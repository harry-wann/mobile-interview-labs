// L04：重新審查中，出金按鈕卻又亮了起來（審查狀態的更新亂序，舊的回應蓋掉新的推播）
// 對應複習站：Exchange11eKYC 第 5 題「審查要數小時到數日，App 的狀態要怎麼設計？」
//
// 情境：審查狀態由伺服器管理，App 有兩個管道拿到它：啟動與回前景時主動抓（fetch），以及審查結果的推播。兩個管道的到達順序沒有保證：
//       fetch 的請求先送出，回應卻比後來才發出的推播晚到。每一份狀態都帶著伺服器給的版本號 revision（越大越新），
//       內容是 kycStatus 與 canWithdraw（能不能出金，由伺服器決定，App 只負責顯示，不自己推論）。
//       演練依到達的順序送進 4 份狀態：
//       ① fetch（rev 3）：通過，可以出金 → 出金按鈕開啟
//       ② 推播（rev 4）：重新審查中（例如要求補件），不可出金 → 出金按鈕關閉
//       ③ 晚到的 fetch 回應（rev 3，其實是①那份舊的）→ 伺服器最新的狀態還是 rev 4：審查中，按鈕應該維持關閉
//       ④ 推播（rev 5）：通過，可以出金 → 按鈕開啟
// Bug 版：新收到的狀態一律蓋掉舊的（「最後到的就是最新的」）。③ 晚到的舊回應把審查中蓋掉，畫面變回「通過」，出金按鈕又亮了。
// 修正版：只接受比目前 revision 大的狀態；版本號一樣或更小的（晚到的舊回應）直接忽略。
import Foundation
import LabKit

private struct AccountState {
    let revision: Int
    let status: String          // 審查狀態（顯示用）
    let canWithdraw: Bool       // 能不能出金：伺服器決定
}

/// 演練流程（兩個版本共用）：依到達順序送進 4 份狀態；每一步和「伺服器當下最新的狀態」比對
private func scenario(_ log: LabLog, merge: (_ current: AccountState?, _ update: AccountState) -> AccountState) {
    let rev3 = AccountState(revision: 3, status: "通過", canWithdraw: true)
    let rev4 = AccountState(revision: 4, status: "審查中", canWithdraw: false)
    let rev5 = AccountState(revision: 5, status: "通過", canWithdraw: true)
    // （描述, 到達的狀態, 伺服器當下最新的狀態）
    let steps: [(String, AccountState, AccountState)] = [
        ("① fetch 回應 rev 3", rev3, rev3),
        ("② 推播 rev 4", rev4, rev4),
        ("③ 晚到的 fetch 回應 rev 3", rev3, rev4),
        ("④ 推播 rev 5", rev5, rev5),
    ]
    var current: AccountState?
    var wrong = 0
    for (name, update, truth) in steps {
        let shown = merge(current, update)
        current = shown
        let button = shown.canWithdraw ? "開啟" : "關閉"
        if shown.status == truth.status && shown.canWithdraw == truth.canWithdraw {
            log("\(name) → 畫面：\(shown.status)（rev \(shown.revision)），出金按鈕：\(button) ✓")
        } else {
            wrong += 1
            log("\(name) → 畫面：\(shown.status)（rev \(shown.revision)），出金按鈕：\(button) ✗（伺服器最新是 \(truth.status)，rev \(truth.revision)）")
        }
    }
    log("4 份更新，畫面和伺服器最新狀態不一致的有 \(wrong) 份")
}

// MARK: - Bug 版
private enum L04Bug {
    fileprivate static func merge(_ current: AccountState?, _ update: AccountState) -> AccountState {
        update                                     // 最後到的就蓋掉前面的
    }

    static func run(_ log: LabLog) async {
        scenario(log, merge: merge(_:_:))
    }
}

// MARK: - 修正版
private enum L04Fixed {
    fileprivate static func merge(_ current: AccountState?, _ update: AccountState) -> AccountState {
        guard let current else { return update }
        return update.revision > current.revision ? update : current       // 只接受更新的版本；晚到的舊回應直接忽略
    }

    static func run(_ log: LabLog) async {
        scenario(log, merge: merge(_:_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("4 份更新") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange11eKYC {
    static let l04 = Lab(
        id: "L04",
        title: "重新審查中，出金按鈕卻又亮了起來",
        question: "第 5 題：審查要數小時到數日，App 的狀態要怎麼設計？",
        goal: "審查狀態由伺服器管理，每一份狀態帶著版本號；App 只接受比目前更新的版本，晚到的舊回應不能蓋掉新的推播。能不能出金一律照伺服器給的 canWithdraw 顯示。",
        steps: [
            "選「Bug 版」執行，看第 ③ 步：晚到的舊回應之後，畫面和出金按鈕是什麼？",
            "想一想：fetch 的請求是什麼時候送出的？它的回應為什麼會比後來的推播晚到？",
            "選「修正版」執行，四份更新的畫面都要和伺服器最新的狀態一致。",
            "進階：就算按鈕被誤開，伺服器收到出金請求時仍然會依帳號狀態拒絕；但畫面顯示成能出金，使用者會以為審查通過了。App 端的旗標不能當作依據。",
        ],
        expectBug: "「③ 晚到的 fetch 回應 rev 3 → 畫面：通過（rev 3），出金按鈕：開啟 ✗（伺服器最新是 審查中，rev 4）」，其他三步是對的：「4 份更新，畫面和伺服器最新狀態不一致的有 1 份」。",
        expectFixed: "③ 的畫面維持「審查中（rev 4），出金按鈕：關閉」，④ 之後才變回通過：「4 份更新，畫面和伺服器最新狀態不一致的有 0 份」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, merge:) 執行" }
            if !lines.contains("② 推播 rev 4 → 畫面：審查中（rev 4），出金按鈕：關閉 ✓") { return "Bug 版也要接受新到的狀態：收到推播 rev 4，畫面要變成審查中" }
            if !lines.contains("③ 晚到的 fetch 回應 rev 3 → 畫面：通過（rev 3），出金按鈕：開啟 ✗（伺服器最新是 審查中，rev 4）") {
                return "Bug 還沒重現：新收到的狀態一律蓋掉舊的，晚到的 rev 3 會把審查中蓋掉"
            }
            return n == 1 ? nil : "Bug 版應該只有第 ③ 步不一致（目前 \(n) 份）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, merge:) 執行" }
            return n == 0 ? nil : "還有 \(n) 份更新的畫面不對：沒有目前狀態就接受；revision 比目前大才接受，否則維持目前的"
        }
    )
}
