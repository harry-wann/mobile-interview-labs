// L04：K 線頁一直轉圈（req 的回覆和錯誤訊息都被丟掉了）
// 對應複習站：Exchange03WebSocket 第 4 題「sub、unsub、req
// 差在哪？訊息和錯誤長什麼樣子？」
//
// 情境：打開 BTC/JPY 的 K 線頁，App 送出三個請求：req 歷史 K 線（id r1）、sub
// 即時 K 線（id s1）、sub 一個文件裡沒有的 market.btcjpy.kline.3min（id s2）。
//       畫面上每個「還沒收到結果的請求」都會轉圈。伺服器依序回來：
//       s1 的確認（subbed）、r1 的回覆（rep，不是 ch）、s2 的錯誤（status 是
// error）、一則即時 K 線的推送（ch）、一則 ping。
// Bug 版：分派只認識「有 ch 欄位的推送」和 ping，其他全部忽略。req 的回覆、
// 訂閱的確認、錯誤訊息都被丟掉，三個請求永遠收不到結果，畫面一直轉圈。
// 修正版：照固定的順序判斷：心跳 → 錯誤（用 id 對回請求）→
// 確認（subbed／unsubbed／rep，用 id 對回請求）→ 推送 → 忽略。
import Foundation
import LabKit

private enum Incoming: Equatable {
    case heartbeat(Int64)
    case failure(id: String, message: String)  // status 是 error
    case ack(id: String)  // subbed／unsubbed
    case reply(id: String, topic: String)  // req 的回覆
    case push(topic: String)  // 持續推送
    case ignored
}

private let serverMessages = [
    #"{"id":"s1","status":"ok","subbed":"market.btcjpy.kline.1min","ts":1630994555000}"#,
    #"{"id":"r1","status":"ok","rep":"market.btcjpy.kline.1min","tick":[{"id":1630994400,"close":9990000},{"id":1630994460,"close":10000000}]}"#,
    #"{"id":"s2","status":"error","err-code":"bad-request","err-msg":"invalid topic market.btcjpy.kline.3min","ts":1630994555100}"#,
    #"{"ch":"market.btcjpy.kline.1min","ts":1630994555200,"tick":{"id":1630994520,"close":10010000}}"#,
    #"{"ping":18212558000}"#,
]

/// 演練流程（兩個版本共用）：送出三個請求，依序處理伺服器的回覆，
/// 看有哪些請求一直沒有結果
private func scenario(_ log: LabLog, classify: ([String: Any]) -> Incoming) {
    var pending: Set<String> = ["r1", "s1", "s2"]
    var candles = 0
    for text in serverMessages {
        guard let json = e03Parse(text) else { continue }
        switch classify(json) {
        case .heartbeat(let value): log("心跳 ping \(value)，回 pong")
        case .failure(let id, let message):
            pending.remove(id)
            log("請求 \(id) 失敗：\(message)，畫面顯示錯誤")
        case .ack(let id):
            pending.remove(id)
            log("請求 \(id) 訂閱成功")
        case .reply(let id, let topic):
            pending.remove(id)
            log("請求 \(id) 的回覆：\(topic) 的歷史 K 線")
        case .push(let topic):
            candles += 1
            log("推送：\(topic)")
        case .ignored: log("忽略一則訊息")
        }
    }
    log(
        "還在轉圈、沒有收到結果的請求有 \(pending.count) 個\(pending.isEmpty ? "" : "（\(pending.sorted().joined(separator: "、"))）")"
    )
}

// MARK: - Bug 版
private enum L04Bug {
    static func classify(_ json: [String: Any]) -> Incoming {
        if let ping = (json["ping"] as? NSNumber)?.int64Value {
            return .heartbeat(ping)
        }
        // 只認得有 ch 的推送
        if let ch = json["ch"] as? String { return .push(topic: ch) }
        return .ignored
    }

    static func run(_ log: LabLog) async {
        scenario(log, classify: classify(_:))
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func classify(_ json: [String: Any]) -> Incoming {
        // 1. 心跳
        if let ping = (json["ping"] as? NSNumber)?.int64Value {
            return .heartbeat(ping)
        }
        // 2. 錯誤：用 id 對回請求
        if json["status"] as? String == "error", let id = json["id"] as? String
        {
            return .failure(id: id, message: json["err-msg"] as? String ?? "")
        }
        // 3. 確認
        if json["subbed"] != nil || json["unsubbed"] != nil,
            let id = json["id"] as? String
        {
            return .ack(id: id)
        }
        // 3. req 的回覆：欄位是 rep，不是 ch
        if let topic = json["rep"] as? String, let id = json["id"] as? String {
            return .reply(id: id, topic: topic)
        }
        // 4. 推送：tick 或 data 都要接受
        if let ch = json["ch"] as? String,
            json["tick"] != nil || json["data"] != nil
        {
            return .push(topic: ch)
        }
        // 5. 其他一律忽略
        return .ignored
    }

    static func run(_ log: LabLog) async {
        scenario(log, classify: classify(_:))
    }
}

// MARK: - 演練設定
extension Exchange03WebSocket {
    static let l04 = Lab(
        id: "L04",
        title: "K 線頁一直轉圈，回覆被丟掉了",
        question: "第 4 題：sub、unsub、req 差在哪？訊息和錯誤長什麼樣子？",
        goal:
            "每個請求都帶一個 id，回覆（確認、req 的 rep、錯誤）要用 id 對回請求。分派的順序是：心跳、錯誤、確認、推送、忽略。",
        steps: [
            "選「Bug 版」執行，看哪些請求一直沒有收到結果、哪些訊息被「忽略」。",
            "想一想：req 的回覆用 rep 還是 ch？訂閱不存在的 topic，伺服器回什麼？",
            "選「修正版」執行，三個請求都要有結果：成功、成功，和一個失敗。",
            "進階：推送的資料有時放 tick、有時放 data，解析時兩個欄位都要接受；判斷「是不是推送」要放在最後。",
        ],
        expectBug: "r1、s1、s2 都沒有結果：「還在轉圈、沒有收到結果的請求有 3 個（r1、s1、s2）」，只看到推送與心跳。",
        expectFixed: "s1 訂閱成功、r1 收到歷史 K 線、s2 失敗並顯示錯誤，「還在轉圈、沒有收到結果的請求有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("還在轉圈") }) {
                return "演練沒有跑完：要用 scenario(log, classify:) 執行"
            }
            if !lines.contains("推送：market.btcjpy.kline.1min") {
                return "Bug 版也要認得推送：有 ch 欄位就是推送"
            }
            return lines.contains("還在轉圈、沒有收到結果的請求有 3 個（r1、s1、s2）")
                ? nil : "Bug 還沒重現：只認得有 ch 欄位的推送和 ping，req 的回覆、確認、錯誤都要被忽略"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("還在轉圈") }) {
                return "演練沒有跑完：要用 scenario(log, classify:) 執行"
            }
            if !lines.contains(
                "請求 s2 失敗：invalid topic market.btcjpy.kline.3min，畫面顯示錯誤")
            {
                return
                    "status 是 error 要回傳 .failure(id:message:)，message 取 err-msg"
            }
            if !lines.contains("請求 r1 的回覆：market.btcjpy.kline.1min 的歷史 K 線") {
                return "req 的回覆用 rep 欄位（不是 ch），要回傳 .reply(id:topic:)"
            }
            if !lines.contains("請求 s1 訂閱成功") {
                return "有 subbed 或 unsubbed 欄位的是確認，要回傳 .ack(id:)"
            }
            if !lines.contains("推送：market.btcjpy.kline.1min") {
                return "有 ch 而且有 tick 或 data 的是推送"
            }
            return lines.contains("還在轉圈、沒有收到結果的請求有 0 個") ? nil : "還有請求沒有收到結果"
        }
    )
}
