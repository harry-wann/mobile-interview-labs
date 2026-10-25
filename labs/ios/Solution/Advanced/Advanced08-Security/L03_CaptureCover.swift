// L03：開始螢幕錄影之後，餘額還是明明白白錄進去了（錄影狀態要監聽變化，進背景要遮住）
// 對應複習站：Advanced08Security 第 6 題「怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？」（iOS：isCaptured 與背景快照）
//
// 情境：資產畫面顯示餘額。iOS 沒有禁止截圖的 API，能做的是：偵測螢幕正在被錄影（或鏡像到外部螢幕）就遮住餘額；
//       App 切到背景時也遮住，因為系統會在這時對畫面截一張快照，顯示在多工切換畫面。
//       演練用假的螢幕狀態（真正的 App 讀 UIScreen 的 isCaptured；iOS 17 起可以讀 trait 的 sceneCaptureState），
//       並直接送出系統會送的通知（UIScreen.capturedDidChangeNotification、UIApplication.didEnterBackgroundNotification…）。
// Bug 版：只在「打開畫面」的當下讀一次 isCaptured。使用者打開畫面之後才開始錄影，餘額照錄；切到背景也沒有遮。
// 修正版：監聽 capturedDidChangeNotification，每次變化都重新讀 isCaptured；進入背景時遮住，回到前景再依錄影狀態決定。
import LabKit
import UIKit

/// 假的螢幕：真正的 App 這裡是 UIScreen（兩個版本共用）
private final class FakeScreen {
    var isCaptured = false
}

/// 餘額要不要遮住（兩個版本共用）
private protocol BalanceCover {
    var isCovered: Bool { get }
}

/// 演練流程（兩個版本共用）：makeCover 是各版本建立「餘額畫面」的方式
@MainActor
private func scenario(_ log: LabLog, makeCover: (FakeScreen) -> BalanceCover) {
    let screen = FakeScreen()
    let cover = makeCover(screen)
    let center = NotificationCenter.default
    func state() -> String { cover.isCovered ? "已遮住" : "顯示中" }

    log("打開餘額畫面（沒有錄影）：\(state())")

    screen.isCaptured = true
    center.post(name: UIScreen.capturedDidChangeNotification, object: nil)
    log("使用者開始螢幕錄影：\(state())")

    screen.isCaptured = false
    center.post(name: UIScreen.capturedDidChangeNotification, object: nil)
    log("使用者停止錄影：\(state())")

    center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
    log("切到背景（系統即將截多工畫面的快照）：\(state())")

    center.post(name: UIApplication.willEnterForegroundNotification, object: nil)
    log("回到前景：\(state())")
}

// MARK: - Bug 版
private enum L03Bug {
    final class BalanceScreen: BalanceCover {
        private(set) var isCovered: Bool

        init(screen: FakeScreen) {
            isCovered = screen.isCaptured                       // 只在打開畫面的當下看一次
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        scenario(log) { BalanceScreen(screen: $0) }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class BalanceScreen: BalanceCover {
        private(set) var isCovered: Bool
        private var observers: [NSObjectProtocol] = []

        init(screen: FakeScreen) {
            isCovered = screen.isCaptured
            let center = NotificationCenter.default
            // 錄影狀態改變：重新讀一次
            observers.append(center.addObserver(forName: UIScreen.capturedDidChangeNotification, object: nil, queue: nil) { [weak self] _ in
                self?.isCovered = screen.isCaptured
            })
            // 進入背景：系統馬上要截快照，先遮住
            observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) { [weak self] _ in
                self?.isCovered = true
            })
            // 回到前景：依目前的錄影狀態決定
            observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil) { [weak self] _ in
                self?.isCovered = screen.isCaptured
            })
        }

        deinit {
            observers.forEach { NotificationCenter.default.removeObserver($0) }
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        scenario(log) { BalanceScreen(screen: $0) }
    }
}

// MARK: - 演練設定
extension Advanced08Security {
    static let l03 = Lab(
        id: "L03",
        title: "開始錄影後，餘額照樣被錄進去",
        question: "第 6 題：怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？（iOS）",
        goal: "iOS 沒辦法阻止截圖，但要在錄影開始時、進入背景時遮住敏感內容，並且在狀態恢復時再顯示。",
        steps: [
            "選「Bug 版」執行，看每個事件之後餘額是「顯示中」還是「已遮住」。",
            "想一想：使用者打開畫面「之後」才開始錄影，只在 init 讀一次 isCaptured 會怎樣？",
            "選「修正版」執行：監聽錄影狀態變化，進入背景時也遮住。",
            "進階：真機上可以從控制中心開啟「螢幕錄影」，或把 iPhone 鏡像到電視，用真的 UIScreen.isCaptured 驗證。截圖（userDidTakeScreenshotNotification）是截完才通知，擋不住。",
        ],
        expectBug: "五個時間點全部是「顯示中」：開始錄影、切到背景，餘額都沒有被遮住。",
        expectFixed: "依序是：顯示中、已遮住（開始錄影）、顯示中（停止錄影）、已遮住（切到背景）、顯示中（回到前景）。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("打開餘額畫面（沒有錄影）：顯示中") else { return "餘額畫面一打開（沒有錄影）要顯示：isCovered 要設成 screen.isCaptured" }
            if !lines.contains("使用者開始螢幕錄影：顯示中") { return "Bug 還沒重現：只在 init 讀一次 isCaptured，開始錄影之後餘額不該被遮住" }
            if !lines.contains("切到背景（系統即將截多工畫面的快照）：顯示中") { return "Bug 還沒重現：Bug 版沒有處理進入背景，切到背景時餘額不該被遮住" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("打開餘額畫面（沒有錄影）：顯示中") else { return "餘額畫面一打開（沒有錄影）要顯示：isCovered 要設成 screen.isCaptured" }
            if !lines.contains("使用者開始螢幕錄影：已遮住") { return "開始錄影後應該遮住：監聽 UIScreen.capturedDidChangeNotification，收到就重新讀 isCaptured" }
            if !lines.contains("使用者停止錄影：顯示中") { return "停止錄影後應該恢復顯示：通知收到時要重新讀 isCaptured，不是永遠遮住" }
            if !lines.contains("切到背景（系統即將截多工畫面的快照）：已遮住") { return "切到背景時應該遮住：監聽 UIApplication.didEnterBackgroundNotification" }
            if !lines.contains("回到前景：顯示中") { return "回到前景（沒有在錄影）應該恢復顯示：監聽 willEnterForegroundNotification，依 isCaptured 決定" }
            return nil
        }
    )
}
