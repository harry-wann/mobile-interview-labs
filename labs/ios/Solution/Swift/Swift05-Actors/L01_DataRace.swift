// L01：10 個任務各數 10 萬次成交，總數卻對不起來（沒有保護的共享可變狀態，
// 資料競爭）
// 對應複習站：Swift05Actors 第 1 題「actor 是什麼？它解決了什麼問題？」
//
// 情境：行情畫面統計成交筆數。10 個任務同時處理推播，每個任務處理 10 萬筆，
// 每筆呼叫一次 increment()，總數應該是 100 萬。
// Bug 版：計數器是一般的 class，為了讓編譯器不抱怨標了 @unchecked Sendable，
// 但裡面沒有任何保護。
//        （拿掉 @unchecked Sendable，Strict Concurrency Checking 設成 Complete
// 會出現警告，Swift 6 語言模式是編譯錯誤：
//          passing closure as a 'sending' parameter risks causing data races。
// 標上 @unchecked 只是讓它消失。）
//        count += 1 其實是「讀、加一、寫回」三步，多個執行緒同時做，
// 會有人把別人剛寫的值蓋掉，總數變少，而且每次不一樣。
// 修正版：改成 actor。外部呼叫要 await 排隊，一次只有一個呼叫在裡面，
// 不會有資料競爭。
import Foundation
import LabKit

private let taskCount = 10
private let perTask = 100_000

// MARK: - Bug 版
private enum L01Bug {
    /// @unchecked Sendable：向編譯器保證「同步由我自己負責」，但其實什麼都沒做
    final class TradeCounter: @unchecked Sendable {
        private(set) var count = 0
        // @inline(never)：Release 建置才不會把整個迴圈合併成一次加法，
        // 看得到真實的競爭
        @inline(never) func increment() { count += 1 }
    }

    static func run(_ log: LabLog) async {
        let counter = TradeCounter()
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<taskCount {
                group.addTask {
                    for _ in 0..<perTask { counter.increment() }
                }
            }
        }
        log("成交筆數應該是 \(taskCount * perTask)，實際是 \(counter.count)")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    /// actor：外部存取要 await，一次只有一個呼叫在裡面
    actor TradeCounter {
        private(set) var count = 0
        func increment() { count += 1 }
    }

    static func run(_ log: LabLog) async {
        let counter = TradeCounter()
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<taskCount {
                group.addTask {
                    for _ in 0..<perTask { await counter.increment() }
                }
            }
        }
        log("成交筆數應該是 \(taskCount * perTask)，實際是 \(await counter.count)")
    }
}

// MARK: - 演練設定
private func actualCount(_ lines: [String]) -> Int? {
    lines.last.flatMap { line in
        let marker = "實際是 "
        guard let r = line.range(of: marker) else { return nil }
        return Int(line[r.upperBound...])
    }
}

extension Swift05Actors {
    static let l01 = Lab(
        id: "L01",
        title: "100 萬筆成交，只數到不到 100 萬",
        question: "第 1 題：actor 是什麼？它解決了什麼問題？",
        goal: "10 個任務同時呼叫計數器，總數必須剛好是 100 萬。找出為什麼一般的 class 會少算，改用 actor 修好。",
        steps: [
            "選「Bug 版」執行，看最後一行：實際數到幾筆。再執行一次，數字每次都不一樣。",
            "用 Thread Sanitizer 看資料競爭：Edit Scheme › Run › Diagnostics 勾選 Thread Sanitizer，從 Xcode 執行 Bug 版，Issues navigator 會出現 Data race detected，指出兩條執行緒同時存取 count。",
            "想一想：count += 1 在機器看來是幾個步驟？@unchecked Sendable 保護了什麼？",
            "選「修正版」執行：總數剛好是 1000000。（每次呼叫要 await 排隊，會比 Bug 版慢很多，這是 actor 的成本。）",
        ],
        expectBug:
            "實際數到的筆數少於 1000000，而且每次執行都不一樣；Thread Sanitizer 會回報 Data race detected。",
        expectFixed: "實際是 1000000，每次都一樣。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = actualCount(lines) else { return "找不到實際筆數" }
            guard n > 0 else { return "increment() 要把 count 加一" }
            return n < 1_000_000
                ? nil : "Bug 還沒重現：總數剛好是 1000000，沒有少算（計數器要是沒有任何保護的一般 class）"
        },
        checkFixed: { lines in
            guard let n = actualCount(lines) else { return "找不到實際筆數" }
            return n == 1_000_000
                ? nil : "總數應該剛好是 1000000，實際是 \(n)：計數器要改成 actor"
        }
    )
}
