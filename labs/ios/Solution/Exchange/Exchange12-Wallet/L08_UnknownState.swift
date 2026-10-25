// L08：伺服器新增了一種出金狀態，整份出金記錄就載入失敗（出金狀態用 enum 解碼，遇到不認識的值）
// 對應複習站：Exchange12Wallet 第 5 題「出金申請的 API 長什麼樣子？送出逾時時為什麼不能直接重送？」
//
// 情境：出入金記錄的 state 是字串：submitted、reexamine、pass、pre-transfer、wallet-transfer、confirmed、reject、wallet-reject、confirm-error、canceled、repealed…
//       App 要把它們對應成使用者看得懂的階段（申請中、審查中、處理中、已送出、完成、失敗、已取消）。複習站的建議：不認識的狀態，
//       顯示原始狀態碼，不要當成失敗，也不能讓整份記錄載入失敗。伺服器日後會新增狀態（演練用一個假的 "compliance-hold"），舊版 App 一定會遇到。
//       演練的 JSON 有 3 筆記錄：confirmed、compliance-hold（不認識）、wallet-transfer。parse 要把它解碼成「畫面上的每一列」。
// Bug 版：state 宣告成 enum（遵守 Decodable，每個狀態一個 case）。遇到不認識的字串，JSONDecoder 丟出 DecodingError，整份記錄解碼失敗，畫面上一筆記錄都沒有。
// 修正版：state 當成 String 讀進來，再用函式對應成階段，對應不到回傳 nil；畫面顯示原始狀態碼，其他記錄不受影響。
import Foundation
import LabKit

private let json = """
{"status":"ok","data":[
  {"id":101,"state":"confirmed"},
  {"id":102,"state":"compliance-hold"},
  {"id":103,"state":"wallet-transfer"}
]}
"""

/// 畫面上的一列：出金 id 與顯示的階段文字
private struct Row: Equatable {
    let id: Int
    let text: String
}

private struct Response<R: Decodable>: Decodable {
    let data: [R]
}

/// 已知狀態對應的階段文字；不認識回傳 nil
private func stage(for state: String) -> String? {
    switch state {
    case "submitted": return "申請中"
    case "reexamine": return "審查中"
    case "pass", "pre-transfer": return "處理中"
    case "wallet-transfer": return "已送出"
    case "confirmed": return "完成"
    case "reject", "wallet-reject", "confirm-error": return "失敗"
    case "canceled", "repealed": return "已取消"
    default: return nil
    }
}

/// 演練流程（兩個版本共用）：把 JSON 解碼成畫面上的列
private func scenario(_ log: LabLog, parse: (Data) -> [Row]?) {
    guard let rows = parse(Data(json.utf8)) else {
        log("解析失敗：整份出金記錄載入失敗")
        log("畫面上顯示 0 筆出金記錄")
        return
    }
    for row in rows { log("#\(row.id) → \(row.text)") }
    log("畫面上顯示 \(rows.count) 筆出金記錄")
}

// MARK: - Bug 版
private enum L08Bug {
    enum State: String, Decodable {                   // 每個已知狀態一個 case
        case submitted, reexamine, pass, reject
        case preTransfer = "pre-transfer"
        case walletTransfer = "wallet-transfer"
        case confirmed
    }

    struct Record: Decodable {
        let id: Int
        let state: State                                // 遇到 enum 沒有的字串，整份解碼就失敗
    }

    static func parse(_ data: Data) -> [Row]? {
        guard let response = try? JSONDecoder().decode(Response<Record>.self, from: data) else { return nil }
        return response.data.map { Row(id: $0.id, text: stage(for: $0.state.rawValue) ?? $0.state.rawValue) }
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 修正版
private enum L08Fixed {
    struct Record: Decodable {
        let id: Int
        let state: String                               // 先當字串讀進來，不在解碼時限制
    }

    static func parse(_ data: Data) -> [Row]? {
        guard let response = try? JSONDecoder().decode(Response<Record>.self, from: data) else { return nil }
        return response.data.map { Row(id: $0.id, text: stage(for: $0.state) ?? "未知狀態（\($0.state)）") }   // 不認識的：顯示原始狀態碼
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 演練設定
extension Exchange12Wallet {
    static let l08 = Lab(
        id: "L08",
        title: "伺服器新增一種狀態，整份出金記錄載入失敗",
        question: "第 5 題：出金申請的 API 長什麼樣子？送出逾時時為什麼不能直接重送？",
        goal: "API 回傳的狀態字串日後一定會新增。解碼時不要把它限制成 enum；先讀成字串，再對應成階段，對應不到就顯示原始狀態碼（不要當成失敗，也不能讓整份記錄失敗）。",
        steps: [
            "選「Bug 版」執行，看畫面上顯示幾筆記錄：只有 compliance-hold 一筆不認識，為什麼另外兩筆也不見了？",
            "想一想：舊版 App 在使用者手機上，沒辦法立刻更新；伺服器新增狀態之後，會發生什麼事？",
            "選「修正版」執行，3 筆都顯示，不認識的那一筆顯示「未知狀態（compliance-hold）」。",
            "進階：Android 用 Gson 解析 enum，遇到不認識的字串不會丟例外，而是悄悄給 null（見 Android 版）；兩個平台的行為不同，但結果同樣不能接受。",
        ],
        expectBug: "「解析失敗：整份出金記錄載入失敗」，「畫面上顯示 0 筆出金記錄」（只有一筆不認識，三筆都不見了）。",
        expectFixed: "「#101 → 完成」「#102 → 未知狀態（compliance-hold）」「#103 → 已送出」，「畫面上顯示 3 筆出金記錄」。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("畫面上顯示") }) else { return "演練沒有跑完：要用 scenario(log, parse:) 執行" }
            if !lines.contains("解析失敗：整份出金記錄載入失敗") { return "Bug 還沒重現：state 用 enum 解碼，遇到不認識的 compliance-hold，整份解碼會失敗（parse 要回傳 nil）" }
            return lines.contains("畫面上顯示 0 筆出金記錄") ? nil : "整份失敗時，畫面上應該是 0 筆"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("畫面上顯示") }) else { return "演練沒有跑完：要用 scenario(log, parse:) 執行" }
            if !lines.contains("畫面上顯示 3 筆出金記錄") { return "三筆記錄都要顯示（不認識的狀態不能讓整份失敗）" }
            if !lines.contains("#101 → 完成") || !lines.contains("#103 → 已送出") { return "已知的狀態要對應成階段：confirmed → 完成、wallet-transfer → 已送出" }
            return lines.contains("#102 → 未知狀態（compliance-hold）") ? nil : "不認識的狀態要顯示「未知狀態（原始狀態碼）」"
        }
    )
}
