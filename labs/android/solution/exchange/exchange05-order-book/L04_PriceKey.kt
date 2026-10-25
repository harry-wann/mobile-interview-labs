// L04：同一個價位在板上分成兩檔（BigDecimal 當 HashMap 的 key，equals
// 連小數位數一起比）
// 對應複習站：Exchange05OrderBook 第 3
// 題「本地的訂單簿要用什麼資料結構？為什麼價格不能用 `Double`？」
//
// 情境：買方板的快照（REST）把價格寫成 "4860000.0"、"4859000.0"；之後
// WebSocket 的更新把同一個價格寫成 "4860000"（數量變 0.9）、
//       "4859000.00"（數量 0，刪除這一檔），又新增 "4858000"（數量 0.3）。
// 同一個價格，不同的寫法，兩個來源很常不一致。
//       伺服器的板：4860000×0.9、4858000×0.3，共 2 檔。
//       （iOS 的 `Decimal` 當字典的 key 比的是數值，沒有這個問題；iOS 的 Bug
// 版是直接用價格的文字當 key。）
// Bug 版：BigDecimal(priceText) 直接當 HashMap 的 key。BigDecimal 的 equals
// 會比小數位數：4860000.0 與 4860000 不相等，
//        同一個價位分成兩檔；"4859000.00" 刪不到 "4859000.0"。
// 修正版：key 先 stripTrailingZeros()（去掉尾端的 0），4860000.0 與 4860000
// 就是同一個 key。（也可以改用 TreeMap，它用 compareTo 比數值。）
package tw.harry.labs.solution.exchange05orderbook

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface L04PriceBook {
    fun set(priceText: String, qty: BigDecimal)

    /** 由高價排到低價 */
    val levels: List<E05Level>
}

private fun describe(levels: List<E05Level>) = "共 ${levels.size} 檔：" +
    levels.joinToString("、") { "${e05Text(it.price)}×${e05Text(it.qty)}" }

/** 演練流程（兩個版本共用）：快照與即時更新，同一個價格用不同的文字寫法 */
private fun scenario(log: LabLog, book: L04PriceBook) {
    book.set("4860000.0", e05("0.4"))
    book.set("4859000.0", e05("0.6"))
    log("快照（REST）→ ${describe(book.levels)}")
    book.set("4860000", e05("0.9"))
    book.set("4859000.00", e05("0"))
    book.set("4858000", e05("0.3"))
    log("更新（WebSocket）→ ${describe(book.levels)}")
    log("最後：${describe(book.levels)}（伺服器的板：共 2 檔：4860000×0.9、4858000×0.3）")
}

// MARK: - Bug 版
private object L04Bug {
    class Book : L04PriceBook {
        private val byPrice = HashMap<BigDecimal, BigDecimal>()

        override fun set(priceText: String, qty: BigDecimal) {
            // 4860000.0 與 4860000 的 equals 是 false
            val key = BigDecimal(priceText)
            if (qty.signum() == 0) byPrice.remove(key) else byPrice[key] = qty
        }

        override val levels: List<E05Level>
            get() = byPrice.map { E05Level(it.key, it.value) }
                .sortedWith(
                    compareByDescending<E05Level> {
                        it.price
                    }.thenByDescending { it.qty },
                )
    }

    suspend fun run(log: LabLog) = scenario(log, Book())
}

// MARK: - 修正版
private object L04Fixed {
    class Book : L04PriceBook {
        private val byPrice = HashMap<BigDecimal, BigDecimal>()

        override fun set(priceText: String, qty: BigDecimal) {
            // 去掉尾端的 0：同一個價格只有一種寫法
            val key = BigDecimal(priceText).stripTrailingZeros()
            if (qty.signum() == 0) byPrice.remove(key) else byPrice[key] = qty
        }

        override val levels: List<E05Level>
            get() = byPrice.map {
                E05Level(it.key, it.value)
            }.sortedByDescending { it.price }
    }

    suspend fun run(log: LabLog) = scenario(log, Book())
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "同一個價位在板上分成兩檔",
    question = "第 3 題：本地的訂單簿要用什麼資料結構？為什麼價格不能用 `Double`？",
    goal = "價格當 Map 的 key，要比「數值」，不能比文字（或 BigDecimal 的 equals）：4860000.0 與 4860000 必須是同一檔。",
    steps = listOf(
        "選「Bug 版」執行，看快照之後有幾檔？更新之後呢？4,860,000 這個價格出現了幾次？",
        "想一想：「4859000.00」數量 0 想刪掉 4,859,000 這一檔，為什麼沒刪到？",
        "選「修正版」執行，更新之後要剩 2 檔：4860000×0.9、4858000×0.3。",
        "進階：除了 `stripTrailingZeros()`，也可以改用 `TreeMap`，它是用 `compareTo` 比數值的。iOS 的 `Decimal` 當 key 為什麼沒有這個問題？",
    ),
    expectBug = "更新之後「共 4 檔：4860000×0.9、4860000×0.4、4859000×0.6、4858000×0.3」，4,860,000 分成兩檔，要刪除的 4,859,000 還在。",
    expectFixed = "更新之後「共 2 檔：4860000×0.9、4858000×0.3」。",
    file = "exchange05-order-book/L04_PriceKey.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, book) 執行"
            }

            lines.none {
                it.startsWith(
                    "最後：共 4 檔：4860000×0.9、4860000×0.4、4859000×0.6、4858000×0.3",
                )
            } -> {
                "Bug 還沒重現：set 要用 BigDecimal(priceText) 直接當 byPrice 的 key，數量 0 就 remove；4860000.0 與 4860000 會變成兩檔"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, book) 執行"
            }

            lines.none {
                it.startsWith("最後：共 2 檔：4860000×0.9、4858000×0.3")
            } -> {
                "set 的 key 要先 stripTrailingZeros()（數量 0 就 remove），同一個價格才會是同一檔"
            }

            else -> {
                null
            }
        }
    },
)
