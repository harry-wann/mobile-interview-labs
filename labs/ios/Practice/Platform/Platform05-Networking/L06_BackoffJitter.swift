// L06：伺服器剛恢復，1000 支手機在同一瞬間一起湧入（重試要加 jitter）
// 對應複習站：Platform05Networking 第 5 題「網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？」（指數退避與 jitter）
//
// 情境：伺服器短暫當機，1000 支手機同時斷線，各自用「指數退避」決定第 3 次重試要等多久。
//       演練把這 1000 個等待時間依 0.1 秒分組，看同一個 0.1 秒內最多有幾支手機一起重連。
//
// 你要寫：
//   ① Bug 版：backoffSeconds 回傳 min(30, 2 的 attempt 次方) 秒，只有指數退避。
//   ② 修正版：先算出上限 ceiling，再用 Double.random(in: 0...ceiling, using: &rng) 回傳一個隨機的等待時間。
// 只改有 TODO 的地方。解答：Solution/Platform/Platform05-Networking/L06_BackoffJitter.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：1000 支手機各自算第 3 次重試的等待時間。亂數用固定種子，結果每次一樣
private func scenario(_ log: LabLog, backoff: (Int, inout P05SeededRandom) -> Double) {
    var rng = P05SeededRandom(seed: 2026)
    let delays = (0..<1000).map { _ in backoff(3, &rng) }
    var buckets: [Int: Int] = [:]
    for delay in delays { buckets[Int(delay * 10), default: 0] += 1 }       // 每 0.1 秒一組
    log("第 3 次重試：等待時間上限 \(Int(min(30, pow(2, 3.0)))) 秒，實際在 \(String(format: "%.1f", delays.min() ?? 0)) 到 \(String(format: "%.1f", delays.max() ?? 0)) 秒之間")
    log("1000 支手機裡，同一個 0.1 秒內湧入最多的有 \(buckets.values.max() ?? 0) 支")
}

// MARK: - Bug 版
private enum L06Bug {
    /// attempt：第幾次重試（從 0 開始）。上限依序是 1、2、4、8 秒……最多 30 秒
    static func backoffSeconds(attempt: Int, rng: inout P05SeededRandom) -> Double {
        // TODO ①：回傳 min(30, 2 的 attempt 次方) 秒（pow(2, Double(attempt))），不加任何亂數
        0
    }

    static func run(_ log: LabLog) async {
        scenario(log) { attempt, rng in backoffSeconds(attempt: attempt, rng: &rng) }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func backoffSeconds(attempt: Int, rng: inout P05SeededRandom) -> Double {
        let ceiling = min(30, 1 * pow(2, Double(attempt)))
        // TODO ②：在 0 到 ceiling 之間隨機挑一個時間回傳（用 Double.random(in:using:)，亂數來源是 &rng）
        return 0
    }

    static func run(_ log: LabLog) async {
        scenario(log) { attempt, rng in backoffSeconds(attempt: attempt, rng: &rng) }
    }
}

// MARK: - 演練設定
private func peak(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("1000 支手機裡") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Platform05Networking {
    static let l06 = Lab(
        id: "L06",
        title: "伺服器一恢復，1000 支手機同一瞬間湧入",
        question: "第 5 題：網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？",
        goal: "1000 支手機同時斷線、各自退避重連。讓它們的重連時間散開，不要擠在同一個瞬間。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：同一個 0.1 秒內湧入最多幾支手機，出現 ✓。",
            "想一想：1000 支手機算出來的等待時間一樣嗎？伺服器剛恢復，同一瞬間收到 1000 個重連會怎樣？",
            "寫 TODO ②，選「修正版」執行，看最多的那一組降到幾支。",
            "進階：jitter 只解決「大家同時重連」。429 的 Retry-After 標頭是伺服器明確告訴你要等多久，要優先遵守。",
        ],
        expectBug: "「第 3 次重試：等待時間上限 8 秒，實際在 8.0 到 8.0 秒之間」「同一個 0.1 秒內湧入最多的有 1000 支」。",
        expectFixed: "實際等待時間分散在 0 到 8 秒之間，「同一個 0.1 秒內湧入最多的」只有二十幾支（不到 100 支）。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = peak(lines) else { return "演練沒有跑完：要用 scenario(log, backoff:) 執行" }
            if !lines.contains(where: { $0.contains("實際在 8.0 到 8.0 秒之間") }) { return "第 3 次重試的等待時間應該是 min(30, 2 的 3 次方) = 8 秒" }
            return n >= 900 ? nil : "Bug 還沒重現：只用指數退避、不加亂數，1000 支手機的等待時間應該一模一樣（目前最多 \(n) 支）"
        },
        checkFixed: { lines in
            guard let n = peak(lines) else { return "演練沒有跑完：要用 scenario(log, backoff:) 執行" }
            return n < 100 ? nil : "重連時間還是擠在一起（最多 \(n) 支）：在 0 到上限之間用 Double.random(in:using:) 隨機挑一個時間"
        }
    )
}
