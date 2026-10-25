// L07：手機時間調快 10 分鐘，所有下單請求都被伺服器拒絕（用伺服器的 Date 標頭校正時間）
// 對應複習站：Platform05Networking 第 6 題「簽名請求遇到手機時間不準怎麼辦？」
//
// 情境：下單 API 要求每個請求帶 X-Timestamp 和簽章（HMAC：用雙方共有的密鑰對「時間戳記＋路徑」算出來的簽章）。
//       伺服器只接受時間差在 30 秒內的請求，避免被攔截的請求日後被拿來重送（replay attack）。
//       使用者手動把手機時間調快了 10 分鐘。（本機假伺服器；手機時鐘用一個函式代表，方便演練調快。）
//
// 你要寫：
//   ① Bug 版：timestamp() 直接用手機的時鐘 deviceNow()。
//   ② 修正版：observe 讀回應的 Date 標頭（P05HTTPDate.parse），算出 offset = 伺服器時間 − deviceNow() 存起來；timestamp() 用 deviceNow() 加上 offset。
// 只改有 TODO 的地方。解答：Solution/Platform/Platform05-Networking/L07_ClockSkew.swift
import CryptoKit
import Foundation
import LabKit

private let secret = SymmetricKey(data: Data("demo-secret".utf8))

private func sign(timestamp: Int, path: String) -> String {
    let mac = HMAC<SHA256>.authenticationCode(for: Data("\(timestamp)POST\(path)".utf8), using: secret)
    return Data(mac).map { String(format: "%02x", $0) }.joined()
}

/// 演練流程（兩個版本共用）：先打一支不需要簽名的 /ticker，再送簽名的下單請求
private func scenario(_ log: LabLog, timestamp: () -> Int, observe: (HTTPURLResponse) -> Void) async {
    guard let server = try? P05LocalServer(handler: { request, writer in
        if request.path == "/ticker" {
            writer.respond(200, "15000000")                     // 公開行情：不用簽名；回應的 Date 標頭是伺服器的時間
            return
        }
        let ts = Int(request.headers["x-timestamp"] ?? "") ?? 0
        let drift = abs(Date().timeIntervalSince1970 - Double(ts))
        if drift > 30 {
            writer.respond(401, "timestamp out of range")       // 時間差超過 30 秒：拒絕
        } else if request.headers["x-signature"] != sign(timestamp: ts, path: request.path) {
            writer.respond(401, "bad signature")
        } else {
            writer.respond(200, "ok")
        }
    }) else {
        log("本機伺服器啟動失敗")
        return
    }
    defer { server.stop() }
    let session = URLSession(configuration: .ephemeral)
    defer { session.invalidateAndCancel() }

    if let (_, response) = try? await session.data(from: server.url("/ticker")), let http = response as? HTTPURLResponse {
        observe(http)                                           // 每次收到回應，都有機會用 Date 標頭校正時間
    }

    let ts = timestamp()
    let diff = ts - Int(Date().timeIntervalSince1970)
    log("簽名用的時間比伺服器的時間快 \(diff) 秒")
    var request = URLRequest(url: server.url("/orders"))
    request.httpMethod = "POST"
    request.setValue(String(ts), forHTTPHeaderField: "X-Timestamp")
    request.setValue(sign(timestamp: ts, path: "/orders"), forHTTPHeaderField: "X-Signature")
    let status = ((try? await session.data(for: request))?.1 as? HTTPURLResponse)?.statusCode ?? -1
    log("簽名的下單請求：HTTP \(status)")
}

/// 手機的時鐘：比真實時間快 10 分鐘（使用者手動調的）
private func deviceNow() -> Date { Date().addingTimeInterval(600) }

// MARK: - Bug 版
private enum L07Bug {
    final class Clock: @unchecked Sendable {
        func timestamp() -> Int {
            // TODO ①：直接用手機的時鐘：Int(deviceNow().timeIntervalSince1970)
            0
        }
        func observe(_ response: HTTPURLResponse) {}
    }

    static func run(_ log: LabLog) async {
        let clock = Clock()
        await scenario(log, timestamp: clock.timestamp, observe: clock.observe)
    }
}

// MARK: - 修正版
private enum L07Fixed {
    final class Clock: @unchecked Sendable {
        private var offset: TimeInterval = 0                // 伺服器時間 − 手機時間

        func timestamp() -> Int {
            // TODO ②-1：用 deviceNow() 加上 offset（addingTimeInterval），轉成 timeIntervalSince1970 的整數
            0
        }

        func observe(_ response: HTTPURLResponse) {
            // TODO ②-2：讀 response.value(forHTTPHeaderField: "Date")，用 P05HTTPDate.parse 轉成 Date，
            //          offset = 伺服器時間 - 手機現在的時間（server.timeIntervalSince(deviceNow())）
            _ = offset
        }
    }

    static func run(_ log: LabLog) async {
        let clock = Clock()
        await scenario(log, timestamp: clock.timestamp, observe: clock.observe)
    }
}

// MARK: - 演練設定
private func skew(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("簽名用的時間比伺服器的時間快 ") }) else { return nil }
    return Int(line.replacingOccurrences(of: "簽名用的時間比伺服器的時間快 ", with: "").replacingOccurrences(of: " 秒", with: ""))
}

extension Platform05Networking {
    static let l07 = Lab(
        id: "L07",
        title: "手機時間調快 10 分鐘，所有下單請求被拒絕",
        question: "第 6 題：簽名請求遇到手機時間不準怎麼辦？",
        goal: "手機時間不準時，簽名請求仍然能被伺服器接受。簽名用的時間要從哪裡來？",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看簽名用的時間和伺服器差幾秒、下單請求的 HTTP 狀態碼，出現 ✓。",
            "想一想：伺服器的 /ticker 回應裡，有哪個標頭帶著伺服器現在的時間？",
            "寫 TODO ②，選「修正版」執行，看時間差縮到幾秒、下單請求是不是 HTTP 200。",
            "進階：offset 要用「伺服器時間 − 手機現在的時間」算，而且不要存手機的絕對時間，使用者再改一次時鐘才不會又錯。",
        ],
        expectBug: "「簽名用的時間比伺服器的時間快 600 秒」「簽名的下單請求：HTTP 401」。",
        expectFixed: "「簽名用的時間比伺服器的時間快 0 秒」（差 1 秒以內），「簽名的下單請求：HTTP 200」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let diff = skew(lines) else { return "演練沒有跑完：要用 scenario(log, timestamp:, observe:) 執行" }
            if abs(diff - 600) > 5 { return "Bug 版簽名要直接用手機的時鐘 deviceNow()（目前和伺服器差 \(diff) 秒，應該約 600）" }
            return lines.contains("簽名的下單請求：HTTP 401") ? nil : "時間差 600 秒，伺服器應該回 HTTP 401"
        },
        checkFixed: { lines in
            guard let diff = skew(lines) else { return "演練沒有跑完：要用 scenario(log, timestamp:, observe:) 執行" }
            if abs(diff) > 2 { return "簽名用的時間還差 \(diff) 秒：observe 要讀 Date 標頭算 offset，timestamp 要加上 offset" }
            return lines.contains("簽名的下單請求：HTTP 200") ? nil : "時間已經校正，下單請求應該是 HTTP 200"
        }
    )
}
