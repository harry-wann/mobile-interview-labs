// L05：沒有成交的時段補了 K 線，價格卻是很久以前的收盤價（補 K
// 線用錯前一根的收盤價）
// 對應複習站：Coding03ExchangeAlgorithms 第 3 題「某些時段沒有成交時，K
// 線要怎麼補？」
//
// 情境：1 分鐘 K 線（週期 60 秒），API 沒有回傳沒成交的那幾分鐘。收到的 K
// 線時間是 0、180、240、420，
//       中間缺 60、120（第 1 個缺口，前一根收盤 102）和 300、360（第 2 個缺口，
// 前一根是 240 那根，收盤 115）。
//       缺的時段要補一根開高低收都等於「前一根收盤價」、成交量 0 的 K 線。
// Bug 版：用一個變數 lastClose 記住收盤價，一開始設成第一根的收盤價，
// 之後忘了更新。第 1 個缺口剛好是對的，
//        第 2 個缺口卻還在用第一根的 102，圖上 300、360 兩根的價格比實際低了
// 13 円。
// 修正版：每放進一根真正的 K 線，就把 lastClose 更新成這一根的收盤價。
package tw.harry.labs.solution.coding03exchangealgorithms

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val PERIOD = 60

private val candles = listOf(
    C03Candle(0, 100, 105, 99, 102, 5),
    C03Candle(180, 103, 112, 102, 111, 7),
    C03Candle(240, 111, 116, 110, 115, 4),
    C03Candle(420, 116, 121, 114, 120, 6),
)

/** 演練流程（兩個版本共用）：補洞，列出時間軸與補出來的 K 線 */
private fun scenario(
    log: LabLog,
    fillGaps: (List<C03Candle>, Int) -> List<C03Candle>,
) {
    val result = fillGaps(candles, PERIOD)
    val originalTimes = candles.map { it.time }.toSet()
    val filled = result.filter { it.time !in originalTimes }
    log("時間軸：${result.map { it.time }}（共 ${result.size} 根）")
    log(
        "補了 ${filled.size} 根（時間→價格）：${filled.joinToString(", ") {
            "${it.time}→${it.close}"
        }}",
    )
    val flat = filled.all {
        it.open == it.close && it.high == it.close &&
            it.low == it.close &&
            it.volume == 0
    }
    log("補的 K 線開高低收一樣、成交量 0：${if (flat) "是" else "否"}")
}

// MARK: - Bug 版
private object L05Bug {
    fun fillGaps(candles: List<C03Candle>, period: Int): List<C03Candle> {
        val first = candles.firstOrNull() ?: return emptyList()
        val result = arrayListOf(first)
        // 一開始記下第一根的收盤價，之後都沒有更新
        val lastClose = first.close
        for (candle in candles.drop(1)) {
            var t = result.last().time + period
            while (t < candle.time) {
                result.add(
                    C03Candle(t, lastClose, lastClose, lastClose, lastClose, 0),
                )
                t += period
            }
            // 忘了更新 lastClose
            result.add(candle)
        }
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::fillGaps)
}

// MARK: - 修正版
private object L05Fixed {
    fun fillGaps(candles: List<C03Candle>, period: Int): List<C03Candle> {
        val first = candles.firstOrNull() ?: return emptyList()
        val result = arrayListOf(first)
        var lastClose = first.close
        for (candle in candles.drop(1)) {
            var t = result.last().time + period
            while (t < candle.time) {
                result.add(
                    C03Candle(t, lastClose, lastClose, lastClose, lastClose, 0),
                )
                t += period
            }
            result.add(candle)
            // 每放進一根真的 K 線，就更新「前一根的收盤價」
            lastClose = candle.close
        }
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::fillGaps)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "補的 K 線用了很久以前的收盤價",
    question = "第 3 題：某些時段沒有成交時，K 線要怎麼補？",
    goal = "補洞的價格是「緊鄰缺口前面那一根」的收盤價；每放進一根真的 K 線，就要更新它。",
    steps = listOf(
        "選「Bug 版」執行，比較兩個缺口補出來的價格：第 2 個缺口（300、360）前一根的收盤價是多少？",
        "選「修正版」執行，確認補的價格是 60→102、120→102、300→115、360→115。",
        "想一想：如果漏了 t += period，會發生什麼事？（無限迴圈）開始寫之前要先確認什麼？（時間有沒有對齊週期）",
    ),
    expectBug = "「補了 4 根（時間→價格）：60→102, 120→102, 300→102, 360→102」：第 2 個缺口還在用第一根的收盤價。",
    expectFixed = "「補了 4 根（時間→價格）：60→102, 120→102, 300→115, 360→115」，時間軸 0 到 420 共 8 根。",
    file = "coding03-exchange-algorithms/L05_FillGaps.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("補了") } -> {
                "演練沒有跑完：要用 scenario(log, fillGaps) 執行"
            }

            lines.any { it.startsWith("補了 0 根") } -> {
                "fillGaps 還沒寫：缺的時段要補 K 線"
            }

            "補了 4 根（時間→價格）：60→102, 120→102, 300→102, 360→102" !in lines -> {
                "Bug 還沒重現：lastClose 只在一開始設成第一根的收盤價，之後不更新，第 2 個缺口才會用到 102"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("補了") } -> {
                "演練沒有跑完：要用 scenario(log, fillGaps) 執行"
            }

            lines.any { it.startsWith("補了 0 根") } -> {
                "fillGaps 還沒寫：缺的時段要補 K 線"
            }

            "補了 4 根（時間→價格）：60→102, 120→102, 300→115, 360→115" !in lines -> {
                "第 2 個缺口要用 240 那根的收盤價 115：每放進一根真的 K 線，就更新 lastClose"
            }

            "補的 K 線開高低收一樣、成交量 0：是" !in lines -> {
                "補的 K 線開高低收都要等於 lastClose，成交量是 0"
            }

            else -> {
                null
            }
        }
    },
)
