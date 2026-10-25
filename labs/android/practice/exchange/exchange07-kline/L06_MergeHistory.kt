// L06：切換週期之後，圖表一片空白（歷史資料和暫存的推送直接串起來，id 重複、順序亂）
// 對應複習站：Exchange07Kline 第 5 題「切換週期、接上即時推送時，歷史資料和推送怎麼接起來？」
//
// 情境：使用者切換週期。App 先訂閱新週期的推送並暫存，再用 REST 抓歷史，最後把兩邊合併，一次交給圖表（`setData`）。
//        歷史（REST，已由舊到新）：id 60 收 1、id 120 收 2、id 180 收 3。
//        暫存的推送（訂閱後收到）：id 120 收 99（比歷史最後一根舊的舊資料）、id 180 收 30（同一根的最新資料）、id 240 收 4（新的一根）。
//        正確的合併：60:1、120:2、180:30、240:4。圖表的 `setData` 和 Lightweight Charts 一樣，要求資料「嚴格由舊到新」，id 不能重複，否則丟出例外。
//
// 你要寫：
//   ① Bug 版（L06Bug.run 的 merge 閉包）：(history + buffered) 直接接在一起，依 id 排序。
//   ② 修正版（L06Fixed.run 的 merge 閉包）：比歷史最後一根 id 更舊的推送丟掉；相同 id 以推送為準；最後依 id 排序。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange07-kline/L06_MergeHistory.kt
package tw.harry.labs.practice.exchange07kline

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L06Candle(val id: Long, val close: Int)

private class L06ChartError(message: String) : Exception(message)

/** 假的圖表：`setData` 要求資料嚴格由舊到新（id 不能重複、不能倒退） */
private class L06FakeChart {
    var candles: List<L06Candle> = emptyList()
        private set

    fun setData(data: List<L06Candle>) {
        for (i in 1 until data.size) {
            if (data[i].id <= data[i - 1].id) throw L06ChartError("data must be asc ordered by time（id ${data[i].id} 沒有比前一根 ${data[i - 1].id} 新）")
        }
        candles = data
    }
}

private fun text(candles: List<L06Candle>) = candles.joinToString("、") { "${it.id}:${it.close}" }

/** 演練流程（兩個版本共用）：合併之後一次 setData */
private fun scenario(log: LabLog, merge: (history: List<L06Candle>, buffered: List<L06Candle>) -> List<L06Candle>) {
    val history = listOf(L06Candle(60, 1), L06Candle(120, 2), L06Candle(180, 3))
    val buffered = listOf(L06Candle(120, 99), L06Candle(180, 30), L06Candle(240, 4))
    val merged = merge(history, buffered)
    log("合併後：${text(merged)}")
    val chart = L06FakeChart()
    try {
        chart.setData(merged)
        log("圖表 setData：成功，共 ${chart.candles.size} 根")
    } catch (e: L06ChartError) {
        log("圖表 setData：失敗，${e.message}")
    }
    log("最後：圖表有 ${chart.candles.size} 根${if (chart.candles.isEmpty()) "" else "：" + text(chart.candles)}（正確答案：60:1、120:2、180:30、240:4）")
}

// MARK: - Bug 版
private object L06Bug {
    suspend fun run(log: LabLog) = scenario(log) { history, buffered ->
        // TODO ①：把 history + buffered 直接接在一起，依 id 排序（sortedBy），不去重、不丟舊的。目前只回傳 history 是佔位
        history
    }
}

// MARK: - 修正版
private object L06Fixed {
    suspend fun run(log: LabLog) = scenario(log) { history, buffered ->
        // TODO ②：先把 history 做成「id → Candle」的 Map；buffered 裡 id 不小於歷史最後一根 id 的，放進 Map（相同 id 以推送為準），
        //        比歷史最後一根更舊的丟掉；最後把 Map 的值依 id 排序後回傳。目前只回傳 history 是佔位
        history
    }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "切換週期之後，圖表一片空白",
    question = "第 5 題：切換週期、接上即時推送時，歷史資料和推送怎麼接起來？",
    goal = "歷史與暫存的推送要依 id 合併：比歷史最後一根更舊的推送丟掉，相同 id 以推送為準，最後依 id 由舊到新排序，一次交給圖表。先寫出直接把歷史和推送接在一起的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange07-kline/L06_MergeHistory.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看合併後的資料有哪些 id 重複，圖表的 setData 回報什麼錯誤。",
        "想一想：id 120 的推送（收 99）比歷史最後一根（id 180）還舊，該拿它怎麼辦？id 180 兩邊都有，聽誰的？",
        "寫 TODO ②（修正版），選「修正版」執行，圖表要有 4 根：60:1、120:2、180:30、240:4。",
        "進階：為什麼要先訂閱推送並暫存，再抓歷史？（和訂單簿「先暫存增量、再取快照」是同一個道理，Exchange04 L05、Platform01 L07 練過）",
    ),
    expectBug = "合併後「60:1、120:2、120:99、180:3、180:30、240:4」，圖表 setData 失敗：「最後：圖表有 0 根」。",
    expectFixed = "合併後「60:1、120:2、180:30、240:4」，「最後：圖表有 4 根：60:1、120:2、180:30、240:4」。",
    file = "exchange07-kline/L06_MergeHistory.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, merge) 執行"
            "合併後：60:1、120:2、120:99、180:3、180:30、240:4" !in lines -> "Bug 版要把 history + buffered 直接接在一起，再依 id 排序（不去重、不丟舊的）"
            lines.none { it.startsWith("最後：圖表有 0 根") } -> "Bug 還沒重現：id 重複的資料交給圖表，setData 應該失敗"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, merge) 執行"
            "合併後：60:1、120:2、180:30、240:4" !in lines -> "比歷史最後一根（id 180）更舊的推送丟掉；相同 id 以推送為準；新的 id 加進來；最後依 id 排序"
            else -> null
        }
    },
)
