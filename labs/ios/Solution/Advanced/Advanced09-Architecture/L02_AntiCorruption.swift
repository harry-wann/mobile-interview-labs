// L02：維護中的價格顯示成 -1，還觸發了誤報的跌破提醒（舊 SDK 的怪習慣要擋在邊界，不要滲進新程式）
// 對應複習站：Advanced09Architecture 第 1 題「面對已經上線的舊專案，怎麼逐步演進，而不是整個重寫？」（anti-corruption layer）
//
// 情境：新的畫面要從舊的行情 SDK 取價格。舊 SDK 的習慣是：沒有價格（維護中）時，last 傳 -1。
//       新程式裡有三個地方會用到價格：價格標籤、下單表單的預設價格、「跌破 14000000 時通知我」的價格提醒。
//       演練各用一個正常的報價與一個維護中的報價（last 是 -1）測試。
// Bug 版：新程式直接使用舊 SDK 的型別，三個地方各自假設 last 是真的價格。維護中時，標籤顯示「-1 JPY」，
//        下單表單預設價格是 -1，「跌破」提醒還因為 -1 小於 14000000 而觸發。
// 修正版：在邊界放一層 anti-corruption layer（adapter）：把舊 SDK 的型別轉成新程式自己的 Ticker，
//        「沒有價格」轉成 nil。三個地方都只認 Ticker，每個都明確處理 nil。以後 SDK 換了，只改這一層。
import Foundation
import LabKit

/// 舊 SDK 的型別（不能改）：沒有價格時 last 是 -1
private struct LegacyTicker {
    let symbol: String
    let last: Double
}

private enum LegacySDK {
    /// 0 是正常的報價，1 是維護中
    static func fetch(_ index: Int) -> LegacyTicker {
        index == 0 ? LegacyTicker(symbol: "BTC_JPY", last: 15_000_000) : LegacyTicker(symbol: "BTC_JPY", last: -1)
    }
}

private let alertThreshold = 14_000_000.0

/// 演練流程（兩個版本共用）：fetch 取報價，其餘三個是新程式裡用到價格的地方
private func scenario<T>(
    _ log: LabLog,
    fetch: (Int) -> T,
    label: (T) -> String,
    defaultPrice: (T) -> String,
    belowAlert: (T) -> Bool
) {
    for (index, name) in ["正常", "維護中"].enumerated() {
        let ticker = fetch(index)
        let price = defaultPrice(ticker)
        log("[\(name)] 價格標籤：\(label(ticker))")
        log("[\(name)] 下單表單的預設價格：\(price.isEmpty ? "（空白，請使用者輸入）" : price)")
        log("[\(name)] 跌破 14000000 的提醒：\(belowAlert(ticker) ? "觸發" : "不觸發")")
    }
}

// MARK: - Bug 版
private enum L02Bug {
    // 新程式直接使用舊 SDK 的型別
    static func label(_ ticker: LegacyTicker) -> String { "\(Int(ticker.last)) JPY" }
    static func defaultPrice(_ ticker: LegacyTicker) -> String { "\(Int(ticker.last))" }
    static func belowAlert(_ ticker: LegacyTicker) -> Bool { ticker.last <= alertThreshold }

    static func run(_ log: LabLog) async {
        scenario(log, fetch: LegacySDK.fetch, label: label, defaultPrice: defaultPrice, belowAlert: belowAlert)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    /// 新程式自己的型別：沒有價格就是 nil
    struct Ticker {
        let pair: String
        let last: Decimal?
    }

    /// anti-corruption layer：舊 SDK 的型別只出現在這裡
    static func adapt(_ legacy: LegacyTicker) -> Ticker {
        Ticker(pair: legacy.symbol.replacingOccurrences(of: "_", with: "/"),
               last: legacy.last < 0 ? nil : Decimal(legacy.last))        // -1 代表沒有價格 → nil
    }

    static func label(_ ticker: Ticker) -> String { ticker.last.map { "\($0) JPY" } ?? "—" }
    static func defaultPrice(_ ticker: Ticker) -> String { ticker.last.map { "\($0)" } ?? "" }
    static func belowAlert(_ ticker: Ticker) -> Bool {
        guard let last = ticker.last else { return false }                // 沒有價格，不能拿來比較
        return last <= Decimal(alertThreshold)
    }

    static func run(_ log: LabLog) async {
        scenario(log, fetch: { adapt(LegacySDK.fetch($0)) }, label: label, defaultPrice: defaultPrice, belowAlert: belowAlert)
    }
}

// MARK: - 演練設定
extension Advanced09Architecture {
    static let l02 = Lab(
        id: "L02",
        title: "維護中的價格顯示 -1，還誤報跌破提醒",
        question: "第 1 題：面對已經上線的舊專案，怎麼逐步演進，而不是整個重寫？（adapter 隔離舊 SDK）",
        goal: "舊 SDK 的型別與怪習慣（-1 代表沒有價格）要擋在邊界，轉成新程式自己的型別；新程式裡不該出現舊 SDK 的型別。",
        steps: [
            "選「Bug 版」執行，看「維護中」那一組的三行：標籤、預設價格、跌破提醒。",
            "想一想：三個地方各自假設 last 是真的價格。如果 SDK 還有別的習慣（例如單位不同），要改幾個地方？",
            "選「修正版」執行：邊界的 adapt 把 -1 轉成 nil，三個地方只認自己的 Ticker。",
            "進階：Android 用 Kotlin 的 data class 加上 mapper 函式做同樣的事；這一層也是之後把舊 SDK 整個換掉時，唯一要重寫的地方。",
        ],
        expectBug: "維護中：標籤「-1 JPY」、預設價格「-1」、「跌破 14000000 的提醒：觸發」（誤報）。",
        expectFixed: "維護中：標籤「—」、預設價格「（空白，請使用者輸入）」、「跌破 14000000 的提醒：不觸發」。正常的報價兩個版本都一樣。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("[正常] 價格標籤：15000000 JPY") else { return "正常的報價應該顯示「15000000 JPY」" }
            if !lines.contains("[維護中] 價格標籤：-1 JPY") { return "Bug 還沒重現：直接使用舊 SDK 的 last，維護中的標籤會是「-1 JPY」" }
            if !lines.contains("[維護中] 跌破 14000000 的提醒：觸發") { return "Bug 還沒重現：-1 小於 14000000，跌破提醒會誤報觸發" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("[正常] 價格標籤：15000000 JPY") else { return "正常的報價應該顯示「15000000 JPY」：adapt 要把 last 轉成 Decimal" }
            if !lines.contains("[維護中] 價格標籤：—") { return "維護中的標籤應該是「—」：adapt 要把 -1 轉成 nil，label 處理 nil" }
            if !lines.contains("[維護中] 下單表單的預設價格：（空白，請使用者輸入）") { return "維護中的預設價格應該是空白：defaultPrice 處理 nil" }
            if !lines.contains("[維護中] 跌破 14000000 的提醒：不觸發") { return "維護中不該觸發提醒：belowAlert 沒有價格時回傳 false" }
            if !lines.contains("[正常] 跌破 14000000 的提醒：不觸發") { return "正常的報價（15000000）不該觸發跌破提醒" }
            return nil
        }
    )
}
