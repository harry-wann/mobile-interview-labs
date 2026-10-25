// L04：捲動列表的時候行情更新停住，離開畫面之後畫面物件還在、
// 計時器還在跑（CADisplayLink 的 run loop mode 與強參考）
// 對應複習站：Advanced05HighFrequency 第 5 題「怎麼配合螢幕的更新頻率輸出？」
//
// 情境：行情畫面（PriceScreen）用 CADisplayLink
// 在每一格畫面取走緩衝區裡的最新價，更新列表。兩個要小心的地方：
//        A. 使用者捲動列表的時候，主執行緒的 run loop 會切到 tracking mode；
// 只加在預設模式（default）的計時器，捲動時會暫停。
//        B. CADisplayLink 對它的 target 是強參考。直接把 self 當 target，
// 畫面物件會被計時器抓住：
//           離開畫面時，擁有者放掉畫面物件，deinit 永遠不會被呼叫，
// 計時器也就永遠不會被 invalidate。
//        演練用 RunLoop.main.run(mode: tracking, …) 模擬「正在捲動」，
// 數這段時間內 onFrame 被呼叫幾次；再放掉畫面物件，看它有沒有被釋放。
// Bug 版：add(to: main, forMode: default)，target 傳 self。捲動時 onFrame
// 一次都不會被呼叫；離開畫面後畫面物件還活著，計時器還在跑。
// 修正版：add(to: main, forMode: common)（涵蓋 default 與 tracking），target
// 傳一個只 weak 持有畫面物件的中間物件（WeakTarget），
//        畫面物件的 deinit 才會被呼叫，在裡面 invalidate()。
import UIKit
import LabKit

/// 行情畫面（兩個版本共用）：每一格畫面 onFrame 被呼叫一次，frames 數它。
/// start() 由各版本自己寫
@MainActor
private final class PriceScreen: NSObject {
    private(set) var frames = 0
    var link: CADisplayLink?

    override init() {
        super.init()
        LiveObjects.shared.add("A05PriceScreen")
    }

    deinit {
        // 畫面物件被釋放時，把計時器也停掉
        link?.invalidate()
        LiveObjects.shared.remove("A05PriceScreen")
    }

    @objc func onFrame() { frames += 1 }

    func stop() {
        link?.invalidate()
        link = nil
    }
}

/// 只 weak 持有物件的小盒子：用來觀察畫面物件有沒有被釋放（兩個版本共用）
private struct Observed<T: AnyObject> {
    weak var object: T?
}

/// 在指定的 run loop mode 讓主執行緒的 run loop 跑 seconds 秒（模擬捲動：用
/// tracking mode）（兩個版本共用）
@MainActor
private func spin(_ mode: RunLoop.Mode, seconds: TimeInterval) {
    let end = Date(timeIntervalSinceNow: seconds)
    while Date() < end { RunLoop.main.run(mode: mode, before: end) }
}

/// 演練流程（兩個版本共用）：A. 捲動時有沒有更新，B. 離開畫面後有沒有釋放
@MainActor
private func scenario(_ log: LabLog, start: (PriceScreen) -> Void) async {
    var screen: PriceScreen? = PriceScreen()
    let observed = Observed(object: screen)
    start(screen!)

    let beforeScroll = screen!.frames
    // 使用者正在捲動列表
    spin(.tracking, seconds: 0.5)
    log("捲動中的 0.5 秒，onFrame 被呼叫 \(screen!.frames - beforeScroll) 次")
    let beforeIdle = screen!.frames
    spin(.default, seconds: 0.3)  // 手放開了
    log("平常的 0.3 秒，onFrame 被呼叫 \(screen!.frames - beforeIdle) 次")

    // 離開畫面：擁有者放掉畫面物件，沒有呼叫 stop()（相信 deinit 會處理）
    screen = nil
    try? await Task.sleep(for: .milliseconds(100))
    log("離開畫面之後：\(observed.object == nil ? "畫面物件已釋放" : "畫面物件還活著（洩漏）")")
    // 洩漏的話，這裡手動停掉，免得計時器一直跑
    if let leaked = observed.object {
        leaked.stop()
        log("（演練結束前手動 stop）")
    }
}

// MARK: - Bug 版
private enum L04Bug {
    @MainActor
    static func start(_ screen: PriceScreen) {
        // target 是強參考：計時器抓住畫面物件
        let link = CADisplayLink(
            target: screen, selector: #selector(PriceScreen.onFrame))
        // 只有預設模式：捲動時（tracking mode）會暫停
        link.add(to: .main, forMode: .default)
        screen.link = link
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, start: start)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    /// 中間物件：CADisplayLink 抓著它，它只 weak 持有真正的畫面物件
    @MainActor
    final class WeakTarget: NSObject {
        weak var owner: PriceScreen?
        init(_ owner: PriceScreen) { self.owner = owner }
        @objc func tick(_ link: CADisplayLink) { owner?.onFrame() }
    }

    @MainActor
    static func start(_ screen: PriceScreen) {
        // 不抓住畫面物件
        let link = CADisplayLink(
            target: WeakTarget(screen), selector: #selector(WeakTarget.tick(_:))
        )
        // 涵蓋預設與捲動（tracking）模式
        link.add(to: .main, forMode: .common)
        screen.link = link
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, start: start)
    }
}

// MARK: - 演練設定
extension Advanced05HighFrequency {
    static let l04 = Lab(
        id: "L04",
        title: "捲動時行情更新停住，離開畫面後計時器還在跑",
        question: "第 5 題：怎麼配合螢幕的更新頻率輸出？",
        goal:
            "CADisplayLink 要加到 .common mode，捲動時才不會停；target 用只 weak 持有畫面物件的中間物件，畫面物件才能被釋放。",
        steps: [
            "選「Bug 版」按「執行」，看三行 log：捲動中的 0.5 秒 onFrame 被呼叫 0 次；平常的 0.3 秒有大約 18 次；離開畫面之後「畫面物件還活著（洩漏）」。",
            "選「修正版」按「執行」：捲動中也有大約 30 次，離開畫面之後畫面物件已釋放（deinit 呼叫了 invalidate）。",
            "進階：iOS 15 起可以設 `link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)`，在 ProMotion 裝置上把更新限制在 30 到 60Hz，行情列表不需要 120Hz。",
            "想一想：Android 的 Choreographer.postFrameCallback 只會觸發一次、要自己再註冊，這和 CADisplayLink 的差別是什麼？（見這個單元的第 5 題；Android 版演練 4 練這個）",
        ],
        expectBug:
            "捲動中的 0.5 秒，onFrame 被呼叫 0 次；平常的 0.3 秒約 18 次；離開畫面之後：畫面物件還活著（洩漏）。",
        expectFixed:
            "捲動中的 0.5 秒，onFrame 被呼叫約 30 次；平常的 0.3 秒約 18 次；離開畫面之後：畫面物件已釋放。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard
                let scroll = a05Number(
                    in: lines, prefix: "捲動中的 0.5 秒，onFrame 被呼叫 "),
                let idle = a05Number(
                    in: lines, prefix: "平常的 0.3 秒，onFrame 被呼叫 ")
            else { return "演練沒有跑完" }
            if idle < 5 {
                return
                    "平常（default mode）也沒有收到 onFrame：start 要建立 CADisplayLink、add(to: .main, forMode: .default)，target 傳畫面物件"
            }
            if scroll > 2 {
                return
                    "捲動中還是有 onFrame（\(Int(scroll)) 次）：Bug 版只加到 .default，捲動（tracking mode）時不會觸發"
            }
            if !lines.contains("離開畫面之後：畫面物件還活著（洩漏）") {
                return "離開畫面後畫面物件已經釋放：Bug 版 target 要直接傳畫面物件（強參考）"
            }
            return nil
        },
        checkFixed: { lines in
            guard
                let scroll = a05Number(
                    in: lines, prefix: "捲動中的 0.5 秒，onFrame 被呼叫 "),
                let idle = a05Number(
                    in: lines, prefix: "平常的 0.3 秒，onFrame 被呼叫 ")
            else { return "演練沒有跑完" }
            if idle < 5 {
                return "平常也沒有收到 onFrame：start 要建立 CADisplayLink 並加到 .common"
            }
            if scroll < 5 {
                return
                    "捲動中沒有收到 onFrame：add(to: .main, forMode: .common)，.common 涵蓋 default 與 tracking"
            }
            if lines.contains("離開畫面之後：畫面物件還活著（洩漏）") {
                return "畫面物件沒有被釋放：target 要傳 WeakTarget(screen)，不能直接傳畫面物件"
            }
            return nil
        }
    )
}
