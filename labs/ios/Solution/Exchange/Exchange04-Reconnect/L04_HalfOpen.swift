// L04：走出電梯，行情停在 9 秒前的價格，卻沒有重連（半開連線，App 自己送出的 ping 騙了監看）
// 對應複習站：Exchange04Reconnect 第 4 題「系統說「有網路」就真的連得上嗎？什麼是半開連線？」
//
// 情境：半開連線：連線在 App 這端看起來還開著，但對方其實已經收不到你、你也收不到資料；系統不會通知，也不會觸發 closed 的回呼。
//       App 每 5 秒主動送一次 ping（寫進本機的傳送緩衝就算「送出成功」，半開的連線也一樣）。
//       演練用虛擬的時間，一秒一秒跑 60 秒：前 10 秒（第 0 到 9 秒）每秒收到一則行情，之後什麼都收不到。
//       應用層的監看：超過 15 秒沒有「活動」，就主動關閉連線並重連。
// Bug 版：監看把「送出」也算成活動：每次送 ping，最後活動時間就更新。半開連線送得出去、什麼都收不到，最後活動時間永遠是剛剛，監看永遠不會觸發。
// 修正版：只有「收到」伺服器的訊息才更新最後活動時間；超過 15 秒沒有收到任何訊息，就判定連線已死。
import Foundation
import LabKit

private protocol Watchdog {
    func didSend(nowMs: Int64)
    func didReceive(nowMs: Int64)
    func isDead(nowMs: Int64) -> Bool
    var lastActivityMs: Int64 { get }
}

/// 演練流程（兩個版本共用）：虛擬時間一秒一秒走
private func scenario(_ log: LabLog, watchdog: Watchdog) {
    var detectedAt: Int?
    for second in 0...60 {
        let now = Int64(second) * 1000
        if second <= 9 { watchdog.didReceive(nowMs: now) }          // 前 10 秒每秒收到一則行情
        if second % 5 == 0 { watchdog.didSend(nowMs: now) }         // App 每 5 秒送一次 ping（半開連線也寫得進本機緩衝）
        if watchdog.isDead(nowMs: now) {
            detectedAt = second
            log("第 \(second) 秒：超過 15 秒沒有收到任何訊息，判定連線已死，主動關閉並重連")
            break
        }
    }
    log("最後一次活動是第 \(watchdog.lastActivityMs / 1000) 秒")
    if let second = detectedAt {
        log("半開連線在第 \(second) 秒才被偵測到（行情在第 9 秒就停了，過了 \(second - 9) 秒才發現）")
    } else {
        log("60 秒內都沒有偵測到：畫面停在第 9 秒的價格，使用者看著 51 秒前的價格")
    }
}

// MARK: - Bug 版
private enum L04Bug {
    final class Dog: Watchdog {
        private(set) var lastActivityMs: Int64 = 0

        func didSend(nowMs: Int64) { lastActivityMs = nowMs }       // 送出也算活動
        func didReceive(nowMs: Int64) { lastActivityMs = nowMs }
        func isDead(nowMs: Int64) -> Bool { nowMs - lastActivityMs > 15_000 }
    }

    static func run(_ log: LabLog) async {
        scenario(log, watchdog: Dog())
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class Dog: Watchdog {
        private(set) var lastActivityMs: Int64 = 0

        func didSend(nowMs: Int64) {}                               // 送出成功不代表對方收到，不算活動
        func didReceive(nowMs: Int64) { lastActivityMs = nowMs }    // 只有收到伺服器的訊息才更新
        func isDead(nowMs: Int64) -> Bool { nowMs - lastActivityMs > 15_000 }
    }

    static func run(_ log: LabLog) async {
        scenario(log, watchdog: Dog())
    }
}

// MARK: - 演練設定
extension Exchange04Reconnect {
    static let l04 = Lab(
        id: "L04",
        title: "走出電梯，行情停住了卻沒有重連",
        question: "第 4 題：系統說「有網路」就真的連得上嗎？什麼是半開連線？",
        goal: "半開連線系統不會通知。App 要自己監看「有沒有收到伺服器的訊息」，超過一段時間沒收到就主動重連。送出成功不能算成連線還活著。",
        steps: [
            "選「Bug 版」執行，看監看有沒有觸發、最後一次活動是第幾秒。",
            "想一想：App 每 5 秒送一次 ping，半開連線的 send 會失敗嗎？它能證明對方還活著嗎？",
            "選「修正版」執行，看第幾秒偵測到連線已死。",
            "進階：伺服器約每 5 秒送一次 ping，15 秒是三次 ping 的時間（建議值，要依實際測試調整）。監看的計時請用單調時鐘，放在 socket 的背景路徑，不要放在主執行緒；NWPathMonitor 或 onAvailable 只代表系統有路，不能當作連得上的證明。",
        ],
        expectBug: "60 秒內都沒有偵測到：「最後一次活動是第 60 秒」，畫面停在第 9 秒的價格。",
        expectFixed: "「第 25 秒：超過 15 秒沒有收到任何訊息，判定連線已死，主動關閉並重連」，「最後一次活動是第 9 秒」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後一次活動是第") }) { return "演練沒有跑完：要用 scenario(log, watchdog:) 執行" }
            if !lines.contains("最後一次活動是第 60 秒") { return "Bug 還沒重現：didSend 也要更新 lastActivityMs（把送出也算活動），最後一次活動才會是第 60 秒" }
            return lines.contains(where: { $0.hasPrefix("60 秒內都沒有偵測到") }) ? nil : "Bug 版的監看永遠不會觸發：isDead 是「現在 − 最後活動時間 > 15000」"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後一次活動是第") }) { return "演練沒有跑完：要用 scenario(log, watchdog:) 執行" }
            if !lines.contains("最後一次活動是第 9 秒") { return "只有 didReceive 更新 lastActivityMs，didSend 什麼都不做" }
            return lines.contains("第 25 秒：超過 15 秒沒有收到任何訊息，判定連線已死，主動關閉並重連") ? nil : "isDead 應該是「現在 − 最後活動時間 > 15000」：第 9 秒之後過了 15 秒，第 25 秒才超過"
        }
    )
}
