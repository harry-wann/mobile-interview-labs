// L06：算價差用的報價快照，算到一半被推播改掉，價差變成 600（可變的 class 當成快照傳給別的任務）
// 對應複習站：Swift05Actors 第 4 題「Sendable 是什麼？@unchecked Sendable 什麼時候才合理？」
//
// 情境：行情推播每次更新買價（bid）和賣價（ask），買賣價分兩個欄位寫入。分析任務拿到一份快照，先讀買價，
//       等 0.1 秒（做別的運算）再讀賣價，算價差。就在這 0.1 秒中間，推播把買價與賣價都更新了。真正的價差一直是 100。
//
// 你要寫：
//   ① Bug 版：QuoteSnapshot 是 final class（一般的 class，可變，沒有任何保護），有 bid、ask 屬性和 init。
//   ② 修正版：QuoteSnapshot 是 struct（Sendable），有可變的 bid、ask 屬性。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift05-Actors/L06_SendableSnapshot.swift
import Foundation
import LabKit

// MARK: - Bug 版
private enum L06Bug {
    /// 快照：class（參考型別）。可變，沒有任何保護
    // TODO ①：寫 final class QuoteSnapshot（一般的 class，不用標 Sendable），有 var bid: Int、var ask: Int 和 init(bid:ask:)
    final class QuoteSnapshot {
        var bid = 0
        var ask = 0
        init(bid: Int, ask: Int) { }
    }

    static func analyze(_ snapshot: QuoteSnapshot, log: LabLog) async {
        let bid = snapshot.bid                                       // 先讀買價
        try? await Task.sleep(for: .milliseconds(100))               // 做別的運算
        let ask = snapshot.ask                                       // 再讀賣價：這時推播已經改過了
        log("分析任務算出的價差：\(ask - bid)")
    }

    static func run(_ log: LabLog) async {
        let live = QuoteSnapshot(bid: 15_000_000, ask: 15_000_100)
        let analysis = Task { await analyze(live, log: log) }       // 傳給分析任務的是同一個物件
        try? await Task.sleep(for: .milliseconds(50))
        live.bid = 15_000_500                                        // 推播更新：買價、賣價一起往上
        live.ask = 15_000_600
        await analysis.value
        log("實際的價差：\(live.ask - live.bid)")
    }
}

// MARK: - 修正版
private enum L06Fixed {
    /// 快照：struct，所有成員都是 Int，自動是 Sendable
    // TODO ②：改寫成 struct QuoteSnapshot: Sendable，有 var bid: Int、var ask: Int
    struct QuoteSnapshot: Sendable {
        var bid = 0
        var ask = 0
        init(bid: Int, ask: Int) { }
    }

    static func analyze(_ snapshot: QuoteSnapshot, log: LabLog) async {
        let bid = snapshot.bid
        try? await Task.sleep(for: .milliseconds(100))
        let ask = snapshot.ask
        log("分析任務算出的價差：\(ask - bid)")
    }

    static func run(_ log: LabLog) async {
        var live = QuoteSnapshot(bid: 15_000_000, ask: 15_000_100)
        let copy = live                                              // struct：複製一份給分析任務
        let analysis = Task { await analyze(copy, log: log) }
        try? await Task.sleep(for: .milliseconds(50))
        live.bid = 15_000_500
        live.ask = 15_000_600
        await analysis.value
        log("實際的價差：\(live.ask - live.bid)")
    }
}

// MARK: - 演練設定
extension Swift05Actors {
    static let l06 = Lab(
        id: "L06",
        title: "價差算到一半被推播改掉",
        question: "第 4 題：Sendable 是什麼？@unchecked Sendable 什麼時候才合理？",
        goal: "分析任務拿到的報價快照，必須從頭到尾是同一個時間點的資料。不能讓推播在它讀到一半時偷偷改掉。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，比較兩行 log：分析任務算出的價差，和實際的價差（一直是 100），出現 ✓。",
            "想一想：快照是 class 還是 struct？傳給分析任務的是物件的參考，還是一份複製？",
            "寫 TODO ②，選「修正版」執行，分析任務算出的價差應該是 100。",
            "進階：把 Build Settings 的 Strict Concurrency Checking 設成 Complete（或 Swift Language Version 設成 6），Bug 版會出現 sending value of non-Sendable type … risks causing data races；替 QuoteSnapshot 加上 @unchecked Sendable，警告消失，但 bug 還在。",
        ],
        expectBug: "分析任務算出的價差：600；實際的價差：100。",
        expectFixed: "分析任務算出的價差：100；實際的價差：100。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("實際的價差：100") else { return "推播更新後，實際的價差應該是 100" }
            return lines.contains("分析任務算出的價差：600")
                ? nil : "Bug 還沒重現：分析任務應該算出 600（讀到舊買價、新賣價）。快照要是 class，傳給任務的是同一個物件"
        },
        checkFixed: { lines in
            guard lines.contains("實際的價差：100") else { return "推播更新後，實際的價差應該是 100" }
            return lines.contains("分析任務算出的價差：100")
                ? nil : "分析任務應該算出 100：快照要改成 struct，傳給任務的是複製"
        }
    )
}
