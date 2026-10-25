// L07：訂單狀態更新亂了順序，資料庫最後留下「New」（用 limitedParallelism(1) 讓寫入排隊）
// 對應複習站：Platform02Threading 第 5 題「Android 的 Dispatchers 怎麼選？為什麼不能在主執行緒 runBlocking？」
//
// 情境：同一張訂單的狀態依序更新三次：New → PartiallyFilled → Filled。三次寫入都交給資料庫，但每次寫入花的時間不一樣
//        （演練用 Thread.sleep 代替：第一次 60 ms、第二次 30 ms、第三次 5 ms）。看資料庫最後留下哪個狀態。
// Bug 版：三次寫入都 launch 在 Dispatchers.IO。IO 可以同時用很多執行緒，三次寫入同時進行，誰先做完誰先寫，最後做完的是最慢的第一次：資料庫最後是 New。
// 修正版：寫入資料庫用 Dispatchers.IO.limitedParallelism(1)：最多同時 1 條執行緒，寫入一個一個照順序做，最後是 Filled。
package tw.harry.labs.solution.platform02threading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的資料庫（兩個版本共用）：寫入要花一段時間，最後寫進去的就是現在的狀態 */
private class L07OrderDb {
    @Volatile var status = "（空）"

    fun write(status: String, takesMs: Long) {
        Thread.sleep(takesMs)
        this.status = status
    }
}

/** 演練流程（兩個版本共用）：[writeAll] 決定三次寫入怎麼交給資料庫 */
private suspend fun scenario(log: LabLog, writeAll: suspend (L07OrderDb) -> Unit) {
    val db = L07OrderDb()
    writeAll(db)
    log("依序更新 New → PartiallyFilled → Filled，資料庫最後的狀態是 ${db.status}")
}

// MARK: - Bug 版
private object L07Bug {
    suspend fun run(log: LabLog) = scenario(log) { db ->
        coroutineScope {
            launch(Dispatchers.IO) { db.write("New", 60) }                      // 三次寫入同時進行
            launch(Dispatchers.IO) { db.write("PartiallyFilled", 30) }
            launch(Dispatchers.IO) { db.write("Filled", 5) }
        }
    }
}

// MARK: - 修正版
@OptIn(ExperimentalCoroutinesApi::class)
private object L07Fixed {
    suspend fun run(log: LabLog) = scenario(log) { db ->
        val dbDispatcher = Dispatchers.IO.limitedParallelism(1)                 // 最多同時 1 條，照順序一個一個做
        coroutineScope {
            launch(dbDispatcher) { db.write("New", 60) }
            launch(dbDispatcher) { db.write("PartiallyFilled", 30) }
            launch(dbDispatcher) { db.write("Filled", 5) }
        }
    }
}

// MARK: - 演練設定
private fun finalStatus(lines: List<String>): String? =
    lines.firstOrNull { it.startsWith("依序更新 New → PartiallyFilled → Filled，資料庫最後的狀態是 ") }
        ?.substringAfter("資料庫最後的狀態是 ")

val l07 = Lab(
    id = "L07",
    title = "訂單狀態亂了順序，最後留下 New",
    question = "第 5 題：Android 的 Dispatchers 怎麼選？為什麼不能在主執行緒 runBlocking？",
    goal = "同一份資料的寫入要一個一個照順序做。用 limitedParallelism(1) 限制 Dispatchers.IO 同時只用 1 條執行緒。",
    steps = listOf(
        "選「Bug 版」按執行，看「資料庫最後的狀態」。",
        "想一想：三次寫入的順序是 New、PartiallyFilled、Filled，為什麼最後留下的是 New？（寫入同時進行，最慢的最後完成）",
        "選「修正版」執行：資料庫最後的狀態是 Filled。",
    ),
    expectBug = "資料庫最後的狀態是 New：同時進行，最慢的第一次最後才寫完，蓋掉了後面的狀態。",
    expectFixed = "資料庫最後的狀態是 Filled。",
    file = "platform02-threading/L07_IoOrdering.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when (val s = finalStatus(lines)) {
            null -> "演練沒有跑完"
            "New" -> null
            "（空）" -> "資料庫沒有寫入：要 launch 三次寫入，交給 Dispatchers.IO"
            else -> "Bug 還沒重現（最後是 $s）：三次寫入要同時在 Dispatchers.IO 上進行，不要限制數量"
        }
    },
    checkFixed = { lines ->
        when (val s = finalStatus(lines)) {
            null -> "演練沒有跑完"
            "Filled" -> null
            "（空）" -> "資料庫沒有寫入：要 launch 三次寫入"
            else -> "最後的狀態是 $s：寫入要照順序一個一個做，用 Dispatchers.IO.limitedParallelism(1)"
        }
    },
)
