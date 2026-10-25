// L01：後台說 crash-free users 是 92%，其實是 95%（crash-free users 怎麼算、
// 問題怎麼排序）
// 對應複習站：Advanced06Crash 第 1 題「crash-free users 是什麼？和 crash-free
// sessions 差在哪？」（問題的排序見第 5 題的步驟二）
//
// 情境：一天的資料：100 位使用者、1000 次 session，共 8 筆 crash 事件，分屬 3
// 個問題。
//       其中 u07 卡在「失敗就重試」的迴圈，同一個問題連續 crash 了 4 次；另外
// 4 位使用者各 crash 1 次。
//
// 你要寫：
//   ① Bug 版：把「crash 事件的筆數」當成「受影響的使用者數」，
// 問題排行也數筆數。u07 一個人會被算成 4 位，crash-free users 變成 92%。
//   ② 修正版：用 userId 去重，數「不同的使用者」有幾位；
// 問題排行也按每個問題影響幾位使用者。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced06-Crash/L01_CrashFreeUsers.swift
import Foundation
import LabKit

private struct CrashEvent {
    let userId: String
    let issue: String
}

private let totalUsers = 100
private let totalSessions = 1_000

/// 一天的 crash 事件：OrderViewModel 強制解包 4 筆（都是 u07）、Realm 跨執行緒
/// 3 筆（3 位）、K 線圖記憶體 1 筆（1 位）
private let events: [CrashEvent] = [
    CrashEvent(userId: "u07", issue: "OrderViewModel 強制解包"),
    CrashEvent(userId: "u07", issue: "OrderViewModel 強制解包"),
    CrashEvent(userId: "u07", issue: "OrderViewModel 強制解包"),
    CrashEvent(userId: "u07", issue: "OrderViewModel 強制解包"),
    CrashEvent(userId: "u21", issue: "Realm 跨執行緒"),
    CrashEvent(userId: "u35", issue: "Realm 跨執行緒"),
    CrashEvent(userId: "u58", issue: "Realm 跨執行緒"),
    CrashEvent(userId: "u90", issue: "K 線圖記憶體"),
]

private func percent(_ part: Int, of whole: Int) -> String {
    String(format: "%.1f%%", Double(part) / Double(whole) * 100)
}

/// 演練流程（兩個版本共用）：算 crash-free users／sessions，再列出問題排行
private func scenario(
    _ log: LabLog,
    affectedUsers: ([CrashEvent]) -> Int,
    usersPerIssue: ([CrashEvent]) -> [String: Int]
) {
    let affected = affectedUsers(events)
    log(
        "crash-free users：\(percent(totalUsers - affected, of: totalUsers))（受影響 \(affected) 位／共 \(totalUsers) 位）"
    )
    log(
        "crash-free sessions：\(percent(totalSessions - events.count, of: totalSessions))（crash \(events.count) 次／共 \(totalSessions) 次）"
    )
    let ranking = usersPerIssue(events).sorted {
        $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key
    }
    for (issue, users) in ranking { log("問題排行：\(issue)，影響 \(users) 位") }
    if let top = ranking.first { log("最優先要修：\(top.key)") }
}

// MARK: - Bug 版
private enum L01Bug {
    /// 受影響的使用者數
    static func affectedUsers(_ events: [CrashEvent]) -> Int {
        // TODO ①-1：把「事件的筆數」當成受影響的使用者數回傳（一筆 crash
        // 事件算一位使用者）
        return 0
    }

    /// 每個問題影響幾位使用者
    static func usersPerIssue(_ events: [CrashEvent]) -> [String: Int] {
        // TODO ①-2：用 Dictionary(grouping:by:) 依 issue 分組，
        // 每個問題的值是這一組的「筆數」
        return [:]
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, affectedUsers: affectedUsers, usersPerIssue: usersPerIssue)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func affectedUsers(_ events: [CrashEvent]) -> Int {
        // TODO ②-1：把每筆事件的 userId 放進 Set 去重，回傳不同使用者的數量
        return 0
    }

    static func usersPerIssue(_ events: [CrashEvent]) -> [String: Int] {
        // TODO ②-2：一樣依 issue 分組，但每個問題的值是這一組裡「不同 userId」
        // 的數量
        return [:]
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, affectedUsers: affectedUsers, usersPerIssue: usersPerIssue)
    }
}

// MARK: - 演練設定
extension Advanced06Crash {
    static let l01 = Lab(
        id: "L01",
        title: "後台說 92%，其實是 95%",
        question: "第 1 題：crash-free users 是什麼？和 crash-free sessions 差在哪？",
        goal:
            "crash-free users 的分母和分子都是「人」，不是 crash 的次數；問題的優先順序也要看影響幾位使用者。 先寫出數筆數的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced06-Crash/L01_CrashFreeUsers.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 crash-free users 的數字，以及「最優先要修」是哪一個問題。",
            "想一想：u07 一個人 crash 了 4 次，算 1 位使用者還是 4 位？crash-free sessions 為什麼可以直接用筆數除？",
            "寫 TODO ②，選「修正版」執行，比較 crash-free users 與問題排行。",
        ],
        expectBug:
            "「crash-free users：92.0%（受影響 8 位）」；最優先要修的是只影響 1 位使用者的「OrderViewModel 強制解包」。",
        expectFixed:
            "「crash-free users：95.0%（受影響 5 位）」；最優先要修的是影響 3 位使用者的「Realm 跨執行緒」。crash-free sessions 兩個版本都是 99.2%。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("crash-free users：") })
            else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains(where: { $0.hasPrefix("crash-free users：92.0%") }
            ) {
                return "Bug 還沒重現：把事件筆數 8 當成受影響的使用者數，crash-free users 會是 92.0%"
            }
            if !lines.contains("最優先要修：OrderViewModel 強制解包") {
                return "Bug 還沒重現：問題排行要按筆數排，u07 一個人 4 筆會排第一"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("crash-free users：") })
            else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains(where: {
                $0.hasPrefix("crash-free users：95.0%（受影響 5 位")
            }) {
                return "crash-free users 應該是 95.0%：受影響的是 5 位不同的使用者（用 userId 去重）"
            }
            if !lines.contains("最優先要修：Realm 跨執行緒") {
                return "最優先要修的應該是影響 3 位使用者的「Realm 跨執行緒」：每個問題要數不同的 userId"
            }
            return nil
        }
    )
}
