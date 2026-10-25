// Design02MarketData 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：假的 WebSocket 伺服器（記下訂閱管理者送出的每一則 sub／unsub，推送時依目前的訂閱決定送幾份）、毫秒時鐘。
import Foundation

/// 假的 WebSocket 伺服器：訂閱管理者送出的 sub／unsub 都記在這裡
final class D02FakeServer {
    /// 收到的每一則 sub／unsub，連同收到的時間（毫秒；演練編好的時間，不是真實的時鐘）
    private(set) var frames: [(atMs: Int, text: String)] = []
    /// 目前的時間（毫秒）：由演練流程設定
    var nowMs = 0
    /// topic → 伺服器收到幾次 sub（同一個 topic 訂兩次，每次推送就會送兩份；unsub 會取消這個 topic 的全部訂閱）
    private var active: [String: Int] = [:]

    func send(sub topic: String) {
        frames.append((nowMs, "sub \(topic)"))
        active[topic, default: 0] += 1
    }

    func send(unsub topic: String) {
        frames.append((nowMs, "unsub \(topic)"))
        active[topic] = nil
    }

    var subCount: Int { frames.filter { $0.text.hasPrefix("sub ") }.count }
    var unsubCount: Int { frames.filter { $0.text.hasPrefix("unsub ") }.count }
    var activeTopics: [String] { active.keys.sorted() }
    func isSubscribed(_ topic: String) -> Bool { active[topic] != nil }

    /// 伺服器推送一則這個 topic 的訊息：每有一次 sub，App 就收到一份
    func push(_ topic: String) -> Int { active[topic, default: 0] }
}

/// 現在的時間（毫秒，單調時鐘）
func d02NowMs() -> Double { Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000 }
