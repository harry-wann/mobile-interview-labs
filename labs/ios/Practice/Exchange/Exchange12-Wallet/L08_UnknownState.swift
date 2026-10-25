// L08：伺服器新增了一種出金狀態，整份出金記錄就載入失敗（出金狀態用 enum 解碼，遇到不認識的值）
// 對應複習站：Exchange12Wallet 第 5 題「出金申請的 API 長什麼樣子？送出逾時時為什麼不能直接重送？」
//
// 情境：出入金記錄的 state 是字串：submitted、reexamine、pass、pre-transfer、wallet-transfer、confirmed、reject、wallet-reject、confirm-error、canceled、repealed…
//       App 要把它們對應成使用者看得懂的階段（申請中、審查中、處理中、已送出、完成、失敗、已取消）。複習站的建議：不認識的狀態，
//       顯示原始狀態碼，不要當成失敗，也不能讓整份記錄載入失敗。伺服器日後會新增狀態（演練用一個假的 "compliance-hold"），舊版 App 一定會遇到。
//       演練的 JSON 有 3 筆記錄：confirmed、compliance-hold（不認識）、wallet-transfer。parse 要把它解碼成「畫面上的每一列」。
//
// 你要寫：
//   ① Bug 版（L08Bug.parse）：宣告一個遵守 Decodable、rawValue 是 String 的 enum State（只列 7 個已知狀態：submitted、reexamine、pass、preTransfer = "pre-transfer"、
//      walletTransfer = "wallet-transfer"、confirmed、reject），Record 的 state 用這個 enum；用 JSONDecoder 解碼，成功就把每筆轉成畫面的一列（rows），
//      失敗（try? 得到 nil）回傳 nil。（宣告 enum、Record 都放在 L08Bug 裡面。）
//   ② 修正版（L08Fixed.parse）：Record 的 state 用 String；用 stage(for:) 對應成階段文字，對應不到就顯示「未知狀態（原始狀態碼）」。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange12-Wallet/L08_UnknownState.swift
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
    // TODO ①：在這裡宣告 enum State（遵守 Decodable、rawValue 是 String，已知的 7 個狀態：submitted、reexamine、pass、preTransfer = "pre-transfer"、
    //        walletTransfer = "wallet-transfer"、confirmed、reject）與 struct Record: Decodable（id: Int、state: State）。
    //        parse 用 try? JSONDecoder().decode(Response<Record>.self, from: data) 解碼，失敗回傳 nil；
    //        成功就把每筆轉成 Row(id: …, text: stage(for: 狀態字串) ?? 狀態字串)
    static func parse(_ data: Data) -> [Row]? {
        return []
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 修正版
private enum L08Fixed {
    // TODO ②：在這裡宣告 struct Record: Decodable（id: Int、state 用 String，先當字串讀進來）。
    //        parse 用 try? JSONDecoder().decode(Response<Record>.self, from: data) 解碼，失敗回傳 nil；
    //        成功就把每筆轉成 Row(id: …, text: stage(for: $0.state) ?? "未知狀態（\($0.state)）")
    static func parse(_ data: Data) -> [Row]? {
        return []
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
            "打開 Practice/Exchange/Exchange12-Wallet/L08_UnknownState.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看畫面上顯示幾筆記錄：只有 compliance-hold 一筆不認識，為什麼另外兩筆也不見了？",
            "想一想：舊版 App 在使用者手機上，沒辦法立刻更新；伺服器新增狀態之後，會發生什麼事？",
            "寫 TODO ②（修正版），選「修正版」執行，3 筆都顯示，不認識的那一筆顯示「未知狀態（compliance-hold）」。",
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
