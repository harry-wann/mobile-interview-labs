// L07：一次斷線，伺服器卻收到三條連線和五個重複的訂閱（每個畫面各自重連）
// 對應複習站：Exchange04Reconnect 第 1 題「為什麼重連要寫成狀態機？狀態和事件有哪些？」（訂閱要有單一來源）
//
// 情境：App 同時有三個畫面開著，各自需要一些 topic：
//        行情列表：market.btcjpy.bbo、market.ethjpy.bbo；K 線：market.btcjpy.kline.1min、market.btcjpy.bbo；下單頁：market.btcjpy.bbo。
//        一共 5 個訂閱需求，其中不同的 topic 只有 3 個。連線斷了，三個畫面都收到「連線關閉」的通知。
// Bug 版：每個畫面收到通知就自己重連，再訂閱自己的 topic。伺服器收到 3 條連線、5 則 sub（market.btcjpy.bbo 重複 3 次，每次推送收到 3 份）。
// 修正版：連線由一個地方負責（單一來源）：已經在重連了就忽略其他畫面的通知；topic 清單合併、去重之後，只訂閱一次。
package tw.harry.labs.solution.exchange04reconnect

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val screens = listOf(
    listOf("market.btcjpy.bbo", "market.ethjpy.bbo"),
    listOf("market.btcjpy.kline.1min", "market.btcjpy.bbo"),
    listOf("market.btcjpy.bbo"),
)

/** 假伺服器：記下收到幾條連線、哪些 sub */
private class FakeServer {
    var connections = 0
        private set
    val subs = mutableListOf<String>()

    fun connect() { connections++ }
    fun sub(topic: String) { subs += topic }
}

/** 演練流程（兩個版本共用）：同一個「連線關閉」通知，依序送給三個畫面 */
private fun scenario(log: LabLog, makeHandler: (List<List<String>>) -> (Int, FakeServer) -> Unit) {
    val server = FakeServer()
    val handler = makeHandler(screens)
    for (index in screens.indices) {
        handler(index, server)
        log("畫面 ${index + 1} 收到連線關閉的通知 → 伺服器累計 ${server.connections} 條連線、${server.subs.size} 則 sub")
    }
    val duplicates = server.subs.size - server.subs.toSet().size
    log("伺服器收到 ${server.connections} 條連線、${server.subs.size} 則 sub（重複的 $duplicates 則）")
}

// MARK: - Bug 版
private object L07Bug {
    fun makeHandler(screens: List<List<String>>): (Int, FakeServer) -> Unit = { index, server ->
        server.connect()                                        // 每個畫面各自重連
        for (topic in screens[index]) server.sub(topic)         // 各自訂閱自己的 topic
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeHandler)
}

// MARK: - 修正版
private object L07Fixed {
    fun makeHandler(screens: List<List<String>>): (Int, FakeServer) -> Unit {
        var reconnecting = false
        val topics = screens.flatten().toSortedSet()            // 單一來源：所有畫面需要的 topic，合併、去重
        return { _, server ->
            if (!reconnecting) {                                // 已經在重連了：忽略其他畫面的通知
                reconnecting = true
                server.connect()
                for (topic in topics) server.sub(topic)         // 只訂閱一次
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeHandler)
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "一次斷線，伺服器收到三條連線和五個訂閱",
    question = "第 1 題：為什麼重連要寫成狀態機？狀態和事件有哪些？",
    goal = "「需要哪些 topic」只記錄在一個地方，重連由一個地方負責。不要讓每個畫面各自重連、各自訂閱。",
    steps = listOf(
        "選「Bug 版」執行，看伺服器累計收到幾條連線、幾則 sub、有幾則是重複的。",
        "想一想：三個畫面都收到同一個「連線關閉」的通知，每個都重連會怎樣？這套交易所的私有 WebSocket 每個 API key 最多 10 條連線。",
        "選「修正版」執行，連線要只有 1 條，sub 只有 3 則（沒有重複）。",
        "進階：畫面離開時要從清單裡移除自己的 topic（引用計數）；真實的 App 會把連線、topic 清單和狀態機放在同一個物件裡。",
    ),
    expectBug = "「伺服器收到 3 條連線、5 則 sub（重複的 2 則）」。",
    expectFixed = "「伺服器收到 1 條連線、3 則 sub（重複的 0 則）」。",
    file = "exchange04-reconnect/L07_ReconnectStorm.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("伺服器收到") } -> "演練沒有跑完：要用 scenario(log, ::makeHandler) 執行"
            "伺服器收到 3 條連線、5 則 sub（重複的 2 則）" !in lines -> "Bug 還沒重現：每個畫面收到通知都要 server.connect()，再訂閱 screens[index] 的每個 topic"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("伺服器收到") } -> "演練沒有跑完：要用 scenario(log, ::makeHandler) 執行"
            "伺服器收到 1 條連線、3 則 sub（重複的 0 則）" !in lines -> "只能連線一次（用旗標忽略後面畫面的通知），topic 清單先合併、去重（toSet），再各訂閱一次"
            else -> null
        }
    },
)
