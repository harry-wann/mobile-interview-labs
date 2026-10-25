// L02：行情每 50 毫秒跳一次，畫面卻等到行情停了才更新（行情用 debounce）
// 對應複習站：Coding02TimeStreams 第 2 題「debounce、throttle、sample 差在哪？行情畫面用哪一個？」
//
// 情境：行情每 50 毫秒送來一筆價格，從第 0 到第 1000 毫秒共 21 筆，畫面希望「最多每 200 毫秒更新一次，而且永遠顯示最新價格」。
//       演練用純函式處理：輸入是帶時間的價格，輸出是畫面實際更新的時間與價格。（Flow 的寫法是 debounce(300) 與 sample(200)。）
// Bug 版：用 debounce（輸入停下來 300 毫秒才送出最後一個值）。行情一直在跳，永遠等不到「停下來」，
//        畫面整整 1 秒沒有動，等到行情停了才更新一次（第 1300 毫秒）。
// 修正版：改用 sample（每隔 200 毫秒，送出這 200 毫秒內最新的一筆）。畫面每 200 毫秒更新一次，顯示的永遠是最新價格。
package tw.harry.labs.solution.coding02timestreams

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val ticks = (0..20).map { C02Tick(it * 50, 15_000_000 + it * 1_000) }

/** 演練流程（兩個版本共用）：把 21 筆行情交給 updates，看畫面更新的次數與最長多久沒動 */
private fun scenario(log: LabLog, updates: (List<C02Tick>) -> List<C02Tick>) {
    val out = updates(ticks)
    log("行情每 50 毫秒跳一次，共 ${ticks.size} 筆（第 0 到 1000 毫秒）")
    log("畫面共更新 ${out.size} 次，更新的時間：${out.map { it.time }}")
    var previous = 0
    var longest = 0
    for (update in out) {
        longest = maxOf(longest, update.time - previous)
        previous = update.time
    }
    log("畫面最長 $longest 毫秒沒有更新")
    // 每次更新的價格，都應該是「更新當下之前」最新的一筆行情
    val fresh = out.all { update -> ticks.lastOrNull { it.time < update.time }?.price == update.price }
    log("每次更新的價格都是當下最新的一筆：${if (fresh) "是" else "否"}")
}

// MARK: - Bug 版
private object L02Bug {
    fun updates(ticks: List<C02Tick>): List<C02Tick> {
        return c02Debounce(ticks, 300)                           // 輸入停下來 300 毫秒才送出
    }

    suspend fun run(log: LabLog) = scenario(log, ::updates)
}

// MARK: - 修正版
private object L02Fixed {
    /** 每隔 interval 毫秒送出一次，內容是這段時間內最新的一筆 */
    fun sample(ticks: List<C02Tick>, interval: Int): List<C02Tick> {
        val result = ArrayList<C02Tick>()
        for (tick in ticks) {
            val windowEnd = (tick.time / interval + 1) * interval        // 這一筆屬於哪一段，那一段什麼時候結束
            if (result.isNotEmpty() && result.last().time == windowEnd) {
                result[result.size - 1] = C02Tick(windowEnd, tick.price)  // 同一段：後來的取代前面的
            } else {
                result.add(C02Tick(windowEnd, tick.price))               // 新的一段
            }
        }
        return result
    }

    suspend fun run(log: LabLog) = scenario(log) { sample(it, 200) }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "行情一直跳，畫面卻不更新",
    question = "第 2 題：debounce、throttle、sample 差在哪？行情畫面用哪一個？",
    goal = "行情要用 sample（每段時間送最新的）。debounce 要等輸入停下來，行情一直在變就永遠等不到。",
    steps = listOf(
        "選「Bug 版」執行，看畫面更新幾次、最長多久沒更新。",
        "選「修正版」執行，確認畫面大約每 200 毫秒更新一次，而且每次都是最新價格。",
        "想一想：搜尋框為什麼適合 debounce，行情卻不行？",
    ),
    expectBug = "「畫面共更新 1 次」，時間是 [1300]，最長 1300 毫秒沒有更新。",
    expectFixed = "「畫面共更新 6 次」，時間 [200, 400, 600, 800, 1000, 1200]，最長 200 毫秒沒有更新，每次都是最新價格。",
    file = "coding02-time-streams/L02_QuoteSample.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("畫面共更新") } -> "演練沒有跑完：要用 scenario(log, updates) 執行"
            "畫面共更新 0 次，更新的時間：[]" in lines -> "updates 還沒寫：呼叫 c02Debounce(ticks, 300)"
            "畫面共更新 1 次，更新的時間：[1300]" !in lines -> "Bug 還沒重現：用 wait 300 的 debounce，行情每 50 毫秒就來一筆，只有最後一筆會送出"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("畫面共更新") } -> "演練沒有跑完：要用 scenario(log, updates) 執行"
            "畫面共更新 6 次，更新的時間：[200, 400, 600, 800, 1000, 1200]" !in lines ->
                "sample 還沒寫對：21 筆行情要分成 6 段（每 200 毫秒一段），每段在結束時送出一次"
            "每次更新的價格都是當下最新的一筆：是" !in lines -> "同一段裡有好幾筆時，要留最後（最新）的那一筆"
            else -> null
        }
    },
)
