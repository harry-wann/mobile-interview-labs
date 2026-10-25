// Testing06CI 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   L01 CI 檢查結果 T06Check；L02 測試檔與耗時 T06TestFile；
//   L03 快取 T06Cache、雜湊 T06Hash.shortHash、套件鎖定檔 T06Lockfile；
//   L04 分階段發布的統計 T06Stats 與決定 T06Decision。
import CryptoKit
import Foundation
import LabKit

// L01：CI 檢查結果

/// 一個 CI job 的結果。cancelled 是被取消（例如逾時），skipped 是被略過（例如條件不符、或前一個 job 失敗）
enum T06JobResult: String {
    case success, failure, cancelled, skipped
}

struct T06Check {
    let name: String
    let result: T06JobResult
    /// 是不是合併 PR 必須通過的檢查（branch protection 的 required checks）
    let required: Bool
}

// L02：測試檔與耗時

/// 一個測試檔，seconds 是上一次 CI 量到的執行時間
struct T06TestFile {
    let name: String
    let seconds: Int

    /// 12 個測試檔，總共 1160 秒；分 3 份同時跑，最理想每份約 387 秒
    static let all: [T06TestFile] = [
        T06TestFile(name: "OrderBookTests", seconds: 240),
        T06TestFile(name: "ChartTests", seconds: 200),
        T06TestFile(name: "AuthTests", seconds: 180),
        T06TestFile(name: "WebSocketTests", seconds: 150),
        T06TestFile(name: "OrderFlowTests", seconds: 120),
        T06TestFile(name: "AssetTests", seconds: 90),
        T06TestFile(name: "KYCTests", seconds: 60),
        T06TestFile(name: "SettingsTests", seconds: 40),
        T06TestFile(name: "FormatterTests", seconds: 30),
        T06TestFile(name: "PrecisionTests", seconds: 20),
        T06TestFile(name: "ParserTests", seconds: 20),
        T06TestFile(name: "UtilTests", seconds: 10),
    ]
}

// L03：快取

enum T06Hash {
    /// 內容的 SHA-256，取前 12 個十六進位字元。內容只要差一個字，結果就不同
    static func shortHash(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined().prefix(12).description
    }
}

/// CI 的快取：用 key 存、用 key 取
final class T06Cache {
    private var store: [String: String] = [:]

    func restore(_ key: String) -> String? { store[key] }
    func save(_ key: String, artifact: String) { store[key] = artifact }
}

enum T06Lockfile {
    /// 套件鎖定檔（相當於 Package.resolved 或 gradle.lockfile）的內容，這裡簡化成「套件@版本」
    static func content(version: String) -> String { "Alamofire@\(version)" }

    /// 從鎖定檔讀出版本
    static func version(of lockfile: String) -> String { String(lockfile.split(separator: "@").last ?? "") }
}

// L04：分階段發布

struct T06Stats {
    let sessions: Int
    let crashFreeSessions: Int

    /// crash-free session 比例（百分比）
    var crashFreeRate: Double { Double(crashFreeSessions) / Double(sessions) * 100 }
}

enum T06Decision: String {
    case expand = "擴大發布"
    case hold = "再等一等"
    case halt = "暫停發布"
}
