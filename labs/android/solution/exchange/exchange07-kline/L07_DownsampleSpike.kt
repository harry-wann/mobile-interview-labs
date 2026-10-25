// L07：縮小圖表之後，那根暴漲的針不見了（縮小時用「每隔幾根取一根」，
// 沒有取最高和最低）
// 對應複習站：Exchange07Kline 第 6 題「資料量很大時，
// 圖表怎麼只畫看得到的範圍？圖表元件怎麼選？」
//
// 情境：圖表縮小到一個畫面塞進很多根 K 線，每根連 1 個像素都不到，
// 要把落在同一個像素欄位的 K 線合併。
//        演練用 12 根 K 線縮成 4 欄（每 3 根一欄）。第 5 根（索引 4）
// 有一根暴漲的針：最高 130（其他都在 101 到 106 之間）。
//        正確的縮小：每一欄 open 取最早的一根、close 取最晚的一根、high
// 取最大、low 取最小。
// Bug 版：每隔 3 根取 1 根（取索引 0、3、6、9）。針在索引 4，剛好被跳過，
// 縮小後最高價只剩 105。
// 修正版：把每 3 根合併成一根：open 取第一根的 open、close 取最後一根的 close、
// high 取最大、low 取最小。
package tw.harry.labs.solution.exchange07kline

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L07Candle(
    val open: Int,
    val high: Int,
    val low: Int,
    val close: Int,
)

private val candles = listOf(
    L07Candle(100, 101, 99, 100),
    L07Candle(100, 102, 99, 101),
    L07Candle(101, 102, 100, 100),
    L07Candle(100, 101, 99, 100),
    L07Candle(100, 130, 98, 101),
    L07Candle(101, 103, 100, 102),
    L07Candle(102, 103, 101, 102),
    L07Candle(102, 104, 101, 103),
    L07Candle(103, 104, 102, 103),
    L07Candle(103, 105, 102, 104),
    L07Candle(104, 105, 103, 104),
    L07Candle(104, 106, 103, 105),
)

/** 演練流程（兩個版本共用）：把 12 根縮成 4 欄 */
private fun scenario(
    log: LabLog,
    downsample: (List<L07Candle>, Int) -> List<L07Candle>,
) {
    val small = downsample(candles, 4)
    log(
        "縮成 ${small.size} 欄：" +
            small.joinToString("、") {
                "${it.open}/${it.high}/${it.low}/${it.close}"
            } +
            "（開/高/低/收）",
    )
    val maxHigh = small.maxOfOrNull { it.high }?.toString() ?: "-"
    log("最後：縮小後的最高價 $maxHigh（真正的最高價 ${candles.maxOf { it.high }}）")
}

// MARK: - Bug 版
private object L07Bug {
    fun downsample(candles: List<L07Candle>, columns: Int): List<L07Candle> {
        val step = candles.size / columns
        // 每隔 step 根取 1 根
        return candles.filterIndexed { index, _ -> index % step == 0 }
    }

    suspend fun run(log: LabLog) = scenario(log, ::downsample)
}

// MARK: - 修正版
private object L07Fixed {
    fun downsample(candles: List<L07Candle>, columns: Int): List<L07Candle> {
        val size = candles.size / columns
        return candles.chunked(size).map { group ->
            // 每一欄：開盤取最早的、收盤取最晚的、
            L07Candle(
                group.first().open,
                group.maxOf { it.high },
                // 最高取最大的、最低取最小的
                group.minOf { it.low },
                group.last().close,
            )
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::downsample)
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "縮小圖表之後，暴漲的那根針不見了",
    question = "第 6 題：資料量很大時，圖表怎麼只畫看得到的範圍？圖表元件怎麼選？",
    goal = "縮小時要把落在同一個像素欄位的 K 線合併：open 取最早的、close 取最晚的、high 取最大、low 取最小。每隔幾根取一根，會把最高點和最低點丟掉。",
    steps = listOf(
        "選「Bug 版」執行，看縮小後的最高價，和真正的最高價比一比。",
        "想一想：每隔 3 根取 1 根，取到的是索引 0、3、6、9。那根暴漲的針在索引幾？",
        "選「修正版」執行，縮小後的最高價要和真正的一樣，第二欄要是 100/130/98/102。",
        "進階：只畫可視範圍時，要用二分搜尋在已排序的資料裡找出起訖索引，而不是從頭掃描；向左拖才分頁載入更舊的歷史。",
    ),
    expectBug = "縮成 4 欄「100/101/99/100、100/101/99/100、102/103/101/102、103/105/102/104」，「最後：縮小後的最高價 105（真正的最高價 130）」。",
    expectFixed = "縮成 4 欄「100/102/99/100、100/130/98/102、102/104/101/103、103/106/102/105」，「最後：縮小後的最高價 130（真正的最高價 130）」。",
    file = "exchange07-kline/L07_DownsampleSpike.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("最後")
            } -> "演練沒有跑完：要用 scenario(log, downsample) 執行"

            lines.none {
                it.startsWith("最後：縮小後的最高價 105")
            } -> "Bug 還沒重現：每隔 step（candles.size / columns）根取 1 根（index % step == 0），針在索引 4 會被跳過"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, downsample) 執行"
            }

            lines.none {
                it.startsWith(
                    "縮成 4 欄：100/102/99/100、100/130/98/102、102/104/101/103、103/106/102/105",
                )
            } -> {
                "把每 3 根合併成一欄：open 取第一根的 open、high 取最大、low 取最小、close 取最後一根的 close"
            }

            else -> {
                null
            }
        }
    },
)
