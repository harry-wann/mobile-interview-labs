// L03：伺服器連上就踢人，手機每秒重連一次（一連上就把失敗次數歸零）
// 對應複習站：Exchange04Reconnect 第 3 題「什麼時候才可以把失敗次數歸零？」
//
// 情境：交易高峰期，伺服器限制新連線：App 連得上，但連上 0.3 秒就被踢掉。
//       重連的等待時間用正確的指數退避（起始 500 毫秒、上限 8 秒，亂數固定 0.
// 5，結果每次一樣）。
//       演練用虛擬的時間跑 60 秒，看 App
// 這段時間連了幾次線（時間用毫秒數傳進來，不用真的等）。
//       前 5 次連上，都在 0.3 秒後被踢；第 6 次連上後，伺服器恢復正常，
// 連線穩定維持了 20 秒才斷；之後又開始每次連上 0.3 秒就被踢。
// Bug 版：一連上（opened）就把失敗次數歸零。連上、被踢、次數回到 0，下一次只等
// 500 毫秒左右，馬上又連、又被踢……變成緊密迴圈（flapping，連線抖動）。
// 修正版：連上時只記下時間；斷線時，如果這條連線已經穩定超過 10 秒才歸零，
// 否則失敗次數繼續累加，等待時間越來越長。
import Foundation
import LabKit

private protocol ReconnectManager {
    func opened(nowMs: Int64)
    /// 連線關閉了，回傳下一次重連要等多久（毫秒）
    func closed(nowMs: Int64) -> Int64
}

/// 演練流程（兩個版本共用）：虛擬時間 60 秒；第 6 次連線穩定了 20 秒，
/// 其他每次連上 300 毫秒就被伺服器踢掉
private func scenario(_ log: LabLog, manager: ReconnectManager) {
    var now: Int64 = 0
    var connects = 0
    var waits: [Int64] = []
    while now < 60_000 {
        connects += 1
        manager.opened(nowMs: now)
        // 第 6 次連線穩定 20 秒，其他的 300 毫秒後被踢掉
        now += connects == 6 ? 20_000 : 300
        let wait = manager.closed(nowMs: now)
        if connects == 6 {
            log("第 6 次連線穩定了 20 秒才斷線，下一次等待 \(wait) ms")
        } else {
            waits.append(wait)
        }
        now += wait
    }
    log(
        "其他連線被踢之後，前 5 次的等待時間（毫秒）：\(waits.prefix(5).map { "\($0)" }.joined(separator: "、"))"
    )
    log("60 秒內連線了 \(connects) 次")
}

// MARK: - Bug 版
private enum L03Bug {
    final class Manager: ReconnectManager {
        private var attempt = 0

        func opened(nowMs: Int64) {
            attempt = 0  // 一連上就歸零
        }

        func closed(nowMs: Int64) -> Int64 {
            attempt += 1
            return E04Backoff.delayMs(attempt: attempt, random: 0.5)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, manager: Manager())
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class Manager: ReconnectManager {
        private var attempt = 0
        private var openedAtMs: Int64 = 0

        func opened(nowMs: Int64) {
            // 只記下連上的時間，不歸零
            openedAtMs = nowMs
        }

        func closed(nowMs: Int64) -> Int64 {
            // 這條連線穩定超過 10 秒，才歸零
            if nowMs - openedAtMs >= 10_000 { attempt = 0 }
            attempt += 1
            return E04Backoff.delayMs(attempt: attempt, random: 0.5)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, manager: Manager())
    }
}

// MARK: - 演練設定
private func connectCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("60 秒內連線了") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange04Reconnect {
    static let l03 = Lab(
        id: "L03",
        title: "伺服器連上就踢人，手機每秒重連一次",
        question: "第 3 題：什麼時候才可以把失敗次數歸零？",
        goal:
            "連上之後立刻被踢，不能算成功。失敗次數要等連線穩定一段時間才歸零，不然會變成緊密的重連迴圈；連線真的穩定了，下一次就要從頭開始。",
        steps: [
            "選「Bug 版」執行，看每次斷線後等多久、60 秒內連了幾次。",
            "想一想：每次連上都把次數歸零，下一次斷線的 attempt 是多少？等待時間會越來越長嗎？",
            "選「修正版」執行，等待時間要越來越長，連線次數少很多；穩定了 20 秒的那一次斷線後，要從 500 毫秒重新開始。",
            "進階：網路恢復（從 offline 回來）和 App 回到前景是「現在可以試」的強烈訊號，這兩種情況才會立即重連並歸零。",
        ],
        expectBug: "等待時間一直是 500 毫秒，「60 秒內連線了 51 次」左右（次數以實測為準，一定超過 40 次）。",
        expectFixed:
            "等待時間 500、1000、2000、4000、4000……，穩定 20 秒之後回到 500（「第 6 次連線穩定了 20 秒才斷線，下一次等待 500 ms」），「60 秒內連線了 14 次」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = connectCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, manager:) 執行"
            }
            return n >= 40
                ? nil
                : "Bug 還沒重現：opened 一連上就把 attempt 歸零，等待時間永遠是 500 毫秒，60 秒內應該連 40 次以上（目前 \(n) 次）"
        },
        checkFixed: { lines in
            guard let n = connectCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, manager:) 執行"
            }
            if n > 20 {
                return
                    "還是連太多次（\(n) 次）：opened 不要歸零，只記下連上的時間；closed 時，穩定超過 10 秒才歸零，否則 attempt 加 1"
            }
            if !lines.contains(
                "其他連線被踢之後，前 5 次的等待時間（毫秒）：500、1000、2000、4000、4000")
            {
                return "被踢之後的等待時間應該依序是 500、1000、2000、4000、4000（attempt 越來越大）"
            }
            return lines.contains("第 6 次連線穩定了 20 秒才斷線，下一次等待 500 ms")
                ? nil : "連線穩定超過 10 秒之後斷線，attempt 要歸零，下一次只等 500 毫秒"
        }
    )
}
