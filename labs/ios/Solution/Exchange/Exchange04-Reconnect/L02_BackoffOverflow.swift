// L02：伺服器維護了 10 分鐘，之後重連的等待時間變成負數或 0，
// 手機狂打伺服器（退避的位移溢位）
// 對應複習站：Exchange04Reconnect 第 2 題「斷線後要等多久再重連？指數退避和
// jitter 是什麼？」
//
// 情境：等待時間 = 隨機(0, min(上限, 起始 × 2 的 attempt 次方))，起始 500
// 毫秒、上限 8 秒。attempt 是目前連續失敗的次數。
//       伺服器維護 10 分鐘，每次最多等 8 秒，重試了六十幾次還是失敗，attempt
// 一路加到很大。
//       演練把亂數固定成 0.5，所以 attempt 4 以後，等待時間都應該是 4000
// 毫秒（上限 8000 的一半）。
//       （jitter 本身為什麼要加，Platform05 的演練 6 練過；這題看的是 attempt
// 變大之後的溢位。）
// Bug 版：用位移直接算：上限 = min(8000, 500 << attempt)。attempt 到 55 以後，
// 500 左移的結果超出 64 位元整數，變成負數或 0，
//        min 把負數留了下來，等待時間變成負數或 0，重連的計時器馬上響，
// 手機一直重連。
// 修正版：先封頂再計算：用迴圈一次翻一倍，到 cap 就停，attempt 再大也不會溢位。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：算出不同 attempt 的等待時間，和正確答案比對
private func scenario(_ log: LabLog, delayMs: (Int, Double) -> Int64) {
    var problems = 0
    for attempt in [0, 4, 10, 40, 55, 58, 62, 64, 100] {
        let delay = delayMs(attempt, 0.5)
        let expected = E04Backoff.delayMs(attempt: attempt, random: 0.5)
        if delay == expected {
            log("連續失敗 \(attempt) 次後，下一次等待 \(delay) ms ✓")
        } else {
            problems += 1
            log("連續失敗 \(attempt) 次後，下一次等待 \(delay) ms ✗（應該是 \(expected) ms）")
        }
    }
    log("共 9 種 attempt，等待時間算錯的有 \(problems) 種")
}

// MARK: - Bug 版
private enum L02Bug {
    static func delayMs(
        attempt: Int, baseMs: Int64 = 500, capMs: Int64 = 8000, random: Double
    ) -> Int64 {
        // 500 << 55 超出 64 位元：變成負數；<< 62 以上變成 0
        let ceiling = min(capMs, baseMs << Int64(attempt))
        return Int64(Double(ceiling) * random)
    }

    static func run(_ log: LabLog) async {
        scenario(log) { attempt, random in
            delayMs(attempt: attempt, random: random)
        }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func delayMs(
        attempt: Int, baseMs: Int64 = 500, capMs: Int64 = 8000, random: Double
    ) -> Int64 {
        var ceiling = baseMs
        var i = 0
        // 一次翻一倍，到 cap 就停：attempt 再大也不會溢位
        while i < attempt && ceiling < capMs {
            ceiling = min(ceiling * 2, capMs)
            i += 1
        }
        return Int64(Double(min(ceiling, capMs)) * random)
    }

    static func run(_ log: LabLog) async {
        scenario(log) { attempt, random in
            delayMs(attempt: attempt, random: random)
        }
    }
}

// MARK: - 演練設定
private func problemCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 9 種 attempt") })
    else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

private func delay(_ lines: [String], attempt: Int) -> Int64? {
    guard let line = lines.first(where: { $0.hasPrefix("連續失敗 \(attempt) 次後") })
    else { return nil }
    let tail = line.components(separatedBy: "下一次等待 ").last ?? ""
    return Int64(tail.components(separatedBy: " ms").first ?? "")
}

extension Exchange04Reconnect {
    static let l02 = Lab(
        id: "L02",
        title: "維護後重連的等待時間變成負數",
        question: "第 2 題：斷線後要等多久再重連？指數退避和 jitter 是什麼？",
        goal:
            "attempt 變得很大時，等待時間還是要在 0 到上限之間。計算上限時，不能讓 起始 × 2 的 attempt 次方 溢位。",
        steps: [
            "選「Bug 版」執行，看哪些 attempt 的等待時間算錯了，是從幾次開始、錯成什麼樣子。",
            "想一想：500 左移 55 位，64 位元整數放得下嗎？超出去的部分會怎樣？",
            "選「修正版」執行，9 種 attempt 都要和正確答案一致（attempt 4 以後都是 4000）。",
            "進階：遇到明確的限流（429、too.many.connection），要用比較長的起始等待時間，不要和一般斷線用同一套節奏。",
        ],
        expectBug:
            "attempt 55、58 的等待時間是很大的負數，62、64、100 是 0，「共 9 種 attempt，等待時間算錯的有 5 種」；等待時間是 0，計時器馬上響，等於沒有退避。",
        expectFixed: "attempt 4 以後都是 4000 ms，「共 9 種 attempt，等待時間算錯的有 0 種」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard problemCount(lines) != nil else {
                return "演練沒有跑完：要用 scenario(log, delayMs:) 執行"
            }
            if delay(lines, attempt: 4) != 4000 {
                return
                    "Bug 版在 attempt 還小的時候要算對：上限 = min(8000, 500 左移 attempt 位)，attempt 4 應該是 4000"
            }
            guard let d55 = delay(lines, attempt: 55),
                let d62 = delay(lines, attempt: 62)
            else { return "演練沒有跑完" }
            if d55 >= 0 {
                return
                    "Bug 還沒重現：用 baseMs << Int64(attempt) 算，attempt 55 時會溢位成負數（目前 \(d55)）"
            }
            return d62 == 0
                ? nil : "Bug 還沒重現：attempt 62 時 500 左移 62 位會變成 0（目前 \(d62)）"
        },
        checkFixed: { lines in
            guard let n = problemCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, delayMs:) 執行"
            }
            return n == 0
                ? nil
                : "還有 \(n) 種 attempt 算錯：用迴圈一次翻一倍，ceiling 到 capMs 就停（不要用位移）"
        }
    )
}
