// L08：把查不到的結果放進 ConcurrentHashMap，直接 NullPointerException
// 對應複習站：Kotlin10JavaInterop 第 6 題「HashMap 和 ConcurrentHashMap
// 差在哪？」
//
// 情境：QuoteCache 把查過的報價快取在 ConcurrentHashMap。
// 已下市的幣種查不到報價（api 回傳 null）。
//
// 你要寫：
//   ① Bug 版（L08Bug.QuoteCache）：cache 是 ConcurrentHashMap<String, Int?>，
// 查完不管查到沒有都 cache[symbol] = price，查不到時 NullPointerException。
//   ② 修正版（L08Fixed.QuoteCache）：cache 是 ConcurrentHashMap<String, Int>，
// 查不到就不放進快取。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin10-java-interop/L08_ConcurrentNull.kt
package tw.harry.labs.practice.kotlin10javainterop

import java.util.concurrent.ConcurrentHashMap
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的報價 API：只有 BTC_JPY 查得到，其他（已下市）回傳 null */
private fun fetchQuote(symbol: String): Int? = if (symbol ==
    "BTC_JPY"
) {
    5_000_000
} else {
    null
}

/**
 * 演練流程（兩個版本共用）：查 BTC_JPY 和已下市的 OLD_JPY，[load]
 * 是「取得報價（有快取就用快取）」
 */
private fun scenario(log: LabLog, load: (String) -> Int?) {
    for (symbol in listOf("BTC_JPY", "OLD_JPY")) {
        try {
            log("查 $symbol：${load(symbol) ?: "查不到"}")
        } catch (e: NullPointerException) {
            log("查 $symbol：丟出 NullPointerException")
        }
    }
}

// MARK: - Bug 版
private object L08Bug {
    class QuoteCache {
        // TODO ①-1：private val cache = ConcurrentHashMap<String, Int?>()
        // （value 型別寫成 Int?：編譯器不會擋）
        // TODO ①-2：load(symbol)：先查快取；沒有就 fetchQuote(symbol)，
        // 不管查到沒有都 cache[symbol] = price，最後回傳 price
        fun load(symbol: String): Int? = null
    }

    fun run(log: LabLog) = QuoteCache().let { cache ->
        scenario(log, cache::load)
    }
}

// MARK: - 修正版
private object L08Fixed {
    class QuoteCache {
        // TODO ②-1：private val cache = ConcurrentHashMap<String, Int>()
        // （value 不可為 null）
        // TODO ②-2：load(symbol)：先查快取；沒有就 fetchQuote(symbol)，
        // 查到才放進快取（price?.let { … }），最後回傳 price
        fun load(symbol: String): Int? = null
    }

    fun run(log: LabLog) = QuoteCache().let { cache ->
        scenario(log, cache::load)
    }
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "查不到的報價放進快取，直接 NPE",
    question = "第 6 題：HashMap 和 ConcurrentHashMap 差在哪？",
    goal = "已下市的幣種查不到報價。看把 null 放進 ConcurrentHashMap 為什麼會 NullPointerException，再改成查不到就不快取。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin10-java-interop/L08_ConcurrentNull.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「查 OLD_JPY」那一行。",
        "寫 TODO ②（修正版），選「修正版」執行，確認顯示「查不到」，沒有例外。",
        "想一想：同樣的程式碼用 HashMap 會怎樣？（HashMap 允許 null 的 key 與 value，不會丟例外）",
    ),
    expectBug = "「查 BTC_JPY：5000000」，「查 OLD_JPY：丟出 NullPointerException」。",
    expectFixed = "「查 BTC_JPY：5000000」，「查 OLD_JPY：查不到」，沒有例外。",
    file = "kotlin10-java-interop/L08_ConcurrentNull.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "查 BTC_JPY：5000000" !in lines -> "查得到的 BTC_JPY 要正常回傳 5000000：load() 要先查快取，沒有再 fetchQuote(symbol)，並放進快取"
            "查 OLD_JPY：丟出 NullPointerException" !in lines -> "查不到的 OLD_JPY 沒有 NPE：Bug 版要把 fetchQuote 的結果（可能是 null）直接 cache[symbol] = price"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "查 BTC_JPY：5000000" !in lines -> "查得到的 BTC_JPY 要正常回傳 5000000：load() 要先查快取，沒有再 fetchQuote(symbol)，並放進快取"
            "查 OLD_JPY：丟出 NullPointerException" in lines -> "還是 NPE：查不到（price 是 null）就不要放進 ConcurrentHashMap"
            "查 OLD_JPY：查不到" !in lines -> "查不到的 OLD_JPY 要回傳 null（顯示「查不到」）"
            else -> null
        }
    },
)
