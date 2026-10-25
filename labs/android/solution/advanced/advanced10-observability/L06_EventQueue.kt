// L06：事件重複送出，也有送不出去就消失的（沒有本機佇列，也沒有 idempotency key）
// 對應複習站：Advanced10Observability 第 5 題「analytics 事件怎麼設計，才能算出漏斗又不洩漏資料？」
//
// 情境：App 要把下單漏斗的三個事件送到分析服務：order_form_viewed、order_submitted、order_acked。網路狀況依序是：
//       ① 送 order_form_viewed 時：伺服器收到了，但回應在途中遺失，App 看到逾時；
//       ② 送 order_submitted 時：App 沒有網路；
//       ③ 送 order_acked 時：網路恢復正常。最後使用者回到前景，App 再試著送一次。
//       分析服務是假的，它會記下每個事件被算了幾次；如果事件有 eventId，它會自動去除重複（idempotent，同一個 id 只算一次）。
// Bug 版：track 直接送出，失敗就立刻重送一次，還是失敗就丟掉；事件沒有 id。結果 order_form_viewed 被算了 2 次（重複），
//        order_submitted 一次都沒有（遺失）；三個事件總共算到 3 次，數量剛好對，但漏斗是錯的。
// 修正版：事件先放進本機佇列，每個事件有唯一的 eventId；flush 時整批送出，成功才移出佇列，失敗就留著下次再送。
//        重送時伺服器用 eventId 去重，每個事件剛好算 1 次。
package tw.harry.labs.solution.advanced10observability

import java.io.IOException
import java.util.UUID
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L06Event(val name: String, val id: String? = null)

private enum class L06Network { ONLINE, OFFLINE, DROP_RESPONSE_ONCE }

/** 假的分析服務（兩個版本共用） */
private class L06FakeCollector {
    var mode = L06Network.ONLINE
    val counts = mutableMapOf<String, Int>()
    private val seenIds = mutableSetOf<String>()

    @Throws(IOException::class)
    fun send(events: List<L06Event>) {
        when (mode) {
            L06Network.OFFLINE -> throw IOException("沒有網路")
            L06Network.DROP_RESPONSE_ONCE -> {
                receive(events)                                 // 伺服器收到了
                mode = L06Network.ONLINE
                throw IOException("逾時（回應遺失）")              // 但 App 沒收到回應
            }
            L06Network.ONLINE -> receive(events)
        }
    }

    private fun receive(events: List<L06Event>) {
        for (event in events) {
            if (event.id != null && !seenIds.add(event.id)) continue   // 有 id：同一個只算一次
            counts[event.name] = (counts[event.name] ?: 0) + 1
        }
    }
}

private interface L06Tracker {
    fun track(name: String)
    fun flush()
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, makeTracker: (L06FakeCollector) -> L06Tracker) {
    val collector = L06FakeCollector()
    val tracker = makeTracker(collector)

    collector.mode = L06Network.DROP_RESPONSE_ONCE
    tracker.track("order_form_viewed"); tracker.flush()         // ① 伺服器收到，回應遺失
    collector.mode = L06Network.OFFLINE
    tracker.track("order_submitted"); tracker.flush()           // ② 沒有網路
    collector.mode = L06Network.ONLINE
    tracker.track("order_acked"); tracker.flush()               // ③ 網路恢復
    tracker.flush()                                             // 使用者回到前景，再送一次

    val c = collector.counts
    log("伺服器算到：order_form_viewed ×${c["order_form_viewed"] ?: 0}、order_submitted ×${c["order_submitted"] ?: 0}、order_acked ×${c["order_acked"] ?: 0}")
}

// MARK: - Bug 版
private object L06Bug {
    class SimpleTracker(private val collector: L06FakeCollector) : L06Tracker {
        override fun track(name: String) {
            val event = L06Event(name)                          // 沒有 id，伺服器沒辦法去除重複
            try {
                collector.send(listOf(event))
            } catch (e: IOException) {
                runCatching { collector.send(listOf(event)) }   // 失敗就立刻重送一次，還是失敗就丟掉
            }
        }

        override fun flush() {}
    }

    suspend fun run(log: LabLog) = scenario(log, ::SimpleTracker)
}

// MARK: - 修正版
private object L06Fixed {
    class QueuedTracker(private val collector: L06FakeCollector) : L06Tracker {
        private val queue = mutableListOf<L06Event>()           // 真正的 App 會存到檔案或 Room，重新啟動後還在

        override fun track(name: String) {
            queue += L06Event(name, UUID.randomUUID().toString())    // 每個事件有唯一的 eventId
        }

        override fun flush() {
            if (queue.isEmpty()) return
            try {
                collector.send(queue.toList())
                queue.clear()                                   // 成功才移出佇列
            } catch (e: IOException) {
                // 失敗：留在佇列裡，下次 flush 再送（伺服器用 eventId 去重）
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::QueuedTracker)
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "事件算了兩次，又有事件不見了",
    question = "第 5 題：analytics 事件怎麼設計，才能算出漏斗又不洩漏資料？（重送與離線）",
    goal = "事件要先進本機佇列，每個事件有唯一的 id；送出成功才移除，失敗留著重送，伺服器用 id 去重。",
    steps = listOf(
        "選「Bug 版」執行，看伺服器對三個事件各算了幾次：總數對嗎？每個事件對嗎？",
        "想一想：order_form_viewed 的回應遺失了，App 怎麼知道伺服器有沒有收到？重送會發生什麼事？沒有網路時送的事件去哪了？",
        "選「修正版」執行：每個事件剛好算 1 次。",
        "進階：真正的 App 把佇列存到檔案或 Room，App 被終止後還在；可以批次送出以省電（WorkManager），並限制佇列上限與保留期限。",
    ),
    expectBug = "「伺服器算到：order_form_viewed ×2、order_submitted ×0、order_acked ×1」：總共 3 次，但重複了一個、遺失了一個。",
    expectFixed = "「伺服器算到：order_form_viewed ×1、order_submitted ×1、order_acked ×1」。",
    file = "advanced10-observability/L06_EventQueue.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("伺服器算到") } -> "演練沒有跑完：要用 scenario 執行"
            "伺服器算到：order_form_viewed ×2、order_submitted ×0、order_acked ×1" !in lines ->
                "Bug 還沒重現：track 要直接送出，失敗就立刻重送一次、再失敗就丟掉（事件不帶 id）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("伺服器算到") } -> "演練沒有跑完：要用 scenario 執行"
            "伺服器算到：order_form_viewed ×1、order_submitted ×1、order_acked ×1" !in lines ->
                "每個事件都應該剛好算 1 次：事件要有唯一的 id、先放進佇列，flush 成功才清掉，失敗留著"
            else -> null
        }
    },
)
