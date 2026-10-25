// L02：連上之後每一則訊息都解析失敗（WebSocket 的訊息是 gzip 壓縮的 binary frame）
// 對應複習站：Exchange03WebSocket 第 2 題「為什麼每則訊息都要先解壓縮？iOS 和 Android 怎麼解 gzip？」
//
// 情境：這套交易所公開 WebSocket 的每一則訊息都是 gzip 壓縮過的 binary frame，不是文字。假伺服器送來三則：
//       一則 ping、一則最佳買賣價（BBO）的推送、一則訂閱確認。App 要先把 frame 解成文字，才能解析 JSON。
//       frame 是用 gzip 格式壓縮的：10 位元組的標頭、中間是裸的 DEFLATE 資料、結尾 8 位元組（檢查碼與原始長度）。
// Bug 版：把 frame 當成 UTF-8 文字：String(decoding:as:)。每一則都是亂碼，JSON 解析全部失敗。
// 修正版：先檢查 gzip 的識別碼（0x1f 0x8b），切掉標頭的 10 位元組與結尾的 8 位元組，把中間那段交給系統的 .zlib 解壓，再轉成文字。
//        （Apple 的 .zlib 只認裸的 DEFLATE，直接把整份 gzip 交給它會解壓失敗。）
import Foundation
import LabKit

private let messages = [
    #"{"ping":18212558000}"#,
    #"{"ch":"market.btcjpy.bbo","ts":1630994555540,"tick":{"seqId":137005210233,"ask":10001000,"askSize":1.502181,"bid":10000000,"bidSize":0.178567,"symbol":"btcjpy"}}"#,
    #"{"id":"id1","status":"ok","subbed":"market.btcjpy.bbo","ts":1489474081631}"#,
]

/// 演練流程（兩個版本共用）：假伺服器依序送三個 frame，App 解成文字再解析 JSON
private func scenario(_ log: LabLog, decode: (Data) -> String) {
    var parsed = 0
    for (index, message) in messages.enumerated() {
        let frame = E03Wire.frame(message)
        let text = decode(frame)
        if e03Parse(text) != nil {
            parsed += 1
            log("第 \(index + 1) 則（\(frame.count) 位元組）：解析成功 \(text.prefix(40))…")
        } else {
            log("第 \(index + 1) 則（\(frame.count) 位元組）：解析失敗（轉成的文字長度 \(text.count)）")
        }
    }
    log("共 \(messages.count) 則，成功解析 \(parsed) 則")
}

// MARK: - Bug 版
private enum L02Bug {
    static func decode(_ frame: Data) -> String {
        String(decoding: frame, as: UTF8.self)                  // 把 binary frame 當成文字
    }

    static func run(_ log: LabLog) async {
        scenario(log, decode: decode(_:))
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func decode(_ frame: Data) -> String {
        let bytes = [UInt8](frame)
        guard bytes.count > 18, bytes[0] == 0x1f, bytes[1] == 0x8b else { return "" }       // 不是 gzip
        let deflated = Data(bytes[10..<(bytes.count - 8)]) as NSData                         // 切掉 10 位元組標頭與 8 位元組結尾
        guard let raw = try? deflated.decompressed(using: .zlib) as Data else { return "" }  // 中間是裸的 DEFLATE
        return String(data: raw, encoding: .utf8) ?? ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, decode: decode(_:))
    }
}

// MARK: - 演練設定
private func parsedCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 3 則") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange03WebSocket {
    static let l02 = Lab(
        id: "L02",
        title: "連上之後每一則訊息都解析失敗",
        question: "第 2 題：為什麼每則訊息都要先解壓縮？iOS 和 Android 怎麼解 gzip？",
        goal: "WebSocket 收到的是 gzip 壓縮的 binary frame。先解壓成文字，才能解析 JSON。",
        steps: [
            "選「Bug 版」執行，看三則訊息的解析結果、轉出來的文字是什麼。",
            "想一想：frame 的前兩個位元組是 0x1f 0x8b，它是文字嗎？",
            "選「修正版」執行，三則都要解析成功。",
            "進階：把整份 gzip 直接交給 decompressed(using: .zlib) 試試看，會得到什麼錯誤？為什麼要先切掉標頭和結尾？解壓請在 socket 的背景佇列做，失敗的訊息丟掉就好，不要讓連線崩潰。",
        ],
        expectBug: "三則都是「解析失敗（轉成的文字長度 40 左右）」，「共 3 則，成功解析 0 則」。",
        expectFixed: "「共 3 則，成功解析 3 則」，第一則解出 {\"ping\":18212558000}。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = parsedCount(lines) else { return "演練沒有跑完：要用 scenario(log, decode:) 執行" }
            if !lines.contains(where: { $0.contains("解析失敗（轉成的文字長度") && !$0.hasSuffix("長度 0）") }) {
                return "Bug 還沒重現：decode 要把 frame 直接當成 UTF-8 文字（String(decoding: frame, as: UTF8.self)），轉出來是亂碼，不是空字串"
            }
            return n == 0 ? nil : "Bug 還沒重現：沒解壓就當文字，JSON 應該全部解析失敗（目前成功 \(n) 則）"
        },
        checkFixed: { lines in
            guard let n = parsedCount(lines) else { return "演練沒有跑完：要用 scenario(log, decode:) 執行" }
            return n == 3 ? nil : "還有 \(3 - n) 則解析失敗：檢查 0x1f 0x8b，切掉前 10 個、後 8 個位元組，中間交給 decompressed(using: .zlib)"
        }
    )
}
