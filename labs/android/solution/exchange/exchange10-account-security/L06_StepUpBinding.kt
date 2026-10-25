// L06：使用者核准的是「出金 0.1 BTC 到自己的地址」，
// 簽名卻能拿去出金到別的地址（step-up 的簽名沒有綁定這一筆操作）
// 對應複習站：Exchange10AccountSecurity 第 6 題「出金這類高風險操作，
// 為什麼要再驗證一次？」
//        （Platform06 的演練 5 練過簽名的「重放」：同一份簽名原封不動重送。
// 這題是另一件事：簽名內容沒有綁定金額與地址，被竄改後仍然有效。）
//
// 情境：出金前要 step-up：伺服器送一個一次性的 challenge（nonce），
// 使用者用生物辨識核准，裝置用金鑰對「簽名內容」簽名，
//        App 把出金請求（nonce、金額、地址、簽名）送給伺服器，
// 伺服器重新組出簽名內容、驗證簽名、檢查 nonce 沒用過。
//        這裡的「簽名內容」怎麼組，是客戶端和伺服器共同的約定，演練用
// signedMessage 表示。
//        （金鑰用 HMAC 代替；真實的 App 是 Keystore 裡的私鑰簽章，
// 伺服器用公鑰驗證。）
//        四個情境，伺服器是同一台（記得用過的 nonce）：
//        ① 使用者核准 0.1 BTC → 地址 A，請求原樣送出 → 應該受理
//        ② 手機被植入惡意程式：使用者核准的是 0.1 BTC → 地址 A，
// 惡意程式把地址換成攻擊者的地址 B 再送出 → 應該拒絕
//        ③ 同樣情況，惡意程式把金額 0.1 改成 5 → 應該拒絕
//        ④ 情境 ① 的請求被原封不動再送一次（重放）→ 應該拒絕（nonce 已經用過）
// Bug 版：簽名內容只有 nonce。簽名證明了「使用者核准過一次出金」，
// 卻沒有證明核准的是哪一筆；請求裡的地址、金額改掉，簽名照樣有效。
// 修正版：簽名內容包含 nonce、金額、地址：
// 伺服器用請求裡實際收到的金額與地址重新組出簽名內容，任何一個欄位被改過，
// 簽名就對不上。
package tw.harry.labs.solution.exchange10accountsecurity

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

// 演練用的假金鑰
private const val DEVICE_KEY = "device-key-test-0001"

// BIP173 的測試地址：使用者自己的地址
private const val ADDRESS_A = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

// 另一個公開的測試地址：代表攻擊者的地址
private const val ADDRESS_B = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

private class L06Request(
    val nonce: String,
    val amount: String,
    val address: String,
    val signature: String,
)

/** 假伺服器：記得用過的 nonce；用 signedMessage 組出簽名內容，驗證簽名 */
private class L06Server(
    private val signedMessage: (
        nonce: String,
        amount: String,
        address: String,
    ) -> String,
) {
    private val used = mutableSetOf<String>()

    /** 回傳 null 代表受理，否則是拒絕的原因 */
    fun withdraw(r: L06Request): String? {
        if (r.nonce in used) return "nonce 已經用過"
        val expected = E10.hmacHex(
            DEVICE_KEY,
            signedMessage(r.nonce, r.amount, r.address),
        )
        if (expected != r.signature) return "簽名不符"
        used += r.nonce
        return null
    }
}

/** 演練流程（兩個版本共用）：四個情境 */
private fun scenario(
    log: LabLog,
    signedMessage: (nonce: String, amount: String, address: String) -> String,
) {
    val server = L06Server(signedMessage)
    var wrong = 0
    var wrongAccepted = 0

    /** 使用者核准（amount → address）之後，惡意程式可能把送出的欄位換掉 */
    fun attempt(
        name: String,
        nonce: String,
        approved: Pair<String, String>,
        sent: Pair<String, String>,
        expectAccepted: Boolean,
    ): L06Request {
        val signature = E10.hmacHex(
            DEVICE_KEY,
            signedMessage(nonce, approved.first, approved.second),
        )
        val request = L06Request(nonce, sent.first, sent.second, signature)
        val reason = server.withdraw(request)
        val accepted = reason == null
        val text = if (accepted) "受理" else "拒絕（$reason）"
        if (accepted == expectAccepted) {
            log("$name：$text ✓")
        } else {
            wrong++
            if (accepted) wrongAccepted++
            log("$name：$text ✗（應該${if (expectAccepted) "受理" else "拒絕"}）")
        }
        return request
    }

    log(
        "簽名內容（核准當下，情境 ①）：${signedMessage(
            "ch-0001",
            "0.1",
            ADDRESS_A,
        ).replace("\n", " / ")}",
    )
    val first = attempt(
        "情境 ① 原樣送出",
        "ch-0001",
        "0.1" to ADDRESS_A,
        "0.1" to ADDRESS_A,
        expectAccepted = true,
    )
    attempt(
        "情境 ② 地址被換成 B",
        "ch-0002",
        "0.1" to ADDRESS_A,
        "0.1" to ADDRESS_B,
        expectAccepted = false,
    )
    attempt(
        "情境 ③ 金額被改成 5",
        "ch-0003",
        "0.1" to ADDRESS_A,
        "5" to ADDRESS_A,
        expectAccepted = false,
    )
    val replayReason = server.withdraw(first)
    if (replayReason != null) {
        log("情境 ④ 重放情境 ① 的請求：拒絕（$replayReason） ✓")
    } else {
        wrong++
        wrongAccepted++
        log("情境 ④ 重放情境 ① 的請求：受理 ✗（應該拒絕）")
    }
    log("4 個情境，判斷錯的有 $wrong 個，其中不該受理卻受理的有 $wrongAccepted 個")
}

// MARK: - Bug 版
private object L06Bug {
    fun signedMessage(nonce: String, amount: String, address: String): String {
        // 只簽 nonce：不知道核准的是哪一筆
        return nonce
    }

    suspend fun run(log: LabLog) = scenario(log) { nonce, amount, address ->
        signedMessage(nonce, amount, address)
    }
}

// MARK: - 修正版
private object L06Fixed {
    fun signedMessage(nonce: String, amount: String, address: String): String {
        // 綁定這一筆：nonce、金額、地址
        return "$nonce\n$amount\n$address"
    }

    suspend fun run(log: LabLog) = scenario(log) { nonce, amount, address ->
        signedMessage(nonce, amount, address)
    }
}

// MARK: - 演練設定
private fun counts(lines: List<String>): Pair<Int, Int>? {
    val line = lines.firstOrNull { it.startsWith("4 個情境") } ?: return null
    val numbers = Regex("\\d+").findAll(line).map { it.value.toInt() }.toList()
    return if (numbers.size >= 3) numbers[1] to numbers[2] else null
}

val l06 = Lab(
    id = "L06",
    title = "核准的是一筆出金，簽名卻能換地址使用",
    question = "第 6 題：出金這類高風險操作，為什麼要再驗證一次？（step-up）",
    goal = "step-up 的簽名內容要綁定這一筆操作（nonce、金額、地址），伺服器用實際收到的欄位重新組出簽名內容驗證。只簽 nonce 的話，攻擊者拿到簽名就能換收款地址。",
    steps = listOf(
        "選「Bug 版」執行，看「簽名內容」裡有什麼；情境 ② ③ 被竄改的請求結果是什麼。",
        "想一想：使用者在畫面上看到並核准的是哪些資訊？簽名有沒有涵蓋它們？",
        "選「修正版」執行，情境 ② ③ 都被拒絕（簽名不符），情境 ① 受理，情境 ④ 因為 nonce 用過而拒絕。",
        "進階：新增出金地址之後，可以加一段冷卻時間，期間限制出金額度；判斷一律在伺服器，客戶端的檢查只是讓流程順暢。",
    ),
    expectBug = "「簽名內容（核准當下，情境 ①）：ch-0001」，情境 ② 地址被換成 B：受理 ✗，情境 ③ 金額被改成 5：受理 ✗（錢轉到攻擊者的地址），情境 ④ 重放因為 nonce 用過而拒絕：「4 個情境，判斷錯的有 2 個，其中不該受理卻受理的有 2 個」。",
    expectFixed = "簽名內容是 ch-0001 / 0.1 / 地址 A；情境 ① 受理，② ③ 拒絕（簽名不符），④ 拒絕（nonce 已經用過），「4 個情境，判斷錯的有 0 個，其中不該受理卻受理的有 0 個」。",
    file = "exchange10-account-security/L06_StepUpBinding.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val c = counts(lines)
        when {
            c == null -> "演練沒有跑完：要用 scenario(log) { nonce, amount, address -> … } 執行"
            "簽名內容（核准當下，情境 ①）：ch-0001" !in lines -> "Bug 版的簽名內容只有 nonce（ch-0001）：不含金額與地址"
            "情境 ① 原樣送出：受理 ✓" !in lines -> "Bug 版的簽名要能通過驗證：合法的請求要受理"
            c.second != 2 || c.first != 2 -> "Bug 還沒重現：簽名內容只有 nonce 時，地址被換掉（情境 ②）、金額被改掉（情境 ③）的請求都會被受理"
            else -> null
        }
    },
    checkFixed = { lines ->
        val c = counts(lines)
        when {
            c == null -> {
                "演練沒有跑完：要用 scenario(log) { nonce, amount, address -> … } 執行"
            }

            "簽名內容（核准當下，情境 ①）：ch-0001 / 0.1 / bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4" !in
                lines -> {
                "簽名內容要包含 nonce、金額、地址（用換行接在一起）"
            }

            c.first != 0 -> {
                "還有 ${c.first} 個情境判斷錯（其中 ${c.second} 個不該受理卻受理）：伺服器用實際收到的金額與地址組簽名內容，被改過就對不上"
            }

            else -> {
                null
            }
        }
    },
)
