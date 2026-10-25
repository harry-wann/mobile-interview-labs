// L03：BTC 顯示成 ETH 的價格（TaskGroup 的 for await 是依「完成的順序」，
// 不是加入的順序）
// 對應複習站：Swift04StructuredConcurrency 第 2 題「async let 和 TaskGroup
// 怎麼選？怎麼限制同時進行的請求數量？」
//
// 情境：自選清單 BTC、ETH、XRP 同時抓價格。ETH 的回應最快（100 毫秒），XRP
// 次之（200 毫秒），BTC 最慢（300 毫秒）。
// Bug 版：for await 把價格依序放進陣列，再用 zip 和交易對清單配對。
// 陣列的順序是完成的順序（ETH、XRP、BTC），配錯了。
// 修正版：每個任務的回傳值一起帶上交易對，結果放進以交易對當 key 的字典，
// 不依賴順序。
import Foundation
import LabKit

/// 假的 ticker API：每個交易對的回應時間和價格不一樣（兩個版本共用）
private func fetchPrice(_ symbol: String) async -> Double {
    switch symbol {
    case "BTC/JPY":
        try? await Task.sleep(for: .milliseconds(300))
        return 15_000_000
    case "ETH/JPY":
        try? await Task.sleep(for: .milliseconds(100))
        return 400_000
    default:
        try? await Task.sleep(for: .milliseconds(200))
        return 80
    }
}

private let symbols = ["BTC/JPY", "ETH/JPY", "XRP/JPY"]

// MARK: - Bug 版
private enum L03Bug {
    static func run(_ log: LabLog) async {
        let prices = await withTaskGroup(of: Double.self) { group -> [Double] in
            for symbol in symbols {
                group.addTask { await fetchPrice(symbol) }
            }
            var out: [Double] = []
            // 誰先回來就先放進去
            for await price in group { out.append(price) }
            return out
        }
        // 以為順序和 symbols 一樣
        for (symbol, price) in zip(symbols, prices) {
            log("\(symbol)：\(Int(price))")
        }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func run(_ log: LabLog) async {
        let prices = await withTaskGroup(of: (String, Double).self) {
            group -> [String: Double] in
            for symbol in symbols {
                // 回傳值一起帶上交易對
                group.addTask { (symbol, await fetchPrice(symbol)) }
            }
            var out: [String: Double] = [:]
            for await (symbol, price) in group { out[symbol] = price }
            return out
        }
        // 依照清單的順序顯示
        for symbol in symbols {
            log("\(symbol)：\(Int(prices[symbol] ?? 0))")
        }
    }
}

// MARK: - 演練設定
extension Swift04StructuredConcurrency {
    static let l03 = Lab(
        id: "L03",
        title: "BTC 顯示成 ETH 的價格",
        question: "第 2 題：async let 和 TaskGroup 怎麼選？怎麼限制同時進行的請求數量？",
        goal: "三個交易對同時抓價格，每個交易對要顯示自己的價格，不論哪個回應先到。",
        steps: [
            "選「Bug 版」執行，看每個交易對顯示的價格：BTC 應該是 1500 萬，卻顯示成別人的價格。",
            "想一想：for await 拿到結果的順序是加入的順序，還是完成的順序？",
            "選「修正版」執行，三個交易對都應該顯示自己的價格。",
        ],
        expectBug: "BTC/JPY：400000、ETH/JPY：80、XRP/JPY：15000000，全部配錯。",
        expectFixed: "BTC/JPY：15000000、ETH/JPY：400000、XRP/JPY：80。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.count >= 3 else { return "三個交易對都要顯示一行價格" }
            return lines.contains("BTC/JPY：400000")
                ? nil
                : "Bug 還沒重現：BTC 應該（錯誤地）顯示成最快回來的 ETH 價格 400000（依 for await 的順序放進陣列，再用 zip 配對）"
        },
        checkFixed: { lines in
            guard lines.contains("BTC/JPY：15000000") else {
                return "BTC/JPY 應該顯示 15000000：每個任務要把交易對一起回傳，結果存進字典"
            }
            guard lines.contains("ETH/JPY：400000"), lines.contains("XRP/JPY：80")
            else { return "ETH/JPY 應該是 400000、XRP/JPY 應該是 80" }
            return nil
        }
    )
}
