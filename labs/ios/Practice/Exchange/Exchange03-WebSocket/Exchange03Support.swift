// Exchange03WebSocket 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：JSON 文字轉字典、「線路上的 binary frame」（這套交易所公開 WebSocket
// 的每則訊息都是 gzip 壓縮）的壓縮與解壓。
// 這個單元的演練都不連真的伺服器：用程式裡的假伺服器，或直接準備好的訊息。
import Compression
import Foundation

/// JSON 文字轉成字典。不是 JSON 或不是物件時回傳 nil
func e03Parse(_ text: String) -> [String: Any]? {
    try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
}

/// 線路格式：每一則訊息是一個 gzip 壓縮的 binary frame
enum E03Wire {
    /// 假伺服器用：把 JSON 文字壓成 gzip 的 frame（10 位元組標頭 + 裸的
    /// DEFLATE + 8 位元組結尾）
    static func frame(_ json: String) -> Data {
        let raw = Data(json.utf8)
        let deflated =
            (try? (raw as NSData).compressed(using: .zlib) as Data) ?? Data()
        var out = Data([0x1f, 0x8b, 0x08, 0x00, 0, 0, 0, 0, 0x00, 0x03])
        out.append(deflated)
        var crc = crc32([UInt8](raw)).littleEndian
        var size = UInt32(raw.count).littleEndian
        out.append(Data(bytes: &crc, count: 4))
        out.append(Data(bytes: &size, count: 4))
        return out
    }

    /// 其他演練用：把 gzip 的 frame 解開成文字，失敗回傳 nil（演練 2
    /// 要你自己寫一份）
    static func text(of frame: Data) -> String? {
        let bytes = [UInt8](frame)
        guard bytes.count > 18, bytes[0] == 0x1f, bytes[1] == 0x8b,
            bytes[2] == 8
        else { return nil }
        let end = bytes.count - 8
        guard 10 < end,
            let raw = try? (Data(bytes[10..<end]) as NSData).decompressed(
                using: .zlib) as Data
        else { return nil }
        return String(data: raw, encoding: .utf8)
    }

    private static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var table = [UInt32](repeating: 0, count: 256)
        for i in 0..<256 {
            var c = UInt32(i)
            for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
            table[i] = c
        }
        var crc: UInt32 = 0xFFFFFFFF
        for b in bytes {
            crc = table[Int((crc ^ UInt32(b)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFFFFFF
    }
}
