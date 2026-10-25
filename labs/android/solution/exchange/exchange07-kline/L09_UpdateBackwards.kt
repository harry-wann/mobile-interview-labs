// L09：圖表停止更新了，後面的 K 線都沒有畫出來（`update` 遇到時間倒退丟出例外，沒有擋也沒有接）
// 對應複習站：Exchange07Kline 第 7 題「用 WebView 放 TradingView Lightweight Charts，資料怎麼傳過去？要注意什麼？」
//
// 情境：App 要把 K 線一根一根用 `update` 推給圖表。和 Lightweight Charts 一樣，`update` 只能推相同或較新的時間，時間倒退會丟出例外。
//        從背景回到前景、合併補資料的時候，依序要推的時間是：60、120、180、120（重送的舊資料，已經推過了）、240、300。
//        正確結果：跳過倒退的 120，圖表最後一根是 300，共 5 根（60、120、180、240、300）。
// Bug 版：依序 `chart.update(...)`，沒有擋時間倒退，也沒有接例外。推到第 4 個（120）丟出例外，整個迴圈中斷，240、300 永遠推不出去。
// 修正版：記住最後推送的時間（lastPushedTime），比它舊的跳過；每次 `update` 外面包 `try/catch`，一次失敗不會讓後面全部停掉。
package tw.harry.labs.solution.exchange07kline

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L09ChartError(message: String) : Exception(message)

/** 假的圖表：`update` 只能推相同或較新的時間 */
private class L09FakeChart {
    val times = mutableListOf<Int>()

    fun update(time: Int) {
        val last = times.lastOrNull()
        if (last != null && time < last) throw L09ChartError("Cannot update oldest data, last time=$last, new time=$time")
        times += time
    }
}

/** 演練流程（兩個版本共用）：`push` 把這些時間依序推給圖表 */
private fun scenario(log: LabLog, push: (L09FakeChart, List<Int>, LabLog) -> Unit) {
    val chart = L09FakeChart()
    try {
        push(chart, listOf(60, 120, 180, 120, 240, 300), log)
    } catch (e: L09ChartError) {
        log("更新中斷，丟出例外：${e.message}")
    }
    log("最後：圖表最後一根是 ${chart.times.lastOrNull() ?: "-"}，共 ${chart.times.size} 根（正確答案：最後一根 300，共 5 根）")
}

// MARK: - Bug 版
private object L09Bug {
    suspend fun run(log: LabLog) = scenario(log) { chart, times, _ ->
        for (time in times) chart.update(time)              // 沒有擋時間倒退，也沒有接例外
    }
}

// MARK: - 修正版
private object L09Fixed {
    suspend fun run(log: LabLog) = scenario(log) { chart, times, log ->
        var lastPushedTime = Int.MIN_VALUE
        for (time in times) {
            if (time < lastPushedTime) {                    // 比最後推送的時間還舊：跳過
                log("略過時間倒退的 $time（最後推送的是 $lastPushedTime）")
                continue
            }
            try {
                chart.update(time)
                lastPushedTime = time
            } catch (e: L09ChartError) {
                log("update $time 失敗，繼續處理後面的：${e.message}")      // 一次失敗，不讓後面全部停掉
            }
        }
    }
}

// MARK: - 演練設定
val l09 = Lab(
    id = "L09",
    title = "圖表停止更新，後面的 K 線沒有畫出來",
    question = "第 7 題：用 WebView 放 TradingView Lightweight Charts，資料怎麼傳過去？要注意什麼？",
    goal = "圖表的 `update` 時間不能倒退：要記住最後推送的時間，比它舊的跳過，並且每次 `update` 都包 `try/catch`，一次例外不能讓圖表悄悄停止更新。",
    steps = listOf(
        "選「Bug 版」執行，看圖表最後一根是哪一根，哪幾根沒有推出去？",
        "想一想：丟出例外的是哪一個時間？為什麼 240、300 也跟著沒有畫出來？",
        "選「修正版」執行，要看到「略過時間倒退的 120」，圖表最後一根是 300，共 5 根。",
        "進階：從背景回到前景時，要把「自上次推送以來的所有 K 線」依序推完，才不會在中間留下洞。",
    ),
    expectBug = "推到第 4 個（120）時丟出「Cannot update oldest data」，「最後：圖表最後一根是 180，共 3 根」，240、300 沒有畫出來。",
    expectFixed = "「略過時間倒退的 120（最後推送的是 180）」，「最後：圖表最後一根是 300，共 5 根」。",
    file = "exchange07-kline/L09_UpdateBackwards.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L09Bug.run(log)
            Variant.FIXED -> L09Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, push) 執行"
            lines.none { it.startsWith("最後：圖表最後一根是 180，共 3 根") } ->
                "Bug 還沒重現：用 for 迴圈依序 chart.update(time)，不擋時間倒退、不接例外，第 4 個（120）會丟出例外把迴圈中斷"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, push) 執行"
            lines.none { it.startsWith("略過時間倒退的 120") } -> "記住最後推送的時間（lastPushedTime），比它舊的就跳過，並用 log 寫「略過時間倒退的 …」"
            lines.none { it.startsWith("最後：圖表最後一根是 300，共 5 根") } -> "跳過倒退的 120 之後，240、300 都要推出去；每次 update 成功才更新 lastPushedTime"
            else -> null
        }
    },
)
