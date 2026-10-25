// L05：合併之後，最佳買價比真實的高，最佳賣價比真實的低（合併價格間隔時，
// 買賣方向都用四捨五入）
// 對應複習站：Exchange05OrderBook 第 4 題「依價格間隔（step）合併時，買方、
// 賣方各往哪個方向取整？」
//
// 情境：App 自己把價格合併成每 1,000 円一檔（step = 1000），同一檔的數量相加。
//       真實的買方：4,860,620×0.2、4,860,120×0.5、4,859,900×0.1（最佳買價 4,
// 860,620）；
//       真實的賣方：4,861,120×0.3、4,861,850×0.4、4,862,001×0.2（最佳賣價 4,
// 861,120）。
//       合併的原則：不能讓使用者以為成交條件比真實更有利（買價不能比真實高，
// 賣價不能比真實低）。
// Bug 版：買方、賣方都用四捨五入（RoundingMode.HALF_UP）。買方 4,860,620 變成
// 4,861,000，賣方 4,861,120 也變成 4,861,000，
//        最佳買價等於最佳賣價，板變成交叉簿（crossed book）：不可能存在的狀態。
// 修正版：買方往下取整（RoundingMode.FLOOR），賣方往上取整（RoundingMode.
// CEILING）。
package tw.harry.labs.solution.exchange05orderbook

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.TreeMap
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 把價格取整到 step 的倍數；`mode` 決定往哪個方向取整 */
private fun bucket(
    price: BigDecimal,
    step: BigDecimal,
    mode: RoundingMode,
): BigDecimal = // 有幾個 step（去掉小數），再乘回 step
    price.divide(step, 0, mode).multiply(step)

/** 合併：同一檔的數量相加，最佳價排在最前面 */
private fun aggregate(
    levels: List<E05Level>,
    step: BigDecimal,
    isBid: Boolean,
    mode: (Boolean) -> RoundingMode,
): List<E05Level> {
    val merged = TreeMap<BigDecimal, BigDecimal>()
    for (level in levels) {
        merged.merge(bucket(level.price, step, mode(isBid)), level.qty) {
            a,
            b,
            ->
            a +
                b
        }
    }
    val result = merged.map { E05Level(it.key, it.value) }
    return if (isBid) {
        result.sortedByDescending {
            it.price
        }
    } else {
        result.sortedBy { it.price }
    }
}

private fun describe(levels: List<E05Level>) = levels.joinToString("、") {
    "${e05Text(it.price)}×${e05Text(it.qty)}"
}

/**
 * 演練流程（兩個版本共用）：用 `mode`（買方或賣方各用什麼取整方式）合併，
 * 再和真實的板比較
 */
private fun scenario(log: LabLog, mode: (Boolean) -> RoundingMode) {
    val step = e05("1000")
    val bids =
        listOf(
            E05Level("4860620", "0.2"),
            E05Level("4860120", "0.5"),
            E05Level("4859900", "0.1"),
        )
    val asks =
        listOf(
            E05Level("4861120", "0.3"),
            E05Level("4861850", "0.4"),
            E05Level("4862001", "0.2"),
        )
    val mergedBids = aggregate(bids, step, true, mode)
    val mergedAsks = aggregate(asks, step, false, mode)
    log("合併後的買方：${describe(mergedBids)}")
    log("合併後的賣方：${describe(mergedAsks)}")
    val bestBid = mergedBids[0].price
    val bestAsk = mergedAsks[0].price
    log(
        "最後：最佳買 ${e05Text(
            bestBid,
        )}（真實 4860620），最佳賣 ${e05Text(bestAsk)}（真實 4861120）",
    )
    val bidText = if (bestBid > e05("4860620")) "高於" else "不高於"
    val askText = if (bestAsk < e05("4861120")) "低於" else "不低於"
    log(
        "買價${bidText}真實，賣價${askText}真實，${if (bestBid >= bestAsk) "是" else "不是"}交叉簿",
    )
}

// MARK: - Bug 版
private object L05Bug {
    // 買賣兩邊都四捨五入
    suspend fun run(log: LabLog) = scenario(log) { RoundingMode.HALF_UP }
}

// MARK: - 修正版
private object L05Fixed {
    // 買方往下取整，賣方往上取整
    suspend fun run(log: LabLog) = scenario(log) { isBid ->
        if (isBid) RoundingMode.FLOOR else RoundingMode.CEILING
    }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "合併之後買價變高、賣價變低",
    question = "第 4 題：依價格間隔（step）合併時，買方、賣方各往哪個方向取整？",
    goal = "合併價格間隔時，買方往下取整、賣方往上取整，合併後的最佳買價不會比真實高，最佳賣價不會比真實低。",
    steps = listOf(
        "選「Bug 版」執行，看合併後的最佳買價與最佳賣價，和真實的最佳價比一比。",
        "最後一行：最佳買價是不是大於等於最佳賣價？這代表板變成什麼狀態？",
        "選「修正版」執行，最佳買要是 4860000，最佳賣要是 4862000。",
        "想一想：如果伺服器用 `type=step1` 幫你合併，取整方向是什麼？（文件沒寫，未證實，要用實際資料確認）",
    ),
    expectBug = "合併後最佳買 4861000、最佳賣 4861000，「買價高於真實，賣價低於真實，是交叉簿」。",
    expectFixed = "合併後買方 4860000×0.7、4859000×0.1，賣方 4862000×0.7、4863000×0.2，最佳買 4860000、最佳賣 4862000，「買價不高於真實，賣價不低於真實，不是交叉簿」。",
    file = "exchange05-order-book/L05_StepDirection.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, mode) 執行"
            }

            "買價高於真實，賣價低於真實，是交叉簿" !in lines -> {
                "Bug 還沒重現：買方、賣方都用四捨五入（RoundingMode.HALF_UP），4,860,620 會變成 4,861,000，4,861,120 也變成 4,861,000"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("最後")
            } -> "演練沒有跑完：要用 scenario(log, mode) 執行"

            "合併後的買方：4860000×0.7、4859000×0.1" !in lines -> "買方要往下取整（RoundingMode.FLOOR）：4,860,620 和 4,860,120 都歸到 4,860,000，合計 0.7"

            "合併後的賣方：4862000×0.7、4863000×0.2" !in lines -> "賣方要往上取整（RoundingMode.CEILING）：4,861,120 和 4,861,850 都歸到 4,862,000，合計 0.7"

            else -> null
        }
    },
)
