// L04：放量 1% 時只有 40 位使用者，gate 卻憑這個數字決定要擴大或暫停（樣本太少的數字不能拿來決定）
// 對應複習站：Advanced10Observability 第 3 題「新版本發布時，怎麼用指標決定要不要繼續擴大？」
//
// 情境：新版本分階段放量，每一階段結束時 gate 要決定「擴大」或「暫停」。比較的指標是 crash-free users，基準是上一版的 99.2%。
//       演練有四個階段的資料：
//         A. 放量 1%，只有 40 位使用者，沒有人 crash（100%）。B. 放量 1%，40 位使用者，1 位 crash（97.5%）。
//         C. 放量 5%，2000 位使用者，60 位 crash（97.0%）。D. 放量 5%，2000 位使用者，14 位 crash（99.3%）。
// Bug 版：只比較數字：新版 crash-free users 大於等於上一版就擴大，否則暫停。40 位使用者的 100% 沒有意義，卻決定擴大；
//        同樣 40 位、只多 1 位 crash，卻決定暫停（一位使用者的偶然就改變了結論）。
// 修正版：先看樣本數：使用者不到 1000 位就「等待，繼續收集」，不做決定；樣本夠了才比較，並容許 0.3 個百分點以內的誤差。
import Foundation
import LabKit

private struct Stage {
    let name: String
    let users: Int
    let crashedUsers: Int
    var crashFree: Double { 1 - Double(crashedUsers) / Double(users) }
}

private let previousCrashFree = 0.992
private let stages = [
    Stage(name: "A. 放量 1%（40 位，0 位 crash）", users: 40, crashedUsers: 0),
    Stage(name: "B. 放量 1%（40 位，1 位 crash）", users: 40, crashedUsers: 1),
    Stage(name: "C. 放量 5%（2000 位，60 位 crash）", users: 2_000, crashedUsers: 60),
    Stage(name: "D. 放量 5%（2000 位，14 位 crash）", users: 2_000, crashedUsers: 14),
]

/** 演練流程（兩個版本共用）：decide(階段, 上一版的 crash-free users) 回傳「擴大」「暫停」或「等待」 */
private func scenario(_ log: LabLog, decide: (Stage, Double) -> String) {
    for stage in stages {
        log("\(stage.name)：\(decide(stage, previousCrashFree))")
    }
}

// MARK: - Bug 版
private enum L04Bug {
    static func decide(_ stage: Stage, _ previous: Double) -> String {
        stage.crashFree >= previous ? "擴大" : "暫停"            // 只比數字，不管樣本有多少
    }

    static func run(_ log: LabLog) async {
        scenario(log, decide: decide)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static let minimumUsers = 1_000
    static let tolerance = 0.003

    static func decide(_ stage: Stage, _ previous: Double) -> String {
        guard stage.users >= minimumUsers else { return "等待（樣本不足，繼續收集）" }
        return stage.crashFree >= previous - tolerance ? "擴大" : "暫停"
    }

    static func run(_ log: LabLog) async {
        scenario(log, decide: decide)
    }
}

// MARK: - 演練設定
extension Advanced10Observability {
    static let l04 = Lab(
        id: "L04",
        title: "40 位使用者的數字，就決定要不要擴大",
        question: "第 3 題：新版本發布時，怎麼用指標決定要不要繼續擴大？（樣本數）",
        goal: "release gate 要先確認樣本數夠大，才比較指標；樣本不足時的決定是「等待」，不是「擴大」或「暫停」。",
        steps: [
            "選「Bug 版」執行，看 A、B 兩個只有 40 位使用者的階段，gate 做了什麼決定。",
            "想一想：A 和 B 只差 1 位使用者 crash，結論卻相反，這個決定可靠嗎？",
            "選「修正版」執行：A、B 都是「等待」，C 暫停，D 擴大。",
            "進階：樣本數的門檻、容許的誤差要事前定義；更嚴謹的做法是用信賴區間，但「樣本夠大才決定、事前定義門檻」是最基本的。",
        ],
        expectBug: "A「擴大」、B「暫停」，只差 1 位使用者的偶然；C「暫停」、D「擴大」。",
        expectFixed: "A、B：「等待（樣本不足，繼續收集）」；C：「暫停」；D：「擴大」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("A.") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("A. 放量 1%（40 位，0 位 crash）：擴大") { return "Bug 還沒重現：只比較 crash-free users 大於等於上一版就擴大，A 應該是「擴大」" }
            if !lines.contains("B. 放量 1%（40 位，1 位 crash）：暫停") { return "Bug 還沒重現：B 的 crash-free users 是 97.5%，低於上一版，應該是「暫停」" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("A.") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("A. 放量 1%（40 位，0 位 crash）：等待（樣本不足，繼續收集）") { return "A 只有 40 位使用者，應該回「等待（樣本不足，繼續收集）」" }
            if !lines.contains("B. 放量 1%（40 位，1 位 crash）：等待（樣本不足，繼續收集）") { return "B 只有 40 位使用者，應該回「等待（樣本不足，繼續收集）」" }
            if !lines.contains("C. 放量 5%（2000 位，60 位 crash）：暫停") { return "C 樣本夠了，97.0% 比上一版低很多，應該「暫停」" }
            if !lines.contains("D. 放量 5%（2000 位，14 位 crash）：擴大") { return "D 樣本夠了，99.3% 不比上一版差，應該「擴大」" }
            return nil
        }
    )
}
