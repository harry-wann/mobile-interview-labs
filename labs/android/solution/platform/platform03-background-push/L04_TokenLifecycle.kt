// L04：登出之後還收到前一個帳號的提醒，換了 token 之後卻收不到提醒（推播 token 的管理）
// 對應複習站：Platform03BackgroundPush 第 4 題「推播怎麼從伺服器送到手機？token 是什麼？」
//
// 情境：推播 token 是 FCM 給「這支手機上這個 App」的地址（iOS 是 APNs 的 device token），伺服器靠它把價格提醒送到手機。
//        伺服器的對照表是「帳號 → 這個帳號登入過的裝置 token」。演練跑兩段：
//        ① App 啟動時系統發了 token T1，帳號 A 登入、登出，帳號 B 登入。看 A 的價格提醒還會不會送到這支手機。
//        ② 帳號 B 登入之後，系統因為重新安裝／還原備份換了新的 token T2。看 B 的價格提醒會送到哪一個 token。
//        系統通知 App 的時機：Android 是 FirebaseMessagingService.onNewToken，iOS 是 didRegisterForRemoteNotificationsWithDeviceToken。
// Bug 版：登入時把手上的 token 上傳一次，之後 token 換新不處理，登出也不註銷。
//        結果：已登出的帳號 A 還會收到提醒（個資外洩）；換新 token 之後，伺服器手上還是舊的 T1，提醒送不到這支手機。
// 修正版：拿到 token 就存起來，已登入的話立刻上傳並註銷舊的；登入時綁定帳號；登出時註銷這支手機的 token。
package tw.harry.labs.solution.platform03backgroundpush

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的推播伺服器（兩個版本共用） */
private class L04PushServer {
    private val tokensByUser = mutableMapOf<String, MutableSet<String>>()

    fun register(user: String, token: String) { tokensByUser.getOrPut(user) { mutableSetOf() } += token }
    fun unregister(user: String, token: String) { tokensByUser[user]?.remove(token) }

    /** 價格提醒要送給某個帳號：會送到哪些 token */
    fun deliver(user: String): Set<String> = tokensByUser[user].orEmpty()
}

/** App 這一邊管理推播 token 的物件（兩個版本各自實作） */
private interface L04PushClient {
    fun onNewToken(token: String)          // 系統給了 token（Android：FirebaseMessagingService.onNewToken）
    fun onLogin(user: String)
    fun onLogout()
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, makeClient: (L04PushServer) -> L04PushClient) {
    // ① 帳號 A 登入、登出，帳號 B 登入
    val server1 = L04PushServer()
    val client1 = makeClient(server1)
    client1.onNewToken("T1")
    client1.onLogin("A")
    client1.onLogout()
    client1.onLogin("B")
    log("A 登出、B 登入之後，A 的價格提醒還會送到 ${server1.deliver("A").size} 台裝置")

    // ② 登入之後，系統換了新的 token
    val server2 = L04PushServer()
    val client2 = makeClient(server2)
    client2.onNewToken("T1")
    client2.onLogin("B")
    client2.onNewToken("T2")
    log("token 換新（T1 → T2）之後，B 的價格提醒會送到：${server2.deliver("B").sorted().joinToString("、")}")
}

// MARK: - Bug 版
private object L04Bug {
    class Client(private val server: L04PushServer) : L04PushClient {
        private var token: String? = null

        override fun onNewToken(token: String) {
            this.token = token                         // 只記下來，換新時也不處理
        }

        override fun onLogin(user: String) {
            token?.let { server.register(user, it) }   // 登入時上傳一次
        }

        override fun onLogout() {}                     // 登出什麼都不做
    }

    fun run(log: LabLog) = scenario(log) { Client(it) }
}

// MARK: - 修正版
private object L04Fixed {
    class Client(private val server: L04PushServer) : L04PushClient {
        private var token: String? = null
        private var user: String? = null

        override fun onNewToken(token: String) {
            val old = this.token
            this.token = token
            user?.let { u ->                           // 已登入：換新的 token 要重新上傳，舊的註銷
                old?.let { server.unregister(u, it) }
                server.register(u, token)
            }
        }

        override fun onLogin(user: String) {
            this.user = user
            token?.let { server.register(user, it) }   // 登入後才綁定帳號
        }

        override fun onLogout() {
            val u = user
            val t = token
            if (u != null && t != null) server.unregister(u, t)      // 登出要註銷
            user = null
        }
    }

    fun run(log: LabLog) = scenario(log) { Client(it) }
}

// MARK: - 演練設定
private fun leakedDevices(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("A 登出、B 登入之後，A 的價格提醒還會送到 ") }
        ?.removePrefix("A 登出、B 登入之後，A 的價格提醒還會送到 ")?.removeSuffix(" 台裝置")?.toIntOrNull()

private fun deliveredTokens(lines: List<String>): String? =
    lines.firstOrNull { it.startsWith("token 換新（T1 → T2）之後，B 的價格提醒會送到：") }
        ?.removePrefix("token 換新（T1 → T2）之後，B 的價格提醒會送到：")

val l04 = Lab(
    id = "L04",
    title = "登出後還收到別人的提醒，換 token 後收不到",
    question = "第 4 題：推播怎麼從伺服器送到手機？token 是什麼？",
    goal = "推播 token 要登入後才綁定帳號、換新時重新上傳並註銷舊的、登出時註銷。",
    steps = listOf(
        "選「Bug 版」按執行，看 A 登出之後還會送到幾台裝置，以及 token 換新之後 B 的提醒送到哪個 token。",
        "選「修正版」執行：A 登出後 0 台；換新之後只送到 T2。",
        "想一想：伺服器還要處理什麼？（FCM 回 UNREGISTERED、APNs 回 410 代表 token 已失效，要從資料庫刪掉）",
    ),
    expectBug = "A 登出、B 登入之後，A 的提醒還會送到 1 台裝置；token 換新之後，B 的提醒送到：T1（舊的，這支手機已經收不到）。",
    expectFixed = "A 登出、B 登入之後，A 的提醒送到 0 台裝置；token 換新之後，B 的提醒送到：T2。",
    file = "platform03-background-push/L04_TokenLifecycle.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val leaked = leakedDevices(lines)
        val tokens = deliveredTokens(lines)
        when {
            leaked == null || tokens == null -> "演練沒有跑完"
            leaked == 0 -> "Bug 還沒重現：Bug 版登出時什麼都不做，A 的 token 還留在伺服器"
            tokens != "T1" -> "Bug 還沒重現：Bug 版在 token 換新時不處理，伺服器手上還是舊的 T1"
            else -> null
        }
    },
    checkFixed = { lines ->
        val leaked = leakedDevices(lines)
        val tokens = deliveredTokens(lines)
        when {
            leaked == null || tokens == null -> "演練沒有跑完"
            leaked != 0 -> "A 登出之後，伺服器還會把提醒送到 $leaked 台裝置：onLogout 要呼叫 server.unregister"
            tokens != "T2" -> "token 換新之後，B 的提醒送到「$tokens」：換新時要上傳 T2，並註銷舊的 T1"
            else -> null
        }
    },
)
