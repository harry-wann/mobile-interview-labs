// L03：「漲幅榜」第一名顯示成成交量最大的（複製貼上漏改欄位，改用 KeyPath）
// 對應複習站：Swift10LanguageFeatures 第 4 題「KeyPath 是什麼？什麼時候用？」
//
// 情境：行情頁有「成交量榜」「漲幅榜」「價格榜」三個榜單，每個榜單都是「找出某個欄位最大的那一檔」。
//
// 你要寫：
//   ① Bug 版：topByChange 複製貼上 topByVolume，漏改欄位，仍然用 volume 比較。
//   ② 修正版：寫一個通用的 top(_:by:)，用 KeyPath 把「哪個欄位」當參數傳進來。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift10-LanguageFeatures/L03_KeyPath.swift
import Foundation
import LabKit

/// 一檔行情（兩個版本共用）
private struct Ticker {
    let symbol: String
    let price: Int
    let volume: Int
    let changePercent: Double
}

private let tickers = [
    Ticker(symbol: "BTC", price: 15_000_000, volume: 90, changePercent: 1.2),
    Ticker(symbol: "ETH", price: 400_000, volume: 50, changePercent: 3.4),
    Ticker(symbol: "XRP", price: 80, volume: 20, changePercent: 8.9),
]

// MARK: - Bug 版
private enum L03Bug {
    static func topByVolume(_ list: [Ticker]) -> Ticker? { list.max { $0.volume < $1.volume } }
    // TODO ①：漲幅榜：複製 topByVolume 的寫法，但是「忘了」把欄位從 volume 改成 changePercent（很多人就是這樣出錯的）
    static func topByChange(_ list: [Ticker]) -> Ticker? { nil }
    static func topByPrice(_ list: [Ticker]) -> Ticker? { list.max { $0.price < $1.price } }

    static func run(_ log: LabLog) async {
        log("成交量榜第一名：\(topByVolume(tickers)?.symbol ?? "（沒有）")")
        log("漲幅榜第一名：\(topByChange(tickers)?.symbol ?? "（沒有）")")
        log("價格榜第一名：\(topByPrice(tickers)?.symbol ?? "（沒有）")")
    }
}

// MARK: - 修正版
private enum L03Fixed {
    /// 通用：不管哪個欄位，只要可以比較，就找出最大的那一檔
    static func top<V: Comparable>(_ list: [Ticker], by key: KeyPath<Ticker, V>) -> Ticker? {
        // TODO ②：用 list.max 找出 key 這個欄位最大的那一檔：比較兩個元素時，用 [keyPath: key] 取出欄位的值
        nil
    }

    static func run(_ log: LabLog) async {
        log("成交量榜第一名：\(top(tickers, by: \.volume)?.symbol ?? "（沒有）")")
        log("漲幅榜第一名：\(top(tickers, by: \.changePercent)?.symbol ?? "（沒有）")")
        log("價格榜第一名：\(top(tickers, by: \.price)?.symbol ?? "（沒有）")")
    }
}

// MARK: - 演練設定
extension Swift10LanguageFeatures {
    static let l03 = Lab(
        id: "L03",
        title: "漲幅榜第一名，顯示成成交量最大的",
        question: "第 4 題：KeyPath 是什麼？什麼時候用？",
        goal: "三個榜單都是「找出某個欄位最大的那一檔」。先寫出複製貼上漏改欄位的 Bug 版，再用 KeyPath 把「哪個欄位」當參數傳進去。",
        steps: [
            "打開 Practice/Swift/Swift10-LanguageFeatures/L03_KeyPath.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 log：漲幅榜第一名是誰？（XRP 漲了 8.9%，應該是它。）",
            "寫 TODO ②，選「修正版」執行：三個榜單各自對了。",
            "對照兩個版本：Bug 版有三個內容幾乎一樣的函式，修正版只有一個 top(by:)。",
            "想想：如果要新增一個「依 24 小時最高價」的榜單，兩個版本各要寫幾行？",
        ],
        expectBug: "漲幅榜第一名顯示 BTC（成交量最大的），應該是 XRP。",
        expectFixed: "成交量榜 BTC、漲幅榜 XRP、價格榜 BTC。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("成交量榜第一名：BTC") { return "成交量榜第一名應該是 BTC" }
            if !lines.contains("漲幅榜第一名：BTC") { return "Bug 還沒重現：漲幅榜的比較要用到 volume（複製貼上沒改欄位），榜首變成 BTC" }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("成交量榜第一名：BTC") { return "成交量榜第一名應該是 BTC：top(_:by:) 用 \\.volume" }
            if !lines.contains("漲幅榜第一名：XRP") { return "漲幅榜第一名應該是 XRP：top(_:by:) 用 \\.changePercent" }
            if !lines.contains("價格榜第一名：BTC") { return "價格榜第一名應該是 BTC：top(_:by:) 用 \\.price" }
            return nil
        }
    )
}
