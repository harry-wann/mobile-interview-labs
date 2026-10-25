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
//
// 你要寫：
//   ① Bug 版：新程式直接用舊 SDK 的型別，三個地方各自假設 last 是真的價格。
// 維護中（-1）時顯示 -1、預設價格 -1、誤報跌破提醒。
//   ② 修正版：邊界放一層 adapt，把舊 SDK 的型別轉成自己的 Ticker（沒有價格就是
// null）；三個地方都明確處理 null。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced09-architecture/L02_AntiCorruption.kt
package tw.harry.labs.practice.advanced09architecture

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
    fun label(ticker: L02LegacyTicker): String {
        // TODO ①-1：直接用舊 SDK 的 ticker.last：回傳「<價格取整數> JPY」，
        // 例如 "15000000 JPY"
        return ""
    }

    fun defaultPrice(ticker: L02LegacyTicker): String {
        // TODO ①-2：直接用舊 SDK 的 ticker.last：回傳取整數之後的字串，例如
        // "15000000"
        return ""
    }

    fun belowAlert(ticker: L02LegacyTicker): Boolean {
        // TODO ①-3：直接用舊 SDK 的 ticker.last：小於等於 ALERT_THRESHOLD
        // 就回傳 true
        return false
    }

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
    fun adapt(legacy: L02LegacyTicker): Ticker {
        // TODO ②-1：把舊 SDK 的型別轉成 Ticker：pair 把符號裡的 "_" 換成 "/"；
        // last 小於 0（-1 代表沒有價格）就是 null，否則轉成 BigDecimal
        return Ticker(legacy.symbol, null)
    }

    fun label(ticker: Ticker): String {
        // TODO ②-2：有價格回傳「<價格> JPY」，沒有價格（null）回傳 "—"
        return ""
    }

    fun defaultPrice(ticker: Ticker): String {
        // TODO ②-3：有價格回傳價格的字串（toPlainString），沒有價格回傳空字串
        return ""
    }

    fun belowAlert(ticker: Ticker): Boolean {
        // TODO ②-4：沒有價格（null）回傳 false；有價格就比較：小於等於
        // ALERT_THRESHOLD 才觸發
        return false
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
    goal = "舊 SDK 的型別與怪習慣（-1 代表沒有價格）要擋在邊界，轉成新程式自己的型別；新程式裡不該出現舊 SDK 的型別。 先寫出直接使用舊 SDK 型別的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced09-architecture/L02_AntiCorruption.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看「維護中」那一組的三行：標籤、預設價格、跌破提醒。",
        "想一想：三個地方各自假設 last 是真的價格。如果 SDK 還有別的習慣（例如單位不同），要改幾個地方？",
        "寫 TODO ②，選「修正版」執行：邊界的 adapt 把 -1 轉成 null，三個地方只認自己的 Ticker。",
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
