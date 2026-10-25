// L07：一直輪詢卻拿不到 token，等了好幾秒還是 0 個（補充 token 時，
// 整數除法把不到一個的零頭丟掉）
// 對應複習站：Exchange08OrderFlow 第 6 題「怎麼用 token bucket 做限流？」
//
// 情境：限流器是 token bucket：容量 3、每秒補 3 個 token（約每 333
// 毫秒補一個）。0 毫秒時先把桶用光，之後每 100 毫秒問一次「有沒有 token」，問
// 30 次（3 秒）。
//       正確答案：每秒補 3 個，3 秒內應該成功 9 次；之後再隔 1 秒一次問 5 次，
// 成功 3 次（桶補滿了）。
//
// 你要寫：
//   ① Bug 版（L07Bug.Bucket.refill）：whole = elapsed * refillPerSecond /
// 1000（整數 token）；whole > 0 才 milliTokens 加 whole * 1000（再封頂）；
// lastMs 每次都換成 nowMs。
//   ② 修正版（L07Fixed.Bucket.refill）：以千分之一個 token 為單位：milliTokens
// = min(capacity * 1000, milliTokens + elapsed * refillPerSecond)；lastMs =
// nowMs。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange08-OrderFlow/L07_BucketFraction.swift
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

/// 演練流程（兩個版本共用）：用光 → 每 100 毫秒問一次，共 3 秒 → 隔 1 秒一次問
/// 5 次
private func scenario(_ log: LabLog, bucket: TokenBucket) {
    var initial = 0
    while bucket.tryAcquire(nowMs: 0) { initial += 1 }
    log("0 毫秒：把桶用光，成功 \(initial) 次")
    var polled = 0
    for ms in stride(from: Int64(100), through: 3000, by: 100)
    where bucket.tryAcquire(nowMs: ms) { polled += 1 }
    log("接著每 100 毫秒問一次，3 秒內成功 \(polled) 次（正確答案：9 次）")
    var later = 0
    for _ in 0..<5 where bucket.tryAcquire(nowMs: 4000) { later += 1 }
    log("再隔 1 秒（4,000 毫秒）一次問 5 次，成功 \(later) 次（正確答案：3 次）")
    log("最後：每 100 毫秒問一次，3 秒內成功 \(polled) 次；隔 1 秒一次問，成功 \(later) 次")
}

// MARK: - Bug 版
private enum L07Bug {
    final class Bucket: TokenBucket {
        override func refill(_ nowMs: Int64) {
            // TODO ①：elapsed = nowMs - lastMs；whole = elapsed *
            // refillPerSecond / 1000（只算整數個 token）；
            //        whole > 0 才 milliTokens = min(capacity * 1000,
            // milliTokens + whole * 1000)；不管有沒有補，lastMs 每次都換成
            // nowMs
        }
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, bucket: Bucket(capacity: 3, refillPerSecond: 3, startMs: 0))
    }
}

// MARK: - 修正版
private enum L07Fixed {
    final class Bucket: TokenBucket {
        override func refill(_ nowMs: Int64) {
            // TODO ②：elapsed = max(0, nowMs - lastMs)；milliTokens =
            // min(capacity * 1000, milliTokens + elapsed * refillPerSecond)
            //        （以千分之一個 token 為單位，不丟零頭）；lastMs = nowMs
        }
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, bucket: Bucket(capacity: 3, refillPerSecond: 3, startMs: 0))
    }
}

// MARK: - 演練設定
extension Exchange08OrderFlow {
    static let l07 = Lab(
        id: "L07",
        title: "一直輪詢，卻拿不到 token",
        question: "第 6 題：怎麼用 token bucket 做限流？",
        goal:
            "補充 token 要用整數的最小單位（千分之一個 token）累積，不能每次都把不到一個 token 的零頭丟掉，同時把上次補充的時間往前移。先寫出用整數 token 補充、丟掉零頭的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange08-OrderFlow/L07_BucketFraction.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看每 100 毫秒問一次，3 秒內成功幾次？隔 1 秒再一次問 5 次呢？",
            "想一想：每秒補 3 個，100 毫秒應該補 0.3 個。用整數 100 × 3 ÷ 1000 算出來是多少？剩下的 0.3 去哪了？",
            "寫 TODO ②（修正版），選「修正版」執行，3 秒內成功 9 次，隔 1 秒再問成功 3 次。",
            "進階：向上取整要等多久：每秒補 3 個，補一個要 1000 ÷ 3 = 333.33… 毫秒，所以是 334 毫秒；等 333 毫秒還不夠。",
        ],
        expectBug:
            "每 100 毫秒問一次，3 秒內成功 0 次；隔 1 秒一次問才成功 3 次：「最後：每 100 毫秒問一次，3 秒內成功 0 次；隔 1 秒一次問，成功 3 次」。",
        expectFixed:
            "3 秒內成功 9 次，隔 1 秒成功 3 次：「最後：每 100 毫秒問一次，3 秒內成功 9 次；隔 1 秒一次問，成功 3 次」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, bucket:) 執行"
            }
            return lines.contains("最後：每 100 毫秒問一次，3 秒內成功 0 次；隔 1 秒一次問，成功 3 次")
                ? nil
                : "Bug 還沒重現：refill 用整數 token（elapsed * refillPerSecond / 1000），不到 1 個就不補，但 lastMs 每次都換成 nowMs"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, bucket:) 執行"
            }
            return lines.contains("最後：每 100 毫秒問一次，3 秒內成功 9 次；隔 1 秒一次問，成功 3 次")
                ? nil
                : "milliTokens 以千分之一個 token 為單位累積：milliTokens + elapsed * refillPerSecond，再用 min(capacity * 1000, …) 封頂，lastMs 換成 nowMs"
        }
    )
}
