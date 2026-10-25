// L02：列表和詳細頁來回切換，訂閱一直 sub、unsub，
// 每次回來都要重新等資料（最後一個畫面離開，要等幾秒才退訂）
// 對應複習站：Design02MarketData 第 3 題「一條連線、好幾個畫面，要怎麼共用？」
// （訂閱管理者的延遲退訂）
//
// 情境：引用計數已經寫好了（上一個演練練過）：畫面離開、計數變成 0 的時候，
// 由你決定什麼時候退訂。
//       BTC 詳細頁獨自使用 market.btcjpy.depth.step0（訂單簿）。
// 使用者在列表和詳細頁之間來回切換：
//       0 ms 進詳細頁 → 1000 ms 返回列表 → 2000 ms 又進詳細頁 → 4000 ms
// 返回列表，之後不再進去。
//       時間是演練編好的，由 tick(ms) 往前走（真的 App 是用 delay 或 Handler.
// postDelayed；這裡不用等真實的時鐘）。延遲退訂的等待時間是 3000 ms。
// Bug 版：計數變成 0 就馬上送 unsub。使用者來回切換，訂單簿被訂了兩次、
// 退訂了兩次；第 2 次回到詳細頁時訂閱已經不在，
//        要重新 sub，等第一筆資料到了才有畫面。
// 修正版：計數變成 0 時，不馬上退訂，只記下「3000 ms 之後才退訂」；
// 在期限內又有畫面要，就取消退訂（訂閱一直都在，不用再送 sub）。
//        tick(ms) 發現到期了，才真的送 unsub。
package tw.harry.labs.solution.design02marketdata

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val L02_DEPTH = "market.btcjpy.depth.step0"
private const val L02_GRACE_MS = 3000

/** 訂閱管理者：時間（毫秒）從參數傳進來 */
private interface L02TopicManager {
    fun subscribe(topic: String, atMs: Int)
    fun unsubscribe(topic: String, atMs: Int)

    /** 時間往前走到 atMs（真的 App 是計時器到期） */
    fun tick(atMs: Int)
}

/** 引用計數（上一個演練寫過，這裡已經寫好） */
private class L02RefCounts {
    private val counts = mutableMapOf<String, Int>()

    /** +1。回傳 true 代表這是第一個用的畫面（從 0 變 1） */
    fun increment(topic: String): Boolean {
        val count = (counts[topic] ?: 0) + 1
        counts[topic] = count
        return count == 1
    }

    /** −1。回傳 true 代表這是最後一個離開的畫面（從 1 變 0） */
    fun decrement(topic: String): Boolean {
        val count = counts[topic] ?: return false
        if (count == 1) {
            counts.remove(topic)
            return true
        }
        counts[topic] = count - 1
        return false
    }
}

/** 演練流程（兩個版本共用） */
private fun scenario(
    log: LabLog,
    makeManager: (D02FakeServer) -> L02TopicManager,
) {
    val server = D02FakeServer()
    val manager = makeManager(server)
    var stillThereOnReturn = false
    for (ms in 0..9000 step 500) {
        server.nowMs = ms
        manager.tick(ms)
        when (ms) {
            0 -> {
                manager.subscribe(L02_DEPTH, ms)
                log("0 ms 進入 BTC 詳細頁")
            }

            1000 -> {
                manager.unsubscribe(L02_DEPTH, ms)
                log("1000 ms 返回列表")
            }

            2000 -> {
                stillThereOnReturn = server.isSubscribed(L02_DEPTH)
                manager.subscribe(L02_DEPTH, ms)
                log(
                    "2000 ms 又進入詳細頁：訂單簿的訂閱${if (stillThereOnReturn) "還在，馬上有資料" else "已經被退訂了，要重新 sub，等第一筆資料到了才有畫面"}",
                )
            }

            4000 -> {
                manager.unsubscribe(L02_DEPTH, ms)
                log("4000 ms 返回列表（之後不再進去）")
            }
        }
    }
    for (frame in server.frames) log("伺服器收到：${frame.atMs} ms ${frame.text}")
    log(
        "最後：sub ${server.subCount} 則、unsub ${server.unsubCount} 則；第 2 次回到詳細頁時訂閱${if (stillThereOnReturn) "還在" else "已經退訂"}；全部離開後伺服器上剩 ${server.activeTopics.size} 個訂閱",
    )
}

// MARK: - Bug 版
private object L02Bug {
    class Manager(private val server: D02FakeServer) : L02TopicManager {
        private val counts = L02RefCounts()

        override fun subscribe(topic: String, atMs: Int) {
            if (counts.increment(topic)) server.sendSub(topic)
        }

        override fun unsubscribe(topic: String, atMs: Int) {
            // 計數變成 0，馬上退訂
            if (counts.decrement(topic)) server.sendUnsub(topic)
        }

        override fun tick(atMs: Int) {}
    }

    suspend fun run(log: LabLog) = scenario(log) { Manager(it) }
}

// MARK: - 修正版
private object L02Fixed {
    class Manager(private val server: D02FakeServer) : L02TopicManager {
        private val counts = L02RefCounts()

        // topic → 這個時間到了才退訂
        private val unsubAt = mutableMapOf<String, Int>()

        override fun subscribe(topic: String, atMs: Int) {
            if (!counts.increment(topic)) return
            // 還在等待退訂：取消退訂，訂閱一直都在
            if (unsubAt.remove(topic) == null) {
                // 沒有在等待：才真的要 sub
                server.sendSub(topic)
            }
        }

        override fun unsubscribe(topic: String, atMs: Int) {
            // 計數變成 0：只記下期限，不馬上退訂
            if (counts.decrement(topic)) unsubAt[topic] = atMs + L02_GRACE_MS
        }

        override fun tick(atMs: Int) {
            // 期限到了，才真的退訂
            for ((topic, due) in unsubAt.toList()) {
                if (due <= atMs) {
                    unsubAt.remove(topic)
                    server.sendUnsub(topic)
                }
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { Manager(it) }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "列表和詳細頁來回切換，訂閱一直重訂",
    question = "第 3 題：一條連線、好幾個畫面，要怎麼共用？",
    goal = "最後一個畫面離開時，不要馬上退訂，等 3 秒再送 unsub；等待期間又有畫面要，就取消退訂。使用者在列表和詳細頁之間來回切換時，訂閱不會一直 sub、unsub，回來也馬上有資料。",
    steps = listOf(
        "選「Bug 版」執行，看伺服器收到的 sub、unsub 一共幾則，以及 2000 ms 回到詳細頁時，訂閱還在不在。",
        "想一想：使用者 1 秒後就回來了，這一次 unsub 加 sub 換到了什麼？（只有多一輪網路來回，以及一段沒有資料的空白。）",
        "選「修正版」執行：從頭到尾只有 1 則 sub、1 則 unsub，unsub 發生在最後一次離開（4000 ms）之後 3000 ms，也就是 7000 ms。",
        "想一想：等待 3 秒的代價是什麼？（多佔 3 秒的訂閱；比起重複訂閱的成本很小。）",
    ),
    expectBug = "訂單簿被 sub 兩次、unsub 兩次，2000 ms 回到詳細頁時訂閱已經不在：「最後：sub 2 則、unsub 2 則；第 2 次回到詳細頁時訂閱已經退訂；全部離開後伺服器上剩 0 個訂閱」。",
    expectFixed = "只有 1 則 sub（0 ms）、1 則 unsub（7000 ms）：「最後：sub 1 則、unsub 1 則；第 2 次回到詳細頁時訂閱還在；全部離開後伺服器上剩 0 個訂閱」。",
    file = "design02-market-data/L02_GracePeriod.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> {
                "演練沒有跑完：要用 scenario(log, makeManager) 執行"
            }

            last.startsWith("最後：sub 0 則") -> {
                "subscribe 還沒送出 sub：計數從 0 變 1 時要 server.sendSub(topic)"
            }

            !last.startsWith("最後：sub 2 則、unsub 2 則；第 2 次回到詳細頁時訂閱已經退訂") -> {
                "Bug 還沒重現：計數變成 0 的時候要馬上 server.sendUnsub(topic)，來回切換就會 sub、unsub 兩輪"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> {
                "演練沒有跑完：要用 scenario(log, makeManager) 執行"
            }

            last.startsWith("最後：sub 0 則") -> {
                "subscribe 還沒送出 sub：計數從 0 變 1、而且不是在等待退訂的時候，要 server.sendSub(topic)"
            }

            !last.startsWith(
                "最後：sub 1 則、unsub 1 則；第 2 次回到詳細頁時訂閱還在；全部離開後伺服器上剩 0 個訂閱",
            ) -> {
                "計數變成 0 時，把 atMs + L02_GRACE_MS 記進 unsubAt，先不退訂；回來時（subscribe）從 unsubAt 取消；tick 發現 due <= atMs 才 sendUnsub"
            }

            "伺服器收到：7000 ms unsub market.btcjpy.depth.step0" !in lines -> {
                "unsub 應該發生在最後一次離開（4000 ms）之後 3000 ms，也就是 7000 ms"
            }

            else -> {
                null
            }
        }
    },
)
