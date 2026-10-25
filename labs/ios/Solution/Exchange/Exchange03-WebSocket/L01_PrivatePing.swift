// L01：私有頻道連上沒多久就被伺服器切斷（三個端點的心跳格式不一樣）
// 對應複習站：Exchange03WebSocket 第 1 題「這套交易所 API 有哪幾個 WebSocket 端點？公開和私有差在哪？」
//
// 情境：App 同時連兩個端點：公開行情 /ws，和私有的訂單與資產 /ws/v2。兩個端點都會定時送 ping，連續 2 次沒有收到正確的 pong，伺服器就切斷連線：
//       /ws 的 ping 是 {"ping":數字}，要回 {"pong":同一個數字}；
//       /ws/v2 的 ping 是 {"action":"ping","data":{"ts":數字}}，要回 {"action":"pong","data":{"ts":同一個數字}}。
//       （為了專心看心跳的格式，這個演練的訊息已經解壓好了，直接是 JSON 文字。）
// Bug 版：心跳的處理只認識 /ws 的格式。/ws/v2 的 ping 沒有 "ping" 這個欄位，被當成「不是心跳」，沒有回 pong，連線被切斷。
// 修正版：依端點分別處理：每個端點各有自己的 ping 格式與對應的 pong 格式。
import Foundation
import LabKit

private enum Endpoint: String {
    case market = "/ws"
    case privateOrders = "/ws/v2"
}

/// 伺服器端：這個端點的 ping 長什麼樣子、收到的回覆是不是對應的 pong
private func pingText(_ endpoint: Endpoint, _ value: Int64) -> String {
    switch endpoint {
    case .market: return "{\"ping\":\(value)}"
    case .privateOrders: return "{\"action\":\"ping\",\"data\":{\"ts\":\(value)}}"
    }
}

private func isCorrectPong(_ endpoint: Endpoint, reply: String?, value: Int64) -> Bool {
    guard let reply, let json = e03Parse(reply) else { return false }
    switch endpoint {
    case .market:
        return (json["pong"] as? NSNumber)?.int64Value == value
    case .privateOrders:
        return json["action"] as? String == "pong" && ((json["data"] as? [String: Any])?["ts"] as? NSNumber)?.int64Value == value
    }
}

/// 演練流程（兩個版本共用）：每個端點依序收到 4 次 ping，連續 2 次沒有正確回覆就被切斷
private func scenario(_ log: LabLog, reply: (String, Endpoint) -> String?) {
    var closedCount = 0
    for endpoint in [Endpoint.market, .privateOrders] {
        var missed = 0
        var closed = false
        for i in 1...4 {
            let value = Int64(18_212_558_000 + i * 5_000)
            if isCorrectPong(endpoint, reply: reply(pingText(endpoint, value), endpoint), value: value) {
                missed = 0
            } else {
                missed += 1
                log("\(endpoint.rawValue)：第 \(i) 次 ping 沒有收到正確的 pong（連續 \(missed) 次）")
                if missed >= 2 { closed = true; break }
            }
        }
        if closed { closedCount += 1 }
        log("\(endpoint.rawValue)：\(closed ? "連續 2 次沒回 pong，伺服器切斷連線" : "連線維持")")
    }
    log("被伺服器切斷的連線有 \(closedCount) 條")
}

// MARK: - Bug 版
private enum L01Bug {
    static func reply(to message: String, endpoint: Endpoint) -> String? {
        guard let json = e03Parse(message), let ping = (json["ping"] as? NSNumber)?.int64Value else { return nil }
        return "{\"pong\":\(ping)}"                             // 只認得 /ws 的 {"ping":N}
    }

    static func run(_ log: LabLog) async {
        scenario(log) { message, endpoint in reply(to: message, endpoint: endpoint) }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func reply(to message: String, endpoint: Endpoint) -> String? {
        guard let json = e03Parse(message) else { return nil }
        switch endpoint {
        case .market:                                           // 公開行情：{"ping":N} → {"pong":N}
            guard let ping = (json["ping"] as? NSNumber)?.int64Value else { return nil }
            return "{\"pong\":\(ping)}"
        case .privateOrders:                                    // 私有：{"action":"ping","data":{"ts":N}} → {"action":"pong","data":{"ts":N}}
            guard json["action"] as? String == "ping",
                  let ts = ((json["data"] as? [String: Any])?["ts"] as? NSNumber)?.int64Value else { return nil }
            return "{\"action\":\"pong\",\"data\":{\"ts\":\(ts)}}"
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { message, endpoint in reply(to: message, endpoint: endpoint) }
    }
}

// MARK: - 演練設定
extension Exchange03WebSocket {
    static let l01 = Lab(
        id: "L01",
        title: "私有頻道連上沒多久就被伺服器切斷",
        question: "第 1 題：這套交易所 API 有哪幾個 WebSocket 端點？公開和私有差在哪？",
        goal: "公開行情 /ws 與私有 /ws/v2 的 ping 和 pong 格式不一樣。每個端點都要用自己的格式回 pong，連線才不會被切斷。",
        steps: [
            "選「Bug 版」執行，看哪個端點被伺服器切斷、是第幾次 ping 沒有回。",
            "想一想：/ws/v2 送來的 ping 長什麼樣子？有 \"ping\" 這個欄位嗎？",
            "選「修正版」執行，兩個端點都要維持連線。",
            "進階：販売所的 /retail/ws 又是另一套：App 每 60 秒主動送 {\"action\":4,...}（4 代表 ping）。三套協定共用同一個連線層，各自寫解析。",
        ],
        expectBug: "/ws 連線維持；/ws/v2 的第 1、2 次 ping 都沒有回，「/ws/v2：連續 2 次沒回 pong，伺服器切斷連線」，「被伺服器切斷的連線有 1 條」。",
        expectFixed: "兩個端點都維持連線，「被伺服器切斷的連線有 0 條」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("被伺服器切斷的連線有") }) { return "演練沒有跑完：要用 scenario(log, reply:) 執行" }
            if !lines.contains("/ws：連線維持") { return "公開行情 /ws 的 ping 要能回 pong：{\"ping\":N} → {\"pong\":N}" }
            return lines.contains("/ws/v2：連續 2 次沒回 pong，伺服器切斷連線")
                ? nil : "Bug 還沒重現：只處理 {\"ping\":N} 的格式，/ws/v2 的 ping 不會回 pong"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("被伺服器切斷的連線有") }) { return "演練沒有跑完：要用 scenario(log, reply:) 執行" }
            if !lines.contains("/ws：連線維持") { return "公開行情 /ws 的 ping 要能回 pong：{\"ping\":N} → {\"pong\":N}" }
            return lines.contains("/ws/v2：連線維持")
                ? nil : "私有 /ws/v2 的 ping 是 {\"action\":\"ping\",\"data\":{\"ts\":N}}，要回 {\"action\":\"pong\",\"data\":{\"ts\":N}}（N 要一模一樣）"
        }
    )
}
