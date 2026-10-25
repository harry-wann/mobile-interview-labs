// L02：餘額只有 100，連點兩次提領 80，兩筆都成功，餘額變成 -60（actor 的
// reentrancy）
// 對應複習站：Swift05Actors 第 2 題「actor 的 reentrancy 是什麼？怎麼修？」
//
// 情境：提領 80 円的流程是「檢查可用餘額 → 向伺服器確認（await）→ 扣款」。
// 使用者連點兩次，兩個提領幾乎同時進來，餘額 100。
// Bug 版：Account 是 actor，但檢查和扣款之間隔著 await。第一個呼叫在 await
// 暫停、讓出 actor 的時候，
//        第二個呼叫進來，看到的餘額還是 100，也通過檢查。兩個都扣款，餘額變成
// -60。
// 修正版：在 await 之前就先把額度扣掉（保留額度）。第二個呼叫進來時，
// 餘額已經只剩 20，檢查就會失敗。
//        （另一種修法是 await 之後重新檢查一次。）
import Foundation
import LabKit

// MARK: - Bug 版
private enum L02Bug {
    actor Account {
        private(set) var balance = 100

        func withdraw(_ amount: Int) async -> Bool {
            // 步驟 1：檢查餘額
            guard balance >= amount else { return false }
            // 步驟 2：向伺服器確認。這裡會暫停，讓出 actor
            try? await Task.sleep(for: .milliseconds(10))
            // 步驟 3：扣款（這時候餘額可能已經被別人改過）
            balance -= amount
            return true
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
            // 步驟 1：檢查餘額
            guard balance >= amount else { return false }
            // 步驟 2：先扣掉（保留額度），後面的呼叫就看得到
            balance -= amount
            // 步驟 3：向伺服器確認。暫停時餘額已經是最新的
            try? await Task.sleep(for: .milliseconds(10))
            return true
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
            "選「Bug 版」執行，看兩行 log：成功幾筆、最後餘額。",
            "想一想：Account 明明是 actor，為什麼兩個呼叫都通過了檢查？第一個呼叫在哪裡把 actor 讓出去？",
            "選「修正版」執行：只成功 1 筆，餘額 20。",
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
