// L04：只想測下單的成本估算，卻得先把整個行情服務（WebSocket）啟動起來（依賴方向要指向抽象）
// 對應複習站：Advanced09Architecture 第 4 題「模組化怎麼切才不會切過頭？」、第 5 題「依賴注入（DI）怎麼做？」（依賴反轉、建構子注入）
//
// 情境：下單模組要估算「買 0.5 BTC 大概要多少錢」（數量 × 最新價）。最新價來自行情模組的 MarketDataService：
//       第一次用到它就會開一條 WebSocket 連線，連線建立、收到第一筆行情之前，lastPrice 是 0。
//       演練模擬「寫一個單元測試估算成本」，測試要用固定的價格 15000000，並且不應該啟動任何真的連線。
// Bug 版：下單模組直接依賴行情模組的具體類別和單例（MarketDataService 的 instance）。測試呼叫估算時，行情服務被啟動（1 條連線），
//        價格是 0，估算出 0 日圓；測試沒辦法決定價格，要先把整個行情服務與它的網路層起起來。
// 修正版：依賴反轉：下單模組自己宣告一個很小的 interface（PriceProvider），透過建構子注入（用 Hilt 就是 @Inject constructor）；
//        行情模組實作它，測試傳一個固定價格的替身。不需要啟動任何連線，結果穩定。
package tw.harry.labs.solution.advanced09architecture

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 行情模組（兩個版本共用）：模擬的連線次數會記錄在 connections */
private object L04Market {
    var connections = 0
    private var instance: L04MarketDataService? = null

    /** 單例：第一次存取才建立，並且開一條連線 */
    val shared: L04MarketDataService
        get() = instance ?: L04MarketDataService().also { instance = it }

    fun reset() {
        connections = 0
        instance = null
    }
}

private class L04MarketDataService {
    init {
        L04Market.connections++                                 // 建立時就開 WebSocket 連線
    }

    /** 最新價：連線剛建立，還沒收到第一筆行情，所以是 0 */
    val lastPrice: BigDecimal get() = BigDecimal.ZERO
}

/** 演練流程（兩個版本共用）：makeEstimator 建立「估算成本」的函式（數量 → 日圓） */
private fun scenario(log: LabLog, makeEstimator: () -> (BigDecimal) -> BigDecimal) {
    L04Market.reset()
    val estimate = makeEstimator()
    log("估算買 0.5 BTC 的成本：${estimate(BigDecimal("0.5")).toBigInteger()} JPY（測試要用的價格是 15000000）")
    log("測試過程中啟動了 ${L04Market.connections} 條真的行情連線")
}

// MARK: - Bug 版
private object L04Bug {
    /** 下單模組：直接依賴行情模組的單例 */
    class OrderPreview {
        fun estimate(quantity: BigDecimal): BigDecimal =
            quantity * L04Market.shared.lastPrice               // 呼叫的當下才啟動行情服務，測試沒辦法換掉它
    }

    suspend fun run(log: LabLog) = scenario(log) { OrderPreview()::estimate }
}

// MARK: - 修正版
private object L04Fixed {
    /** 下單模組自己宣告它需要什麼（很小的介面） */
    interface PriceProvider {
        val lastPrice: BigDecimal
    }

    /** 測試用的替身：固定的價格 */
    class StubPrices(override val lastPrice: BigDecimal) : PriceProvider

    class OrderPreview(private val prices: PriceProvider) {     // 建構子注入：用什麼價格來源，由外面決定
        fun estimate(quantity: BigDecimal): BigDecimal = quantity * prices.lastPrice
    }

    suspend fun run(log: LabLog) = scenario(log) { OrderPreview(StubPrices(BigDecimal(15_000_000)))::estimate }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "測試估算成本，卻啟動了整個行情服務",
    question = "第 4 題：模組化怎麼切才不會切過頭？（依賴方向）、第 5 題：依賴注入（DI）怎麼做？",
    goal = "下單模組不該依賴行情模組的具體類別：由下單模組宣告需要的介面，行情模組去實作，再從外面注入。",
    steps = listOf(
        "選「Bug 版」執行，看估算出來的成本，以及「啟動了幾條真的行情連線」。",
        "想一想：測試要驗證的只是「數量乘以價格」，為什麼要碰到網路連線？換一個固定價格的來源做得到嗎？",
        "選「修正版」執行：結果是 7500000，而且沒有啟動任何連線。",
        "進階：Android 用 Hilt 的 @Inject constructor 注入；測試傳 fake，不需要起 Hilt。預設參數 `= MarketDataService.instance` 看起來方便，卻讓依賴偷偷回到單例。",
    ),
    expectBug = "「估算買 0.5 BTC 的成本：0 JPY」，「測試過程中啟動了 1 條真的行情連線」。",
    expectFixed = "「估算買 0.5 BTC 的成本：7500000 JPY」，「測試過程中啟動了 0 條真的行情連線」。",
    file = "advanced09-architecture/L04_DependencyDirection.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("估算買 0.5 BTC") } -> "演練沒有跑完：要用 scenario 執行"
            "測試過程中啟動了 1 條真的行情連線" !in lines -> "Bug 還沒重現：estimate 要直接用 L04Market.shared.lastPrice，會啟動 1 條連線"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("估算買 0.5 BTC") } -> "演練沒有跑完：要用 scenario 執行"
            "估算買 0.5 BTC 的成本：7500000 JPY（測試要用的價格是 15000000）" !in lines -> "估算結果應該是 7500000：OrderPreview 要用注入進來的 prices.lastPrice"
            "測試過程中啟動了 0 條真的行情連線" !in lines -> "不應該啟動任何連線：不能碰 L04Market.shared"
            else -> null
        }
    },
)
