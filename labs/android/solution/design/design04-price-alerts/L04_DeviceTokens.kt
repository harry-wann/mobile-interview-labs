// L04：舊手機的 token 失效了，同一個帳號的其他手機也都收不到提醒（逐一推播時，
// 一個失敗就整批中斷）
// 對應複習站：Design04PriceAlerts 第 5 題「有哪些例外情況？」
// （使用者有好幾台裝置、token 失效）
//
// 情境：一個使用者登記了三台裝置的推播 token，順序是：舊手機（已經解除安裝，
// FCM 回報 token 無效）、平板、新手機。
//       價格兩次達到他的提醒條件，伺服器各發一次推播給他所有的裝置。
//       假的 FCM：送給無效的 token 會丟出 unregistered 錯誤（真的 FCM 是
// UNREGISTERED，APNs 是 HTTP 410），其他 token 正常送達。
// Bug 版：用一個迴圈依序送，整個迴圈包在同一個 try/catch 裡。送給舊手機就丟錯，
// 迴圈中斷，平板和新手機一則都沒收到；
//        失效的 token 也沒有刪，下一次又從它開始失敗。
// 修正版：每個 token 各自 try/catch：失敗不影響其他裝置；收到 unregistered
// 就把這個 token 從清單刪掉，下次不再送。
package tw.harry.labs.solution.design04pricealerts

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L04UnregisteredException : Exception("unregistered")

/** 假的 FCM（兩個版本共用） */
private class L04FakeFcm(private val invalid: Set<String>) {
    var attempts = 0
        private set
    private val delivered = mutableMapOf<String, Int>()

    fun send(token: String) {
        attempts += 1
        if (token in invalid) throw L04UnregisteredException()
        delivered[token] = (delivered[token] ?: 0) + 1
    }

    fun deliveredCount(token: String): Int = delivered[token] ?: 0
}

/** 伺服器端：把一則提醒推播給這個使用者登記的所有裝置 */
private interface L04AlertSender {
    val tokens: List<String>
    fun send(alert: String)
}

/** 演練流程（兩個版本共用） */
private fun scenario(
    log: LabLog,
    makeSender: (L04FakeFcm, List<String>) -> L04AlertSender,
) {
    val fcm = L04FakeFcm(invalid = setOf("phone-old"))
    val sender = makeSender(fcm, listOf("phone-old", "tablet", "phone-new"))
    log("登記的 token：${sender.tokens.joinToString("、")}（phone-old 已經失效）")
    for (alert in listOf("BTC 高於 10,000,000 円", "BTC 高於 10,100,000 円")) {
        sender.send(alert)
        log(
            "提醒「$alert」→ 累計嘗試送出 ${fcm.attempts} 次；tablet 收到 ${fcm.deliveredCount(
                "tablet",
            )} 則、phone-new 收到 ${fcm.deliveredCount(
                "phone-new",
            )} 則；token 清單：${sender.tokens.joinToString("、")}",
        )
    }
    log(
        "最後：伺服器共嘗試送出 ${fcm.attempts} 次；tablet 收到 ${fcm.deliveredCount(
            "tablet",
        )} 則、phone-new 收到 ${fcm.deliveredCount(
            "phone-new",
        )} 則；失效的 token ${if ("phone-old" in sender.tokens) "還在清單裡" else "已刪掉"}",
    )
}

// MARK: - Bug 版
private object L04Bug {
    class Sender(private val fcm: L04FakeFcm, initialTokens: List<String>) :
        L04AlertSender {
        override val tokens = initialTokens.toMutableList()

        override fun send(alert: String) {
            try {
                // 整個迴圈包在同一個 try/catch：一個失敗，後面的就都不送了
                for (token in tokens) fcm.send(token)
            } catch (_: L04UnregisteredException) {
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { fcm, tokens ->
        Sender(fcm, tokens)
    }
}

// MARK: - 修正版
private object L04Fixed {
    class Sender(private val fcm: L04FakeFcm, initialTokens: List<String>) :
        L04AlertSender {
        override val tokens = initialTokens.toMutableList()

        override fun send(alert: String) {
            for (token in tokens.toList()) {
                try {
                    // 每個 token 各自 try/catch，失敗不影響其他裝置
                    fcm.send(token)
                } catch (_: L04UnregisteredException) {
                    // token 已經失效：刪掉，下次不再送
                    tokens.remove(token)
                }
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { fcm, tokens ->
        Sender(fcm, tokens)
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "舊手機的 token 失效，其他手機也收不到提醒",
    question = "第 5 題：有哪些例外情況？",
    goal = "一個使用者有好幾台裝置時，要推播給所有還有效的 token：每個 token 的失敗各自處理，不影響其他裝置；APNs／FCM 回報 token 無效時，把它從清單刪掉。",
    steps = listOf(
        "選「Bug 版」執行，看兩則提醒各送了幾次、tablet 和 phone-new 各收到幾則、失效的 token 還在不在清單裡。",
        "想一想：失效的 token 排第一個，後面兩台為什麼都收不到？（丟出的例外跳出了整個迴圈。）",
        "選「修正版」執行：tablet、phone-new 各收到 2 則；失效的 token 第一次失敗後就被刪掉，第二次只送 2 次。",
        "想一想：伺服器怎麼知道 token 失效？（FCM 回 UNREGISTERED 這類錯誤，APNs 回 HTTP 410 或 BadDeviceToken，實際的錯誤碼以官方文件為準。）",
    ),
    expectBug = "第一個 token 失敗就中斷整個迴圈，兩則提醒都只嘗試送給失效的 token，tablet 和 phone-new 一則都沒收到：「最後：伺服器共嘗試送出 2 次；tablet 收到 0 則、phone-new 收到 0 則；失效的 token 還在清單裡」。",
    expectFixed = "第一則送 3 次（舊手機失敗後被刪掉），第二則只送 2 次：「最後：伺服器共嘗試送出 5 次；tablet 收到 2 則、phone-new 收到 2 則；失效的 token 已刪掉」。",
    file = "design04-price-alerts/L04_DeviceTokens.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> {
                "演練沒有跑完：要用 scenario(log, makeSender) 執行"
            }

            last.startsWith("最後：伺服器共嘗試送出 0 次") -> {
                "send 還沒送出：用 for (token in tokens) fcm.send(token) 依序送"
            }

            last !=
                "最後：伺服器共嘗試送出 2 次；tablet 收到 0 則、phone-new 收到 0 則；失效的 token 還在清單裡" -> {
                "Bug 還沒重現：整個 for 迴圈包在同一個 try/catch 裡，送給舊手機失敗就跳出迴圈；而且沒有刪掉失效的 token"
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
                "演練沒有跑完：要用 scenario(log, makeSender) 執行"
            }

            last.startsWith("最後：伺服器共嘗試送出 0 次") -> {
                "send 還沒送出：每個 token 各自 try { fcm.send(token) } catch"
            }

            last !=
                "最後：伺服器共嘗試送出 5 次；tablet 收到 2 則、phone-new 收到 2 則；失效的 token 已刪掉" -> {
                "每個 token 各自 try/catch；catch L04UnregisteredException 時，把這個 token 從 tokens 移除（下一則就不再送給它）"
            }

            else -> {
                null
            }
        }
    },
)
