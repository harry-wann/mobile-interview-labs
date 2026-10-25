// L01：離開詳細頁，列表上的價格就停了（訂閱沒有引用計數）
// 對應複習站：Design02MarketData 第 3 題「一條連線、好幾個畫面，要怎麼共用？」
//
// 情境：整個 App 只開一條 WebSocket，訂閱管理者替所有畫面送 sub／unsub。
// 兩個畫面需要的 topic 有重疊：
//       行情列表：market.btcjpy.bbo、market.ethjpy.bbo；BTC 詳細頁：market.
// btcjpy.bbo、market.btcjpy.depth.step0。
//       劇本：列表先出現 → 詳細頁出現 → 伺服器推送一次 market.btcjpy.bbo →
// 使用者按返回，詳細頁離開
//       → 伺服器再推送一次 market.btcjpy.bbo（列表還開著，還在看 BTC 價格）→
// 列表也離開。
//       假伺服器的規則：同一個 topic 每被 sub 一次，每次推送就多送一份；unsub
// 會取消這個 topic 的全部訂閱。
// Bug 版：每個畫面要就送 sub、離開就送 unsub，不計數。market.btcjpy.bbo
// 被訂了兩次（每次推送收到 2 份），
//        詳細頁一離開又把它整個退訂，列表明明還開著，卻收不到 BTC 的價格了。
// 修正版：每個 topic 記一個引用計數（有幾個畫面在用）：從 0 變 1 才送 sub，從
// 1 變 0 才送 unsub。
package tw.harry.labs.solution.design02marketdata

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val l01ListTopics = listOf("market.btcjpy.bbo", "market.ethjpy.bbo")
private val l01DetailTopics =
    listOf("market.btcjpy.bbo", "market.btcjpy.depth.step0")

/** 訂閱管理者：畫面出現時呼叫 subscribe，離開時呼叫 unsubscribe */
private interface L01TopicManager {
    fun subscribe(topic: String)
    fun unsubscribe(topic: String)
}

/** 演練流程（兩個版本共用） */
private fun scenario(
    log: LabLog,
    makeManager: (D02FakeServer) -> L01TopicManager,
) {
    val server = D02FakeServer()
    val manager = makeManager(server)
    fun topics() = if (server.activeTopics.isEmpty()) {
        "（沒有）"
    } else {
        server.activeTopics.joinToString(
            "、",
        )
    }

    l01ListTopics.forEach(manager::subscribe)
    log("行情列表出現 → 伺服器上的訂閱：${topics()}（sub 累計 ${server.subCount} 則）")
    l01DetailTopics.forEach(manager::subscribe)
    log("BTC 詳細頁出現 → 伺服器上的訂閱：${topics()}（sub 累計 ${server.subCount} 則）")
    val both = server.push("market.btcjpy.bbo")
    log("伺服器推送 market.btcjpy.bbo（列表和詳細頁都開著）→ App 收到 $both 份")
    l01DetailTopics.forEach(manager::unsubscribe)
    log("使用者按返回，詳細頁離開 → 伺服器上的訂閱：${topics()}（unsub 累計 ${server.unsubCount} 則）")
    val after = server.push("market.btcjpy.bbo")
    log("伺服器推送 market.btcjpy.bbo（只剩列表開著，列表還在看 BTC 價格）→ App 收到 $after 份")
    l01ListTopics.forEach(manager::unsubscribe)
    log("行情列表也離開 → 伺服器上的訂閱：${topics()}（unsub 累計 ${server.unsubCount} 則）")
    log(
        "最後：sub 一共送出 ${server.subCount} 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 $both 份，詳細頁離開後收到 $after 份；全部離開後伺服器上剩 ${server.activeTopics.size} 個訂閱",
    )
}

// MARK: - Bug 版
private object L01Bug {
    class Manager(private val server: D02FakeServer) : L01TopicManager {
        override fun subscribe(topic: String) {
            // 畫面要就訂閱一次，不管別的畫面訂過沒有
            server.sendSub(topic)
        }

        override fun unsubscribe(topic: String) {
            // 畫面離開就退訂，不管別的畫面還在不在用
            server.sendUnsub(topic)
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { Manager(it) }
}

// MARK: - 修正版
private object L01Fixed {
    class Manager(private val server: D02FakeServer) : L01TopicManager {
        // topic → 目前有幾個畫面在用
        private val counts = mutableMapOf<String, Int>()

        override fun subscribe(topic: String) {
            val count = (counts[topic] ?: 0) + 1
            counts[topic] = count
            // 第一個畫面要，才送 sub
            if (count == 1) server.sendSub(topic)
        }

        override fun unsubscribe(topic: String) {
            val count = counts[topic] ?: return
            if (count == 1) {
                counts.remove(topic)
                // 最後一個畫面離開，才送 unsub
                server.sendUnsub(topic)
            } else {
                counts[topic] = count - 1
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { Manager(it) }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "離開詳細頁，列表上的價格就停了",
    question = "第 3 題：一條連線、好幾個畫面，要怎麼共用？",
    goal = "多個畫面共用一條連線時，訂閱管理者替每個 topic 記一個引用計數：從 0 變 1 才送 sub，從 1 變 0 才送 unsub。同一個 topic 只訂一次，畫面還在用的 topic 也不會被別的畫面退訂。",
    steps = listOf(
        "選「Bug 版」執行，看每個畫面出現、離開之後，伺服器上的訂閱剩下什麼。",
        "看兩次推送：兩個畫面都開著時，market.btcjpy.bbo 收到幾份？詳細頁離開之後，列表還在看 BTC 價格，卻收到幾份？",
        "想一想：兩個畫面需要同一個 topic 時，誰有資格送 sub？誰有資格送 unsub？",
        "選「修正版」執行：sub 只送 3 則，詳細頁離開後列表照樣收得到 BTC 價格，全部離開後伺服器上不剩任何訂閱。",
    ),
    expectBug = "sub 送了 4 則（market.btcjpy.bbo 訂了兩次，推送收到 2 份）；詳細頁離開時把 market.btcjpy.bbo 一起退訂，列表收到 0 份：「最後：sub 一共送出 4 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 2 份，詳細頁離開後收到 0 份；全部離開後伺服器上剩 0 個訂閱」。",
    expectFixed = "sub 只送 3 則，推送一直收到 1 份：「最後：sub 一共送出 3 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 1 份，詳細頁離開後收到 1 份；全部離開後伺服器上剩 0 個訂閱」。",
    file = "design02-market-data/L01_RefCount.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> {
                "演練沒有跑完：要用 scenario(log, makeManager) 執行"
            }

            last.startsWith("最後：sub 一共送出 0 則") -> {
                "subscribe 還沒送出 sub：Bug 版每次都 server.sendSub(topic)"
            }

            !last.startsWith(
                "最後：sub 一共送出 4 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 2 份，詳細頁離開後收到 0 份",
            ) -> {
                "Bug 還沒重現：subscribe 每次都送 sub、unsubscribe 每次都送 unsub（不計數），market.btcjpy.bbo 會被訂兩次，又被詳細頁整個退訂"
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

            last.startsWith("最後：sub 一共送出 0 則") -> {
                "subscribe 還沒送出 sub：counts[topic] 從 0 變 1 的時候要 server.sendSub(topic)"
            }

            !last.startsWith(
                "最後：sub 一共送出 3 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 1 份，詳細頁離開後收到 1 份；全部離開後伺服器上剩 0 個訂閱",
            ) -> {
                "用 counts[topic] 記有幾個畫面在用：從 0 變 1 才 sendSub，從 1 變 0 才 sendUnsub（並把 topic 從 counts 移除）"
            }

            else -> {
                null
            }
        }
    },
)
