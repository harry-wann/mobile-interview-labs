// L03：搜尋框打完字，最後一個字沒有送出去；剛好間隔 wait 的輸入被當成取消（debounce 的邊界）
// 對應複習站：Coding02TimeStreams 第 3 題「debounce 怎麼寫成不需要等待就能測試的函式？」
//
// 情境：把 debounce 寫成純函式：輸入是依時間排序的事件，輸出是實際送出的事件（送出時間 = 事件時間 + wait）。
//       規則：下一個事件在 `time + wait` 之前（嚴格小於）到達，這一個才被取消；剛好在 `time + wait` 到達不算取消；最後一個事件一定送出。
//       演練用 4 組固定的資料（wait = 100 毫秒）檢查答案。
// Bug 版：迴圈只跑到倒數第二個事件（最後一個沒有「下一個」可以比，就被漏掉了），而且判斷寫成 `> wait`，
//        剛好相差 wait 毫秒的事件被當成取消。
// 修正版：最後一個事件一定送出；其他事件看下一個事件的間隔，`>= wait` 才送出。
package tw.harry.labs.solution.coding02timestreams

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L03Case(val title: String, val events: List<C02Event>, val expected: List<C02Event>)

private val cases = listOf(
    L03Case(
        "打字停頓",
        listOf(C02Event(0, "b"), C02Event(50, "bt"), C02Event(300, "btc")),
        listOf(C02Event(150, "bt"), C02Event(400, "btc")),
    ),
    L03Case(
        "剛好間隔 wait",
        listOf(C02Event(0, "a"), C02Event(100, "ab")),
        listOf(C02Event(100, "a"), C02Event(200, "ab")),
    ),
    L03Case("只有一個事件", listOf(C02Event(0, "a")), listOf(C02Event(100, "a"))),
    L03Case("沒有事件", emptyList(), emptyList()),
)

/** 演練流程（兩個版本共用）：4 組資料各算一次，和標準答案比對（wait = 100） */
private fun scenario(log: LabLog, debounce: (List<C02Event>, Int) -> List<C02Event>) {
    var right = 0
    cases.forEachIndexed { index, item ->
        val got = debounce(item.events, 100)
        val ok = got == item.expected
        if (ok) right++
        log("案例 ${index + 1}（${item.title}）：得到 ${c02Text(got)}，期待 ${c02Text(item.expected)} ${if (ok) "✓" else "✗"}")
    }
    log("答對 $right/${cases.size}")
}

// MARK: - Bug 版
private object L03Bug {
    fun debounce(events: List<C02Event>, wait: Int): List<C02Event> {
        val result = ArrayList<C02Event>()
        for (index in 0 until events.size - 1) {                          // 只跑到倒數第二個：最後一個事件被漏掉
            if (events[index + 1].time - events[index].time > wait) {     // 用 >：剛好相差 wait 也被當成取消
                result.add(C02Event(events[index].time + wait, events[index].value))
            }
        }
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::debounce)
}

// MARK: - 修正版
private object L03Fixed {
    fun debounce(events: List<C02Event>, wait: Int): List<C02Event> {
        val result = ArrayList<C02Event>()
        for ((index, event) in events.withIndex()) {
            val isLast = index == events.size - 1
            // 最後一個一定送出；其他的要等下一個事件和這一個相差至少 wait
            if (isLast || events[index + 1].time - event.time >= wait) {
                result.add(C02Event(event.time + wait, event.value))
            }
        }
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::debounce)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "最後一個字沒送出，邊界被當成取消",
    question = "第 3 題：debounce 怎麼寫成不需要等待就能測試的函式？",
    goal = "最後一個事件一定送出；下一個事件剛好在 wait 之後到達不算取消（>= wait 才送出）。",
    steps = listOf(
        "選「Bug 版」執行，看案例 1 少了哪一筆、案例 2 少了哪一筆。",
        "選「修正版」執行，確認 4 個案例全部答對，包含「只有一個事件」與「沒有事件」。",
        "想一想：開始寫之前，要向面試官確認哪一個邊界？（下一個事件剛好在 time + wait 到達，算不算取消）",
    ),
    expectBug = "案例 1 只得到 [(150, bt)]（少了最後的 btc），案例 2 得到 []，案例 3 得到 []；4 組只答對 1 組（空輸入）。",
    expectFixed = "4 組全部答對：「答對 4/4」。",
    file = "coding02-time-streams/L03_DebounceBoundary.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("答對") } -> "演練沒有跑完：要用 scenario(log, debounce) 執行"
            "案例 1（打字停頓）：得到 [(150, bt)]，期待 [(150, bt), (400, btc)] ✗" !in lines ->
                "Bug 還沒重現：迴圈只跑到倒數第二個事件（用 events.size - 1），最後的 btc 才會漏掉"
            "案例 2（剛好間隔 wait）：得到 []，期待 [(100, a), (200, ab)] ✗" !in lines ->
                "Bug 還沒重現：判斷寫成 > wait，剛好相差 100 毫秒的 a 就被當成取消"
            else -> null
        }
    },
    checkFixed = { lines ->
        val wrong = lines.firstOrNull { it.startsWith("案例") && it.endsWith("✗") }
        when {
            lines.none { it.startsWith("答對") } -> "演練沒有跑完：要用 scenario(log, debounce) 執行"
            wrong != null -> "還有案例答錯：$wrong"
            "答對 4/4" !in lines -> "4 個案例都要答對：最後一個事件一定送出，間隔 >= wait 就送出"
            else -> null
        }
    },
)
