// Platform05Networking 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：本機假伺服器（不碰外部網路）、HTTP 日期格式、憑證釘選用的測試憑證與公鑰雜湊計算。
import CryptoKit
import Foundation
import Security

// MARK: - 本機假伺服器

/// 只聽 127.0.0.1 的假伺服器（用 BSD socket 自己寫，不碰外部網路）。
/// 每個連線一條執行緒，handler 可以用 `Writer.pause` 慢慢寫回應，模擬慢網路或伺服器處理很久。
final class P05LocalServer: @unchecked Sendable {
    struct Request {
        let method: String
        let path: String
        /// 標頭名稱一律小寫
        let headers: [String: String]
        let body: Data
    }

    /// handler 用來寫回應。連線在 handler 結束後關閉。
    final class Writer {
        fileprivate let fd: Int32
        fileprivate init(fd: Int32) { self.fd = fd }

        /// 先寫狀態列與標頭。要慢慢送 body 時，自己在 headers 放 Content-Length。
        func head(_ status: Int, _ headers: [String: String] = [:]) {
            var text = "HTTP/1.1 \(status) \(P05LocalServer.reason(status))\r\n"
            text += "Date: \(P05HTTPDate.format(Date()))\r\nConnection: close\r\n"
            for (key, value) in headers { text += "\(key): \(value)\r\n" }
            text += "\r\n"
            write(Data(text.utf8))
        }

        func body(_ data: Data) { write(data) }
        func body(_ text: String) { write(Data(text.utf8)) }

        /// 讓這條連線睡一下（毫秒）
        func pause(milliseconds: Int) { usleep(UInt32(milliseconds) * 1000) }

        /// 一次寫完的簡單回應
        func respond(_ status: Int, _ text: String = "", headers: [String: String] = [:]) {
            var all = headers
            all["Content-Length"] = String(text.utf8.count)
            head(status, all)
            body(text)
        }

        private func write(_ data: Data) {
            data.withUnsafeBytes { raw in
                var offset = 0
                while offset < raw.count {
                    let n = send(fd, raw.baseAddress! + offset, raw.count - offset, 0)
                    if n <= 0 { return }          // 對方已經斷線（例如 App 端逾時）：不用再寫
                    offset += n
                }
            }
        }
    }

    private let handler: @Sendable (Request, Writer) -> Void
    private var listenFD: Int32 = -1
    private let lock = NSLock()
    private var stopped = false
    private(set) var port: UInt16 = 0

    init(handler: @escaping @Sendable (Request, Writer) -> Void) throws {
        self.handler = handler
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw URLError(.cannotConnectToHost) }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0                                   // 0：讓系統挑一個沒人用的埠
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0, listen(fd, 32) == 0 else {
            close(fd)
            throw URLError(.cannotConnectToHost)
        }
        var actual = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &actual) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        port = UInt16(bigEndian: actual.sin_port)
        listenFD = fd
        Thread.detachNewThread { [self] in acceptLoop() }
    }

    func url(_ path: String) -> URL { URL(string: "http://127.0.0.1:\(port)\(path)")! }

    func stop() { lock.withLock { stopped = true } }

    private var isStopped: Bool { lock.withLock { stopped } }

    private func acceptLoop() {
        while !isStopped {
            var pfd = pollfd(fd: listenFD, events: Int16(POLLIN), revents: 0)
            guard poll(&pfd, 1, 100) > 0 else { continue }
            let client = accept(listenFD, nil, nil)
            if client < 0 { continue }
            var one: Int32 = 1
            setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))   // 對方斷線時寫入不要讓程式被訊號殺掉
            Thread.detachNewThread { [self] in
                serve(client)
                close(client)
            }
        }
        close(listenFD)
    }

    private func serve(_ client: Int32) {
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        let separator = Data("\r\n\r\n".utf8)
        var headerEnd: Range<Data.Index>?
        while headerEnd == nil {
            let n = recv(client, &buffer, buffer.count, 0)
            if n <= 0 { return }
            data.append(contentsOf: buffer[0..<n])
            headerEnd = data.range(of: separator)
        }
        guard let end = headerEnd,
              let head = String(data: data[..<end.lowerBound], encoding: .utf8) else { return }
        var lines = head.components(separatedBy: "\r\n")
        let first = lines.removeFirst().split(separator: " ")
        guard first.count >= 2 else { return }
        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        var body = Data(data[end.upperBound...])
        let wanted = Int(headers["content-length"] ?? "") ?? 0
        while body.count < wanted {
            let n = recv(client, &buffer, buffer.count, 0)
            if n <= 0 { break }
            body.append(contentsOf: buffer[0..<n])
        }
        handler(Request(method: String(first[0]), path: String(first[1]), headers: headers, body: body), Writer(fd: client))
    }

    fileprivate static func reason(_ status: Int) -> String {
        switch status {
        case 200: "OK"
        case 401: "Unauthorized"
        case 404: "Not Found"
        case 429: "Too Many Requests"
        default: "Status"
        }
    }
}

/// 在任何執行緒都能用的計數器與碼表
final class P05Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    @discardableResult func hit() -> Int { lock.withLock { value += 1; return value } }
    var count: Int { lock.withLock { value } }
}

func p05ElapsedMs(since start: DispatchTime) -> Int {
    Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
}

// MARK: - HTTP 的 Date 標頭

enum P05HTTPDate {
    private static func formatter() -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        return f
    }

    static func format(_ date: Date) -> String { formatter().string(from: date) }
    static func parse(_ text: String) -> Date? { formatter().date(from: text) }
}

// MARK: - 憑證釘選用的測試憑證與公鑰雜湊

/// 四張測試用的憑證（DER 格式，用 openssl 產生，網域 api.example.com，不是真的伺服器）：
/// - a1：伺服器目前的憑證（金鑰 A）；a2：續期後的憑證，金鑰一樣是 A，但憑證本身是新的一張
/// - b：預先產生、還沒上線的備援金鑰 B 的憑證
/// - attacker：中間人拿來冒充的憑證（另一組金鑰）
enum P05Certs {
    static let a1 = Data(base64Encoded: "MIIBczCCARigAwIBAgICA+kwCgYIKoZIzj0EAwIwGjEYMBYGA1UEAwwPYXBpLmV4YW1wbGUuY29tMB4XDTI2MTAyNDEwMTMwMFoXDTI3MDEyMjEwMTMwMFowGjEYMBYGA1UEAwwPYXBpLmV4YW1wbGUuY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpiRKiEblG42qHIPhXHbRCHOb+zm9cwhF4wrs1bahfO2eP50GUFxvxUabDmHjXgKL/l9ulHtdhSS9C11FolTli6NOMEwwHQYDVR0OBBYEFFxRVLj8AkIcRkH0pKQLg4BnmO/EMA8GA1UdEwEB/wQFMAMBAf8wGgYDVR0RBBMwEYIPYXBpLmV4YW1wbGUuY29tMAoGCCqGSM49BAMCA0kAMEYCIQCSQX3v1ynikFCRnUzjR5wldPYSxNk+Pod+VU+KWXf4mAIhAKuEWj4tSFG4gxV5XrX1+GK8qTRXJgnJDywYDxTCvnMk")!
    static let a2 = Data(base64Encoded: "MIIBcjCCARigAwIBAgICA+owCgYIKoZIzj0EAwIwGjEYMBYGA1UEAwwPYXBpLmV4YW1wbGUuY29tMB4XDTI2MTAyNDEwMTMwMVoXDTI3MDEyMjEwMTMwMVowGjEYMBYGA1UEAwwPYXBpLmV4YW1wbGUuY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpiRKiEblG42qHIPhXHbRCHOb+zm9cwhF4wrs1bahfO2eP50GUFxvxUabDmHjXgKL/l9ulHtdhSS9C11FolTli6NOMEwwHQYDVR0OBBYEFFxRVLj8AkIcRkH0pKQLg4BnmO/EMA8GA1UdEwEB/wQFMAMBAf8wGgYDVR0RBBMwEYIPYXBpLmV4YW1wbGUuY29tMAoGCCqGSM49BAMCA0gAMEUCIHwis8CCrBNrR7nIrgKaXcBlzOxl1kqu27ac6txM5r2+AiEAhttvJg30Msr2hOmjlaf/6AnEcOd8JoUujhn6hXegtXI=")!
    static let b = Data(base64Encoded: "MIIBcTCCARigAwIBAgICB9EwCgYIKoZIzj0EAwIwGjEYMBYGA1UEAwwPYXBpLmV4YW1wbGUuY29tMB4XDTI2MTAyNDEwMTMwMVoXDTI3MDEyMjEwMTMwMVowGjEYMBYGA1UEAwwPYXBpLmV4YW1wbGUuY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWHHc2oaXKNWb/2ZR6aPe46A3qCN4qBzoYYfYlsSeVd09yEUt/hCBvkylBi8t1OHuU9rZJen5b3md09wOGqXQDKNOMEwwHQYDVR0OBBYEFHGlVlzrJRSwB3sl898b6g0XyxMGMA8GA1UdEwEB/wQFMAMBAf8wGgYDVR0RBBMwEYIPYXBpLmV4YW1wbGUuY29tMAoGCCqGSM49BAMCA0cAMEQCIGmO7P4bN2LGseQtQJjO1XDvxtZRJkgU/PbsIgbqAiUtAiAmPeXmiM1P37FEFsKtmBwmU23rCZdNxuiZibMBm1OMBw==")!
    static let attacker = Data(base64Encoded: "MIIBcTCCARigAwIBAgICC7kwCgYIKoZIzj0EAwIwGjEYMBYGA1UEAwwPYXBpLmV4YW1wbGUuY29tMB4XDTI2MTAyNDEwMTMwMVoXDTI3MDEyMjEwMTMwMVowGjEYMBYGA1UEAwwPYXBpLmV4YW1wbGUuY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEuKVGuzXT7c/2IzOMxhEiRmjio+NMkMZCZmKQzuQxQHgBeBE25aV6p7NCb/n+rVP9gbuR3gGKU+yX4ucP/2Nw9KNOMEwwHQYDVR0OBBYEFP6Ccd1UYf60PHJzZAblfpJjZBujMA8GA1UdEwEB/wQFMAMBAf8wGgYDVR0RBBMwEYIPYXBpLmV4YW1wbGUuY29tMAoGCCqGSM49BAMCA0cAMEQCIEIxLK+/piIXaaNJlKE3O4T5R3H9U9RBSQmbAv3WYRzrAiBE77MbIWukNW7rhKAffjPPTHZHJ9y/1pBm2OXJKngC0w==")!
}

enum P05Pinning {
    /// 憑證裡的公鑰，轉成 SPKI（Subject Public Key Info，公鑰加上演算法標頭）的位元組。
    /// 這就是 `openssl x509 -pubkey | openssl pkey -pubin -outform der` 輸出的內容。
    static func spki(of certificate: Data) -> Data {
        guard let cert = SecCertificateCreateWithData(nil, certificate as CFData),
              let key = SecCertificateCopyKey(cert),
              let raw = SecKeyCopyExternalRepresentation(key, nil) as Data? else { return Data() }
        // 系統只給原始的公鑰點（EC P-256 共 65 位元組），要補上固定的 ASN.1 標頭才是 SPKI
        let ecP256Header: [UInt8] = [
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
        ]
        return Data(ecP256Header) + raw
    }

    static func sha256Base64(_ data: Data) -> String {
        Data(SHA256.hash(data: data)).base64EncodedString()
    }

    /// 伺服器送來的憑證鏈裡，只要有一張算出來的 pin 在內建清單裡就通過
    static func isTrusted(chain: [Data], pins: [String], pinOf: (Data) -> String) -> Bool {
        chain.contains { pins.contains(pinOf($0)) }
    }
}

// MARK: - 固定種子的亂數

/// 固定種子的亂數產生器（SplitMix64）：同樣的種子每次產生同樣的數字，演練結果才穩定
struct P05SeededRandom: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
