// L04：只想測下單的成本估算，卻得先把整個行情服務（WebSocket）啟動起來（依賴方向要指向抽象）
// 對應複習站：Advanced09Architecture 第 4 題「模組化怎麼切才不會切過頭？」、第 5 題「依賴注入（DI）怎麼做？」（依賴反轉、建構子注入）
//
// 情境：下單模組要估算「買 0.5 BTC 大概要多少錢」（數量 × 最新價）。最新價來自行情模組的 MarketDataService：
//       第一次用到它就會開一條 WebSocket 連線，連線建立、收到第一筆行情之前，lastPrice 是 0。
//       演練模擬「寫一個單元測試估算成本」，測試要用固定的價格 15000000，並且不應該啟動任何真的連線。
// Bug 版：下單模組直接依賴行情模組的具體類別和單例（MarketDataService.shared）。測試呼叫估算時，行情服務被啟動（1 條連線），
//        價格是 0，估算出 0 日圓；測試沒辦法決定價格，要先把整個行情服務與它的網路層起起來。
// 修正版：依賴反轉：下單模組自己宣告一個很小的 protocol（PriceProviding），透過建構子注入；行情模組實作它，測試傳一個固定價格的替身。
//        不需要啟動任何連線，結果穩定。
import Foundation
import LabKit

/// 行情模組（兩個版本共用）：模擬的連線次數會記錄在 connections
private enum A09Market {
    static var connections = 0
    static var instance: MarketDataService?

    /// 單例：第一次存取才建立，並且開一條連線
    static var shared: MarketDataService {
        if let instance { return instance }
        let created = MarketDataService()
        instance = created
        return created
    }

    static func reset() {
        connections = 0
        instance = nil
    }
}

private final class MarketDataService {
    fileprivate init() {
        A09Market.connections += 1                              // 建立時就開 WebSocket 連線
    }

    /// 最新價：連線剛建立，還沒收到第一筆行情，所以是 0
    var lastPrice: Decimal { 0 }
}

/// 演練流程（兩個版本共用）：makeEstimator 建立「估算成本」的函式（數量 → 日圓）
private func scenario(_ log: LabLog, makeEstimator: () -> (Decimal) -> Decimal) {
    A09Market.reset()
    let estimate = makeEstimator()
    log("估算買 0.5 BTC 的成本：\(estimate(Decimal(string: "0.5")!)) JPY（測試要用的價格是 15000000）")
    log("測試過程中啟動了 \(A09Market.connections) 條真的行情連線")
}

// MARK: - Bug 版
private enum L04Bug {
    /// 下單模組：直接依賴行情模組的單例
    final class OrderPreview {
        func estimate(quantity: Decimal) -> Decimal {
            quantity * A09Market.shared.lastPrice               // 呼叫的當下才啟動行情服務，測試沒辦法換掉它
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { OrderPreview().estimate(quantity:) }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    /// 下單模組自己宣告它需要什麼（很小的介面）
    protocol PriceProviding {
        var lastPrice: Decimal { get }
    }

    /// 測試用的替身：固定的價格
    struct StubPrices: PriceProviding {
        let lastPrice: Decimal
    }

    final class OrderPreview {
        private let prices: PriceProviding

        init(prices: PriceProviding) {                          // 建構子注入：用什麼價格來源，由外面決定
            self.prices = prices
        }

        func estimate(quantity: Decimal) -> Decimal {
            quantity * prices.lastPrice
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { OrderPreview(prices: StubPrices(lastPrice: 15_000_000)).estimate(quantity:) }
    }
}

// MARK: - 演練設定
extension Advanced09Architecture {
    static let l04 = Lab(
        id: "L04",
        title: "測試估算成本，卻啟動了整個行情服務",
        question: "第 4 題：模組化怎麼切才不會切過頭？（依賴方向）、第 5 題：依賴注入（DI）怎麼做？",
        goal: "下單模組不該依賴行情模組的具體類別：由下單模組宣告需要的介面，行情模組去實作，再從外面注入。",
        steps: [
            "選「Bug 版」執行，看估算出來的成本，以及「啟動了幾條真的行情連線」。",
            "想一想：測試要驗證的只是「數量乘以價格」，為什麼要碰到網路連線？換一個固定價格的來源做得到嗎？",
            "選「修正版」執行：結果是 7500000，而且沒有啟動任何連線。",
            "進階：Android 的做法一樣：下單模組宣告 interface，建構子注入（或 Hilt 的 @Inject constructor）；測試傳 fake。預設參數 `= MarketDataService.shared` 看起來方便，卻讓依賴偷偷回到單例。",
        ],
        expectBug: "「估算買 0.5 BTC 的成本：0 JPY」，「測試過程中啟動了 1 條真的行情連線」。",
        expectFixed: "「估算買 0.5 BTC 的成本：7500000 JPY」，「測試過程中啟動了 0 條真的行情連線」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("估算買 0.5 BTC") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("測試過程中啟動了 1 條真的行情連線") { return "Bug 還沒重現：estimate 要直接用 A09Market.shared.lastPrice，會啟動 1 條連線" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("估算買 0.5 BTC") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("估算買 0.5 BTC 的成本：7500000 JPY（測試要用的價格是 15000000）") { return "估算結果應該是 7500000：OrderPreview 要用注入進來的 prices.lastPrice" }
            if !lines.contains("測試過程中啟動了 0 條真的行情連線") { return "不應該啟動任何連線：不能碰 A09Market.shared" }
            return nil
        }
    )
}
