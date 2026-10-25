// L03：使用者中途離開開戶流程，證件和臉部影像還留在 App 的沙盒裡（個資只在上傳成功時才刪除）
// 對應複習站：Exchange11eKYC 第 4 題「證件和臉部影像是高敏感的個人資料，App 要怎麼處理？」
//
// 情境：開戶時要拍證件的正面、背面和臉部自拍，先存成暫存檔（App 沙盒）再上傳。個資的原則是「不要長期保留原始影像」：
//       上傳成功就立刻刪除；使用者回上一頁、切到背景、被來電中斷、離開整個流程，也要把暫存檔清掉（重試用「可續傳的上傳」滿足，不是留著原始影像）。
//       演練用一個記憶體裡的假沙盒（不碰真的檔案，也不會有真的影像），三個情境，每個情境各自一個全新的沙盒，都已經拍好三張（front、back、face）：
//       ①上傳成功 ②使用者按返回離開流程 ③上傳失敗（沒有網路），保留影像讓使用者重試，之後使用者離開流程。
//       每個情境結束時，沙盒裡都不應該留下任何影像。
// Bug 版：只在「上傳成功」時刪除；離開流程時什麼都沒做。情境 ②、③ 結束後，三張證件與臉部影像還留在沙盒裡，直到 App 被刪除才消失。
// 修正版：上傳成功時刪除；離開流程時也刪除（回上一頁、進背景、被中斷都算）。上傳失敗之後、使用者還在流程裡的時候，保留影像讓他重試。
import Foundation
import LabKit

/// 假沙盒：只記錄有哪些暫存檔（檔名），不碰真的檔案系統
private final class Sandbox {
    private(set) var files: [String] = []
    func write(_ name: String) { files.append(name) }
    func clear() { files.removeAll() }
}

/// 演練流程（兩個版本共用）：三個情境，每個情境各自一個全新的沙盒
private func scenario(
    _ log: LabLog,
    onUploadSucceeded: (Sandbox) -> Void,
    onLeaveFlow: (Sandbox) -> Void
) {
    var leftover = 0
    func finish(_ name: String, _ sandbox: Sandbox) {
        if sandbox.files.isEmpty {
            log("\(name)：結束時沙盒裡沒有證件影像 ✓")
        } else {
            leftover += 1
            log("\(name)：結束時沙盒裡還留著 \(sandbox.files.count) 個影像檔（\(sandbox.files.joined(separator: "、"))）✗")
        }
    }
    func capture() -> Sandbox {
        let sandbox = Sandbox()
        for name in ["front.jpg", "back.jpg", "face.jpg"] { sandbox.write(name) }
        return sandbox
    }

    let s1 = capture()
    onUploadSucceeded(s1)                                    // 上傳成功
    finish("情境 1（上傳成功）", s1)

    let s2 = capture()
    onLeaveFlow(s2)                                          // 使用者按返回離開流程
    finish("情境 2（按返回離開）", s2)

    let s3 = capture()                                       // 上傳失敗：不呼叫任何清理，影像要留著讓使用者重試
    log("情境 3：上傳失敗後、離開流程前，沙盒裡有 \(s3.files.count) 個影像檔（保留給使用者重試）")
    onLeaveFlow(s3)                                          // 之後使用者離開流程
    finish("情境 3（上傳失敗後離開）", s3)
    log("3 個情境，結束時還留著影像的有 \(leftover) 個")
}

// MARK: - Bug 版
private enum L03Bug {
    fileprivate static func onUploadSucceeded(_ sandbox: Sandbox) {
        sandbox.clear()                                      // 上傳成功就刪除
    }

    fileprivate static func onLeaveFlow(_ sandbox: Sandbox) {
        // 離開流程時什麼都沒做：以為「反正下次還要用」或根本沒想到
    }

    static func run(_ log: LabLog) async {
        scenario(log, onUploadSucceeded: onUploadSucceeded(_:), onLeaveFlow: onLeaveFlow(_:))
    }
}

// MARK: - 修正版
private enum L03Fixed {
    fileprivate static func onUploadSucceeded(_ sandbox: Sandbox) {
        sandbox.clear()
    }

    fileprivate static func onLeaveFlow(_ sandbox: Sandbox) {
        sandbox.clear()                                      // 離開流程（返回、進背景、被中斷）也要清掉暫存的個資
    }

    static func run(_ log: LabLog) async {
        scenario(log, onUploadSucceeded: onUploadSucceeded(_:), onLeaveFlow: onLeaveFlow(_:))
    }
}

// MARK: - 演練設定
private func leftoverCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("3 個情境") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange11eKYC {
    static let l03 = Lab(
        id: "L03",
        title: "中途離開開戶流程，證件影像還留在手機裡",
        question: "第 4 題：證件和臉部影像是高敏感的個人資料，App 要怎麼處理？",
        goal: "證件與臉部影像只在需要的時候存在沙盒：上傳成功就刪，離開流程（返回、進背景、被中斷）也要刪；上傳失敗時才保留給使用者重試。",
        steps: [
            "選「Bug 版」執行，看三個情境結束時沙盒裡還留著什麼。",
            "想一想：使用者拍好證件又不想申請了，這三張影像會留在手機裡多久？別的 App 或備份有可能讀到嗎？",
            "選「修正版」執行，三個情境結束時都沒有影像；情境 3 在離開流程之前仍保留 3 個檔案（給使用者重試）。",
            "進階：iOS 寫檔時用 completeFileProtection（裝置鎖定時無法讀取）；Android 用 App 私有的內部儲存；影像一律不放相簿，也不進 log 與分析工具。",
        ],
        expectBug: "「情境 1（上傳成功）：結束時沙盒裡沒有證件影像 ✓」，「情境 2（按返回離開）：結束時沙盒裡還留著 3 個影像檔（front.jpg、back.jpg、face.jpg）✗」，情境 3 也留著 3 個：「3 個情境，結束時還留著影像的有 2 個」。",
        expectFixed: "三個情境結束時沙盒裡都沒有影像，「情境 3：上傳失敗後、離開流程前，沙盒裡有 3 個影像檔（保留給使用者重試）」，「3 個情境，結束時還留著影像的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = leftoverCount(lines) else { return "演練沒有跑完：要用 scenario(log, onUploadSucceeded:, onLeaveFlow:) 執行" }
            if !lines.contains("情境 1（上傳成功）：結束時沙盒裡沒有證件影像 ✓") { return "Bug 版在上傳成功時要刪除影像（情境 1 結束時沙盒是空的）" }
            return n == 2 ? nil : "Bug 還沒重現：離開流程時什麼都不做，情境 2、3 都會留著 3 個影像檔（目前 \(n) 個情境有留）"
        },
        checkFixed: { lines in
            guard let n = leftoverCount(lines) else { return "演練沒有跑完：要用 scenario(log, onUploadSucceeded:, onLeaveFlow:) 執行" }
            return n == 0 ? nil : "還有 \(n) 個情境結束時留著影像：上傳成功和離開流程這兩個時機都要清掉暫存"
        }
    )
}
