// L06：App 閒置一分鐘，回來一次打出 600 個請求，伺服器全部拒絕（token bucket
// 沒有封頂）
// 對應複習站：Exchange08OrderFlow 第 6 題「怎麼用 token bucket 做限流？」
//
// 情境：限流器是 token bucket：容量 10、每秒補 10 個 token，一個請求用掉 1 個。
// 為了避免浮點數誤差，用整數：以「千分之一個 token」為單位，
//       每過 1 毫秒補 `refillPerSecond`（10）個千分之一 token；取得 1 個 token
// 要 1000 個。時間從參數傳進來（注入時鐘）。
//       演練：0 毫秒時把桶裡的 token 用光（成功 10 次）；閒置 60 秒；60,000
// 毫秒時在同一毫秒連續取 token，看成功幾次。
//       正確答案：桶的容量是 10，閒置再久，一次最多只能突發 10 個請求。
// Bug 版：補充 token 時沒有封頂：`milliTokens += 經過的毫秒 ×
// refillPerSecond`。閒置 60 秒累積了 600 個 token，一次可以打出 600 個請求。
// 修正版：補充之後用 `min(capacity × 1000, …)` 封頂；
// 時鐘倒退（經過的時間是負的）時不補充。
import Foundation
import LabKit

/// 一個 token bucket：容量 capacity，每秒補 refillPerSecond 個。`refill`
/// 由兩個版本各自實作。
private class TokenBucket {
    let capacity: Int64
    let refillPerSecond: Int64
    // 千分之一個 token；1 個 token = 1000
    var milliTokens: Int64
    // 上次補充的時間（毫秒）
    var lastMs: Int64

    init(capacity: Int64, refillPerSecond: Int64, startMs: Int64) {
        self.capacity = capacity
        self.refillPerSecond = refillPerSecond
        self.milliTokens = capacity * 1000
        self.lastMs = startMs
    }

    func tryAcquire(nowMs: Int64) -> Bool {
        refill(nowMs)
        guard milliTokens >= 1000 else { return false }
        milliTokens -= 1000
        return true
    }

    func refill(_ nowMs: Int64) {}
}

/// 演練流程（兩個版本共用）：用光 → 閒置 60 秒 → 同一毫秒連續取
private func scenario(_ log: LabLog, bucket: TokenBucket) {
    func drain(at ms: Int64) -> Int {
        var count = 0
        while count < 1000 && bucket.tryAcquire(nowMs: ms) { count += 1 }
        return count
    }
    log("桶的容量是 \(bucket.capacity)，每秒補 \(bucket.refillPerSecond) 個")
    let first = drain(at: 0)
    log("0 毫秒：連續取 token，成功 \(first) 次，桶空了")
    let burst = drain(at: 60_000)
    log("閒置 60 秒之後（60,000 毫秒）：同一毫秒連續取 token，成功 \(burst) 次")
    log("最後：閒置之後一次最多成功 \(burst) 次（正確答案：10 次）")
}

// MARK: - Bug 版
private enum L06Bug {
    final class Bucket: TokenBucket {
        override func refill(_ nowMs: Int64) {
            let elapsed = nowMs - lastMs
            // 沒有封頂：閒置越久，累積越多
            milliTokens += elapsed * refillPerSecond
            lastMs = nowMs
        }
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, bucket: Bucket(capacity: 10, refillPerSecond: 10, startMs: 0))
    }
}

// MARK: - 修正版
private enum L06Fixed {
    final class Bucket: TokenBucket {
        override func refill(_ nowMs: Int64) {
            // 時鐘倒退：不補充
            let elapsed = max(0, nowMs - lastMs)
            // 補充之後封頂
            milliTokens = min(
                capacity * 1000, milliTokens + elapsed * refillPerSecond)
            lastMs = nowMs
        }
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, bucket: Bucket(capacity: 10, refillPerSecond: 10, startMs: 0))
    }
}

// MARK: - 演練設定
extension Exchange08OrderFlow {
    static let l06 = Lab(
        id: "L06",
        title: "閒置一分鐘，回來一次打出 600 個請求",
        question: "第 6 題：怎麼用 token bucket 做限流？",
        goal:
            "token bucket 的容量決定允許的突發量：補充 token 之後一定要用容量封頂，不然閒置越久、累積的 token 越多，一次可以打出遠超過限制的請求。",
        steps: [
            "選「Bug 版」執行，看閒置 60 秒之後，同一毫秒連續取 token 成功了幾次。",
            "想一想：每秒補 10 個，閒置 60 秒累積了多少？這套 API 每個 API key 每秒只能 10 次，一次打 600 個會怎樣？",
            "選「修正版」執行，閒置之後一次最多只能成功 10 次（桶的容量）。",
            "進階：時鐘倒退（現在的時間比上次記錄的早）時不補充，也不能讓之後的補充算錯。",
        ],
        expectBug: "閒置 60 秒之後成功 600 次：「最後：閒置之後一次最多成功 600 次（正確答案：10 次）」。",
        expectFixed: "閒置 60 秒之後只成功 10 次：「最後：閒置之後一次最多成功 10 次（正確答案：10 次）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, bucket:) 執行"
            }
            return lines.contains(where: { $0.hasPrefix("最後：閒置之後一次最多成功 600 次") }
            )
                ? nil
                : "Bug 還沒重現：refill 要用 milliTokens += elapsed * refillPerSecond（沒有封頂），再把 lastMs 換成 nowMs"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, bucket:) 執行"
            }
            return lines.contains(where: { $0.hasPrefix("最後：閒置之後一次最多成功 10 次") })
                ? nil
                : "refill 要算出經過的毫秒（時鐘倒退用 0），milliTokens 用 min(capacity * 1000, …) 封頂，再把 lastMs 換成 nowMs"
        }
    )
}
