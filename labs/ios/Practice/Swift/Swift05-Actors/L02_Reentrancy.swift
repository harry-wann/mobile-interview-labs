// L02：餘額只有 100，連點兩次提領 80，兩筆都成功，餘額變成 -60（actor 的
// reentrancy）
// 對應複習站：Swift05Actors 第 2 題「actor 的 reentrancy 是什麼？怎麼修？」
//
// 情境：提領 80 円的流程是「檢查可用餘額 → 向伺服器確認（await）→ 扣款」。
// 使用者連點兩次，兩個提領幾乎同時進來，餘額 100。
//
// 你要寫：
//   ① Bug 版：actor Account 的 withdraw：先檢查餘額夠不夠，再 await 一個 Task.
// sleep（模擬向伺服器確認），然後才扣款。
//   ② 修正版：withdraw 檢查餘額後，在 await 之前就先扣款（保留額度），再 await
// Task.sleep。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift05-Actors/L02_Reentrancy.swift
import Foundation
import LabKit

// MARK: - Bug 版
private enum L02Bug {
    actor Account {
        private(set) var balance = 100

        func withdraw(_ amount: Int) async -> Bool {
            // TODO ①：1. guard balance >= amount else { return false
            // }（檢查餘額）
            //        2. try? await Task.sleep(for: milliseconds(10))
            // （向伺服器確認，會暫停）
            //        3. balance -= amount（扣款），回傳 true
            return false
        }
    }

    static func run(_ log: LabLog) async {
        let account = Account()
        async let a = account.withdraw(80)
        async let b = account.withdraw(80)
        let results = await [a, b]
        log("兩次提領 80，成功 \(results.filter { $0 }.count) 筆")
        log("最後餘額：\(await account.balance)")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    actor Account {
        private(set) var balance = 100

        func withdraw(_ amount: Int) async -> Bool {
            // TODO ②：1. 檢查餘額，不夠回傳 false  2. 先 balance -=
            // amount（保留額度）
            //        3. 再 try? await Task.sleep(for: milliseconds(10))
            // （向伺服器確認），回傳 true
            return false
        }
    }

    static func run(_ log: LabLog) async {
        let account = Account()
        async let a = account.withdraw(80)
        async let b = account.withdraw(80)
        let results = await [a, b]
        log("兩次提領 80，成功 \(results.filter { $0 }.count) 筆")
        log("最後餘額：\(await account.balance)")
    }
}

// MARK: - 演練設定
extension Swift05Actors {
    static let l02 = Lab(
        id: "L02",
        title: "連點兩次提領，餘額變成負的",
        question: "第 2 題：actor 的 reentrancy 是什麼？怎麼修？",
        goal: "餘額 100，同時提領兩筆 80，只能成功一筆，最後餘額 20。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看兩行 log：成功幾筆、最後餘額，出現 ✓。",
            "想一想：Account 明明是 actor，為什麼兩個呼叫都通過了檢查？第一個呼叫在哪裡把 actor 讓出去？",
            "寫 TODO ②，選「修正版」執行：只成功 1 筆，餘額 20。",
            "進階：把「先扣款」改成「await 之後重新檢查一次餘額」，也能修好，想想兩種修法的差別。",
        ],
        expectBug: "兩次提領 80，成功 2 筆；最後餘額：-60。",
        expectFixed: "兩次提領 80，成功 1 筆；最後餘額：20。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("兩次提領 80，成功 2 筆") else {
                return "Bug 還沒重現：兩筆提領都應該成功（檢查、await、扣款，檢查和扣款之間隔著 await）"
            }
            return lines.last == "最後餘額：-60" ? nil : "最後餘額應該是 -60（兩筆都扣款）"
        },
        checkFixed: { lines in
            guard lines.contains("兩次提領 80，成功 1 筆") else {
                return "只應該成功 1 筆：在 await 之前就先扣掉額度，第二筆的檢查才會失敗"
            }
            return lines.last == "最後餘額：20" ? nil : "最後餘額應該是 20"
        }
    )
}
