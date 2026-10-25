// L05：加了快取，API 還是被打了好幾次（class delegation 的陷阱）
// 對應複習站：Kotlin02Functions 第 4 題「by 委派是什麼？class delegation 有什麼陷阱？」
//
// 情境：PriceApi 有 price()（打 API 取價格）和 priceWithFee()（含手續費的價格，內部呼叫 price()）。
//        用 CachedApi 包一層：class CachedApi(inner: PriceApi) : PriceApi by inner，只覆寫 price() 加上快取。
// Bug 版：price() 有快取，沒問題。但 priceWithFee() 沒有覆寫，編譯器產生的轉發方法直接交給 RealApi 去執行，
//        RealApi.priceWithFee() 裡呼叫的 price() 是 RealApi 自己的，完全不知道外面還有一層快取，所以每次都打 API。
// 修正版：連 priceWithFee() 也覆寫，改成呼叫 CachedApi 自己的 price()，才會走到快取。
package tw.harry.labs.solution.kotlin02functions

import java.util.concurrent.atomic.AtomicInteger
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface PriceApi {
    fun price(symbol: String): Long
    fun priceWithFee(symbol: String): Long
}

/** 真正會打 API 的實作：每次 price() 都算一次 API 呼叫 */
private class RealApi(private val log: LabLog) : PriceApi {
    val calls = AtomicInteger(0)

    override fun price(symbol: String): Long {
        log("打 API 取 $symbol 的價格（第 ${calls.incrementAndGet()} 次）")
        return 1_000_000
    }

    override fun priceWithFee(symbol: String): Long = price(symbol) * 1001 / 1000   // 這裡的 price() 是 RealApi 自己的
}

/** 演練流程（兩個版本共用）：各取價格 3 次，看 API 被打了幾次 */
private fun scenario(log: LabLog, real: RealApi, api: PriceApi) {
    repeat(3) { api.price("BTC_JPY") }
    log("取 3 次 price() 後，API 共打了 ${real.calls.get()} 次")
    repeat(3) { api.priceWithFee("BTC_JPY") }
    log("再取 3 次 priceWithFee() 後，API 共打了 ${real.calls.get()} 次")
}

// MARK: - Bug 版
private object L05Bug {
    class CachedApi(private val inner: PriceApi) : PriceApi by inner {
        private val cache = HashMap<String, Long>()

        override fun price(symbol: String): Long = cache.getOrPut(symbol) { inner.price(symbol) }   // 只覆寫 price()
    }

    fun run(log: LabLog) {
        val real = RealApi(log)
        scenario(log, real, CachedApi(real))
    }
}

// MARK: - 修正版
private object L05Fixed {
    class CachedApi(private val inner: PriceApi) : PriceApi by inner {
        private val cache = HashMap<String, Long>()

        override fun price(symbol: String): Long = cache.getOrPut(symbol) { inner.price(symbol) }

        override fun priceWithFee(symbol: String): Long = price(symbol) * 1001 / 1000                // 也覆寫：走自己的 price()
    }

    fun run(log: LabLog) {
        val real = RealApi(log)
        scenario(log, real, CachedApi(real))
    }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "加了快取，API 還是被打了好幾次",
    question = "第 4 題：by 委派是什麼？class delegation 有什麼陷阱？",
    goal = "用 by 把 PriceApi 轉給 RealApi，只覆寫 price() 加快取。看 priceWithFee() 為什麼繞過快取。",
    steps = listOf(
        "選「Bug 版」執行，看「取 3 次 price()」和「再取 3 次 priceWithFee()」後 API 各打了幾次。",
        "選「修正版」執行，確認兩種取法都只打 1 次 API。",
        "想一想：如果 CachedApi 是繼承 RealApi（不是 by），priceWithFee() 裡的 price() 會走到哪一個？（繼承會走到子類別覆寫的版本）",
    ),
    expectBug = "price() 取 3 次，API 打 1 次；再取 3 次 priceWithFee()，API 變成 4 次：priceWithFee() 完全沒用到快取。",
    expectFixed = "price()、priceWithFee() 各取 3 次，API 一共只打 1 次。",
    file = "kotlin02-functions/L05_ClassDelegation.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "取 3 次 price() 後，API 共打了 1 次" !in lines -> "price() 要有快取（3 次只打 1 次 API）：用 cache.getOrPut(symbol) { inner.price(symbol) }"
            "再取 3 次 priceWithFee() 後，API 共打了 4 次" !in lines -> "Bug 版不要覆寫 priceWithFee()：它會直接轉給 RealApi，每次都打 API（共 4 次）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "取 3 次 price() 後，API 共打了 1 次" !in lines -> "price() 要有快取（3 次只打 1 次 API）"
            "再取 3 次 priceWithFee() 後，API 共打了 1 次" !in lines -> "priceWithFee() 也要走快取：覆寫它，改呼叫 CachedApi 自己的 price()"
            else -> null
        }
    },
)
