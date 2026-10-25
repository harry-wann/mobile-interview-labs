// L03：行情一多，伺服器就把連線切斷（心跳排在行情處理的後面）
// 對應複習站：Exchange03WebSocket 第 3 題「心跳 ping／pong 是什麼？為什麼要最先處理？」
//
// 情境：伺服器一次推來 20 則行情，緊接著送一則 {"ping":N}，並規定 0.5 秒內要收到 {"pong":N}，否則切斷連線。
//       每則行情要在「畫面佇列」上處理，一則花 40 毫秒（用 Thread.sleep 代表列表正在忙著畫畫面）。
//       訊息從 socket 的背景佇列進來；畫面佇列是另一條序列佇列，一次只做一件事。（演練的時限是為了縮短等待而假設的，真正的伺服器約每 5 秒送一次 ping。）
// Bug 版：每一則訊息（包括 ping）都丟到畫面佇列處理。ping 排在 20 則行情後面，等 0.8 秒才輪到，超過 0.5 秒，連線被切斷。
// 修正版：解壓後先在 socket 的背景佇列檢查心跳，是 ping 就立刻回 pong；只有行情才丟到畫面佇列。
import Foundation
import LabKit

/// 計時用的簡單鎖
private final class Stopwatch: @unchecked Sendable {
    private let lock = NSLock()
    private let start = DispatchTime.now()
    private var pingSentAt: Double?
    private var pongAt: Double?
    private var pongText: String?

    func nowMs() -> Double { Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000 }
    func markPingSent() { lock.lock(); pingSentAt = nowMs(); lock.unlock() }
    func markPong(_ text: String) { lock.lock(); if pongAt == nil { pongAt = nowMs(); pongText = text }; lock.unlock() }
    var result: (elapsedMs: Double?, text: String?) {
        lock.lock(); defer { lock.unlock() }
        guard let sent = pingSentAt, let at = pongAt else { return (nil, nil) }
        return (at - sent, pongText)
    }
}

/// 演練流程（兩個版本共用）：socket 佇列依序送進 20 則行情與 1 則 ping，等 1.3 秒，看 pong 什麼時候到
private func scenario(_ log: LabLog, makeHandler: @escaping (_ ui: DispatchQueue, _ send: @escaping (String) -> Void) -> (Data) -> Void) async {
    let clock = Stopwatch()
    let socketQueue = DispatchQueue(label: "e03.socket")
    let uiQueue = DispatchQueue(label: "e03.ui")
    let handle = makeHandler(uiQueue) { text in clock.markPong(text) }
    log("伺服器：送出 20 則行情，接著送 {\"ping\":777}，要求 0.5 秒內回 pong")
    for i in 1...20 {
        let frame = E03Wire.frame("{\"ch\":\"market.btcjpy.bbo\",\"tick\":{\"seqId\":\(i)}}")
        socketQueue.async { handle(frame) }
    }
    let pingFrame = E03Wire.frame("{\"ping\":777}")
    socketQueue.async {
        clock.markPingSent()
        handle(pingFrame)
    }
    try? await Task.sleep(nanoseconds: 1_300_000_000)
    let result = clock.result
    if let elapsed = result.elapsedMs, let text = result.text {
        log("收到 \(text)，在 ping 送出後 \(Int(elapsed)) ms")
        if elapsed > 500 || text != "{\"pong\":777}" {
            log("伺服器：沒有在 0.5 秒內收到正確的 pong，切斷連線")
        } else {
            log("伺服器：pong 準時，連線維持")
        }
    } else {
        log("伺服器：1.3 秒內沒有收到 pong，切斷連線")
    }
}

/// 處理一則行情要花的時間（代表畫面忙著更新列表）
private func renderQuote() { Thread.sleep(forTimeInterval: 0.04) }

// MARK: - Bug 版
private enum L03Bug {
    static func makeHandler(ui: DispatchQueue, send: @escaping (String) -> Void) -> (Data) -> Void {
        { frame in
            ui.async {                                          // 全部丟到畫面佇列：ping 要排在行情後面
                guard let text = E03Wire.text(of: frame), let json = e03Parse(text) else { return }
                if let ping = (json["ping"] as? NSNumber)?.int64Value {
                    send("{\"pong\":\(ping)}")
                } else {
                    renderQuote()
                }
            }
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, makeHandler: makeHandler(ui:send:))
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func makeHandler(ui: DispatchQueue, send: @escaping (String) -> Void) -> (Data) -> Void {
        { frame in
            guard let text = E03Wire.text(of: frame), let json = e03Parse(text) else { return }
            if let ping = (json["ping"] as? NSNumber)?.int64Value {     // 心跳最先處理：在背景佇列馬上回，pong 的數字原樣回傳
                send("{\"pong\":\(ping)}")
                return
            }
            ui.async { renderQuote() }                          // 只有行情才丟到畫面佇列
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, makeHandler: makeHandler(ui:send:))
    }
}

// MARK: - 演練設定
private func elapsedMs(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("收到 ") && $0.contains("在 ping 送出後") }) else { return nil }
    return line.components(separatedBy: "送出後 ").last.flatMap { Int($0.replacingOccurrences(of: " ms", with: "")) }
}

extension Exchange03WebSocket {
    static let l03 = Lab(
        id: "L03",
        title: "行情一多，伺服器就把連線切斷",
        question: "第 3 題：心跳 ping／pong 是什麼？為什麼要最先處理？",
        goal: "心跳要在背景最先處理，不能排在行情處理的後面。ping 來了就立刻原樣回 pong。",
        steps: [
            "選「Bug 版」執行（約 1.3 秒），看 pong 在 ping 送出後幾毫秒才到、伺服器有沒有切斷連線。",
            "想一想：20 則行情每則處理 40 毫秒，ping 排在它們後面要等多久？",
            "選「修正版」執行，pong 應該在幾毫秒內就回去，而且數字和 ping 一模一樣。",
            "進階：真實的做法是解壓與解碼都在 socket 的背景佇列做，心跳是不依賴任何狀態的純函式；行情處理再交給別的佇列。",
        ],
        expectBug: "「收到 {\"pong\":777}，在 ping 送出後 870 到 920 ms」（一定超過 800 ms），「伺服器：沒有在 0.5 秒內收到正確的 pong，切斷連線」。",
        expectFixed: "pong 在幾毫秒內就回去（遠小於 500 ms），「伺服器：pong 準時，連線維持」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("伺服器：送出 20 則行情") }) else { return "演練沒有跑完：要用 scenario(log, makeHandler:) 執行" }
            guard let ms = elapsedMs(lines) else { return "Bug 版也要回 pong：解壓、解析之後，收到 ping 就回 {\"pong\":N}" }
            return ms > 500 ? nil : "Bug 還沒重現：ping 要和行情一樣丟到畫面佇列（ui.async）處理，才會排在 20 則行情後面（目前 \(ms) ms 就回了）"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("伺服器：送出 20 則行情") }) else { return "演練沒有跑完：要用 scenario(log, makeHandler:) 執行" }
            guard let ms = elapsedMs(lines) else { return "沒有回 pong：解壓、解析之後，收到 ping 要馬上回 {\"pong\":N}" }
            if !lines.contains("伺服器：pong 準時，連線維持") { return "pong 太晚才回（\(ms) ms）：ping 不要丟到畫面佇列，在 socket 的背景佇列直接回；數字要和 ping 一模一樣" }
            return nil
        }
    )
}
