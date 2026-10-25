// L02：維護中的價格顯示成 -1，還觸發了誤報的跌破提醒（舊 SDK
// 的怪習慣要擋在邊界，不要滲進新程式）
// 對應複習站：Advanced09Architecture 第 1 題「面對已經上線的舊專案，
// 怎麼逐步演進，而不是整個重寫？」（anti-corruption layer）
//
// 情境：新的畫面要從舊的行情 SDK 取價格。舊 SDK 的習慣是：沒有價格（維護中）
// 時，last 傳 -1。
//       新程式裡有三個地方會用到價格：價格標籤、下單表單的預設價格、「跌破
// 14000000 時通知我」的價格提醒。
//       演練各用一個正常的報價與一個維護中的報價（last 是 -1）測試。
// Bug 版：新程式直接使用舊 SDK 的型別，三個地方各自假設 last 是真的價格。
// 維護中時，標籤顯示「-1 JPY」，
//        下單表單預設價格是 -1，「跌破」提醒還因為 -1 小於 14000000 而觸發。
// 修正版：在邊界放一層 anti-corruption layer（adapter／mapper）：把舊 SDK
// 的型別轉成新程式自己的 Ticker，
//        「沒有價格」轉成 null。三個地方都只認 Ticker，每個都明確處理 null。
// 以後 SDK 換了，只改這一層。
package tw.harry.labs.solution.advanced09architecture

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 舊 SDK 的型別（不能改）：沒有價格時 last 是 -1 */
private class L02LegacyTicker(val symbol: String, val last: Double)

private object L02LegacySdk {
    /** 0 是正常的報價，1 是維護中 */
    fun fetch(index: Int) = if (index ==
        0
    ) {
        L02LegacyTicker("BTC_JPY", 15_000_000.0)
    } else {
        L02LegacyTicker("BTC_JPY", -1.0)
    }
}

private const val ALERT_THRESHOLD = 14_000_000.0

/** 演練流程（兩個版本共用）：fetch 取報價，其餘三個是新程式裡用到價格的地方 */
private fun <T> scenario(
    log: LabLog,
    fetch: (Int) -> T,
    label: (T) -> String,
    defaultPrice: (T) -> String,
    belowAlert: (T) -> Boolean,
) {
    listOf("正常", "維護中").forEachIndexed { index, name ->
        val ticker = fetch(index)
        val price = defaultPrice(ticker)
        log("[$name] 價格標籤：${label(ticker)}")
        log("[$name] 下單表單的預設價格：${price.ifEmpty { "（空白，請使用者輸入）" }}")
        log(
            "[$name] 跌破 14000000 的提醒：${if (belowAlert(
                    ticker,
                )
            ) {
                "觸發"
            } else {
                "不觸發"
            }}",
        )
    }
}

// MARK: - Bug 版
private object L02Bug {
    // 新程式直接使用舊 SDK 的型別
    fun label(ticker: L02LegacyTicker) = "${ticker.last.toInt()} JPY"
    fun defaultPrice(ticker: L02LegacyTicker) = "${ticker.last.toInt()}"
    fun belowAlert(ticker: L02LegacyTicker) = ticker.last <= ALERT_THRESHOLD

    suspend fun run(log: LabLog) = scenario(
        log,
        L02LegacySdk::fetch,
        ::label,
        ::defaultPrice,
        ::belowAlert,
    )
}

// MARK: - 修正版
private object L02Fixed {
    /** 新程式自己的型別：沒有價格就是 null */
    class Ticker(val pair: String, val last: BigDecimal?)

    /** anti-corruption layer：舊 SDK 的型別只出現在這裡 */
    fun adapt(legacy: L02LegacyTicker) = Ticker(
        pair = legacy.symbol.replace("_", "/"),
        // -1 代表沒有價格 → null
        last = if (legacy.last <
            0
        ) {
            null
        } else {
            BigDecimal.valueOf(legacy.last.toLong())
        },
    )

    fun label(ticker: Ticker) =
        ticker.last?.let { "${it.toPlainString()} JPY" } ?: "—"
    fun defaultPrice(ticker: Ticker) = ticker.last?.toPlainString() ?: ""
    fun belowAlert(ticker: Ticker): Boolean {
        // 沒有價格，不能拿來比較
        val last = ticker.last ?: return false
        return last.toDouble() <= ALERT_THRESHOLD
    }

    suspend fun run(log: LabLog) = scenario(log, {
        adapt(L02LegacySdk.fetch(it))
    }, ::label, ::defaultPrice, ::belowAlert)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "維護中的價格顯示 -1，還誤報跌破提醒",
    question = "第 1 題：面對已經上線的舊專案，怎麼逐步演進，而不是整個重寫？（adapter 隔離舊 SDK）",
    goal = "舊 SDK 的型別與怪習慣（-1 代表沒有價格）要擋在邊界，轉成新程式自己的型別；新程式裡不該出現舊 SDK 的型別。",
    steps = listOf(
        "選「Bug 版」執行，看「維護中」那一組的三行：標籤、預設價格、跌破提醒。",
        "想一想：三個地方各自假設 last 是真的價格。如果 SDK 還有別的習慣（例如單位不同），要改幾個地方？",
        "選「修正版」執行：邊界的 adapt 把 -1 轉成 null，三個地方只認自己的 Ticker。",
        "進階：iOS 用 struct 加上轉換函式做同樣的事；這一層也是之後把舊 SDK 整個換掉時，唯一要重寫的地方。",
    ),
    expectBug = "維護中：標籤「-1 JPY」、預設價格「-1」、「跌破 14000000 的提醒：觸發」（誤報）。",
    expectFixed = "維護中：標籤「—」、預設價格「（空白，請使用者輸入）」、「跌破 14000000 的提醒：不觸發」。正常的報價兩個版本都一樣。",
    file = "advanced09-architecture/L02_AntiCorruption.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "[正常] 價格標籤：15000000 JPY" !in lines -> "正常的報價應該顯示「15000000 JPY」"
            "[維護中] 價格標籤：-1 JPY" !in lines -> "Bug 還沒重現：直接使用舊 SDK 的 last，維護中的標籤會是「-1 JPY」"
            "[維護中] 跌破 14000000 的提醒：觸發" !in lines -> "Bug 還沒重現：-1 小於 14000000，跌破提醒會誤報觸發"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "[正常] 價格標籤：15000000 JPY" !in lines -> "正常的報價應該顯示「15000000 JPY」：adapt 要把 last 轉成 BigDecimal"
            "[維護中] 價格標籤：—" !in lines -> "維護中的標籤應該是「—」：adapt 要把 -1 轉成 null，label 處理 null"
            "[維護中] 下單表單的預設價格：（空白，請使用者輸入）" !in lines -> "維護中的預設價格應該是空白：defaultPrice 處理 null"
            "[維護中] 跌破 14000000 的提醒：不觸發" !in lines -> "維護中不該觸發提醒：belowAlert 沒有價格時回傳 false"
            "[正常] 跌破 14000000 的提醒：不觸發" !in lines -> "正常的報價（15000000）不該觸發跌破提醒"
            else -> null
        }
    },
)
