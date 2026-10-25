// L03：同一個使用者，一下看得到新功能、一下又看不到（放量要用穩定的分桶）
// 對應複習站：Platform07Release 第 4 題「分階段發布（phased release 與 staged
// rollout）怎麼運作？出問題能不能回滾？」（功能開關與放量）
//
// 情境：新功能先對 20% 的使用者開放（遠端 feature flag），出問題能立刻關掉。
// 演練有 1000 位使用者，每位使用者在這一天內打開 App 5 次，
//       每次 App 都問「我在名單裡嗎？」。放量比例從 10% 調到 50% 時，
// 原本看得到的人不應該消失。
//
// 你要寫：
//   ① Bug 版：isEnabled 每次詢問都用 Int.random(in: 0..<100, using: &rng)
// 擲一次骰子，小於 percent 就開。
//   ② 修正版：bucket(of:) 用 FNV-1a 算出使用者的固定桶號（0 到 99）；isEnabled
// 回傳 桶號 < percent。
// 只改有 TODO 的地方。解答：
// Solution/Platform/Platform07-Release/L03_RolloutBucket.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：isEnabled(userId, percent)
/// 回答「這位使用者要不要開」
private func scenario(_ log: LabLog, isEnabled: (String, Int) -> Bool) {
    let users = (0..<1000).map { "user-\($0)" }

    // 20%：每位使用者問 5 次，答案不一致的算「閃爍」
    let flickering = users.filter { user in
        let answers = (0..<5).map { _ in isEnabled(user, 20) }
        return Set(answers).count > 1
    }.count
    log("放量 20%：1000 位使用者各問 5 次，答案不一致的有 \(flickering) 位")

    // 10% 調到 50%：10% 時看得到的人，50% 時是不是還看得到
    let at10 = users.filter { isEnabled($0, 10) }
    let lost = at10.filter { !isEnabled($0, 50) }.count
    log("放量 10% 看得到 \(at10.count) 位，調到 50% 後反而看不到的有 \(lost) 位")
}

/// 固定種子的亂數（Bug 版用，結果才穩定）
private struct SeededRandom: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

// MARK: - Bug 版
private enum L03Bug {
    static func isEnabled(
        userId: String, percent: Int, rng: inout SeededRandom
    ) -> Bool {
        // TODO ①：每次詢問都擲一次骰子：Int.random(in: 0..<100, using: &rng) <
        // percent
        false
    }

    static func run(_ log: LabLog) async {
        var rng = SeededRandom(state: 2026)
        scenario(log) { user, percent in
            isEnabled(userId: user, percent: percent, rng: &rng)
        }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    /// FNV-1a 32 位元雜湊：同樣的字串，不管哪一次、哪一支手機、哪個平台，
    /// 算出來都一樣
    static func bucket(of userId: String) -> Int {
        // TODO ②-1：FNV-1a 32 位元雜湊：var hash: UInt32 = 2_166_136_261；對
        // "rollout:\(userId)".utf8 的每個 byte：
        //          hash = (hash ^ UInt32(byte)) &* 16_777_619；最後回傳
        // Int(hash % 100)
        return 0
    }

    static func isEnabled(userId: String, percent: Int) -> Bool {
        // TODO ②-2：桶號小於 percent 就開：bucket(of: userId) < percent
        false
    }

    static func run(_ log: LabLog) async {
        scenario(log) { user, percent in
            isEnabled(userId: user, percent: percent)
        }
    }
}

// MARK: - 演練設定
private func numbers(_ lines: [String], prefix: String) -> [Int]? {
    lines.first(where: { $0.hasPrefix(prefix) }).map {
        $0.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
    }
}

extension Platform07Release {
    static let l03 = Lab(
        id: "L03",
        title: "同一個使用者，一下看得到新功能、一下又看不到",
        question:
            "第 4 題：分階段發布（phased release 與 staged rollout）怎麼運作？出問題能不能回滾？（功能開關與放量）",
        goal: "遠端功能開關對一部分使用者放量時，同一位使用者的答案要固定；放量比例調高，原本看得到的人不能消失。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看放量 20% 時答案不一致的使用者有幾位，以及 10% 調到 50% 後消失的人數，出現 ✓。",
            "想一想：App 每次問「我在名單裡嗎？」，答案是怎麼決定的？同一位使用者每次一樣嗎？",
            "寫 TODO ②，選「修正版」執行，兩個數字都要是 0。",
            "進階：Swift 的 hashValue 每次啟動 App 的種子都不同，不能拿來分桶；要用固定的雜湊（例如 FNV-1a、SHA-256），而且 iOS、Android、伺服器要用同一套演算法。",
        ],
        expectBug:
            "「放量 20%：1000 位使用者各問 5 次，答案不一致的有」約 660 位；「調到 50% 後反而看不到的有」約 45 位（解答實測 662 位、44 位）。",
        expectFixed: "兩個數字都是 0：「答案不一致的有 0 位」「調到 50% 後反而看不到的有 0 位」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let flicker = numbers(lines, prefix: "放量 20%")?.last,
                let lost = numbers(lines, prefix: "放量 10%")?.last
            else {
                return "演練沒有跑完：要用 scenario(log, isEnabled:) 執行"
            }
            return flicker >= 300 && lost > 0
                ? nil
                : "Bug 還沒重現：每次詢問都用 Int.random(in: 0..<100, using: &rng) 擲骰子，答案才會閃爍（目前不一致 \(flicker) 位、消失 \(lost) 位）"
        },
        checkFixed: { lines in
            guard let flicker = numbers(lines, prefix: "放量 20%")?.last,
                let tenPercent = numbers(lines, prefix: "放量 10%"),
                tenPercent.count == 4
            else {
                return "演練沒有跑完：要用 scenario(log, isEnabled:) 執行"
            }
            let at10 = tenPercent[1], lost = tenPercent[3]
            if flicker != 0 || lost != 0 {
                return
                    "同一位使用者的答案要固定：用 bucket(of: userId) 算出 0 到 99 的桶號，桶號小於 percent 才開（目前不一致 \(flicker) 位、消失 \(lost) 位）"
            }
            return (70...130).contains(at10)
                ? nil : "10% 應該約有 100 位使用者看得到，目前 \(at10) 位：桶號要平均分布在 0 到 99"
        }
    )
}
