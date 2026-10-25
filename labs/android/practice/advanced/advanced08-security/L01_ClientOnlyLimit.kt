// L01：被改過的 App 直接把出金送出去，超過限額也照樣出金（限額只在 App 檢查）
// 對應複習站：Advanced08Security 第 1 題「交易所 App 要保護什麼、防誰？MASVS 是什麼？」（手機上的 App 是不可信的環境）
//
// 情境：每日出金限額是 100 萬日圓。App 在送出前會先檢查金額，超過就不讓使用者按送出。
//       攻擊者拿到自己的手機，用工具改寫 App，跳過這個檢查，直接把請求送給伺服器（演練用「被改過的 App」代表，
//       伺服器和 App 都是演練裡的假物件，不會連到任何外部服務）。
//
// 你要寫：
//   ① Bug 版：伺服器相信「App 已經檢查過了」，收到什麼金額都受理。被改過的 App 送出 500 萬，伺服器照辦。
//   ② 修正版：伺服器自己再檢查一次：當日累計出金加上這筆，超過限額就拒絕。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced08-security/L01_ClientOnlyLimit.kt
package tw.harry.labs.practice.advanced08security

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val DAILY_LIMIT = 1_000_000

/** 假的伺服器：記錄今天已經受理的出金。withdraw 回傳這筆請求有沒有被受理 */
private class L01FakeServer(private val accepts: (amount: Int, totalSoFar: Int) -> Boolean) {
    var total = 0
        private set

    fun withdraw(amount: Int): Boolean {
        if (!accepts(amount, total)) return false
        total += amount
        return true
    }
}

/** 演練流程（兩個版本共用）：accepts 是各版本的伺服器邏輯（這筆金額, 今天已經出金多少） */
private fun scenario(log: LabLog, accepts: (amount: Int, totalSoFar: Int) -> Boolean) {
    val server = L01FakeServer(accepts)
    fun text(ok: Boolean) = if (ok) "受理" else "拒絕"

    // 正常的 App：送出前自己先檢查限額
    fun normalApp(amount: Int) {
        if (amount > DAILY_LIMIT) {
            log("正常的 App，出金 $amount：App 自己擋下（超過每日限額）")
        } else {
            log("正常的 App，出金 $amount：伺服器${text(server.withdraw(amount))}")
        }
    }
    normalApp(500_000)
    normalApp(5_000_000)

    // 被改過的 App：跳過限額檢查，直接送出
    log("被改過的 App（跳過檢查），出金 5000000：伺服器${text(server.withdraw(5_000_000))}")
    log("伺服器今天累計出金：${server.total}")
}

// MARK: - Bug 版
private object L01Bug {
    /** 伺服器是否受理這筆出金 */
    fun accepts(amount: Int, totalSoFar: Int): Boolean {
        // TODO ①：伺服器相信 App 已經檢查過了，收到什麼金額都受理（回傳 true）
        return false
    }

    suspend fun run(log: LabLog) = scenario(log, ::accepts)
}

// MARK: - 修正版
private object L01Fixed {
    fun accepts(amount: Int, totalSoFar: Int): Boolean {
        // TODO ②：伺服器自己檢查：今天累計出金（totalSoFar）加上這一筆（amount）不能超過 DAILY_LIMIT
        return false
    }

    suspend fun run(log: LabLog) = scenario(log, ::accepts)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "被改過的 App 繞過限額，照樣出金",
    question = "第 1 題：交易所 App 要保護什麼、防誰？MASVS 是什麼？（App 端的檢查不是防線）",
    goal = "限額、授權、風控的最終判斷一定要在伺服器。App 端的檢查只是為了使用者體驗，攻擊者可以把它整段跳過。 先寫出什麼都受理的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced08-security/L01_ClientOnlyLimit.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看被改過的 App 送出 500 萬之後，伺服器的回應與累計出金。",
        "想一想：正常的 App 被擋在「App 自己擋下」，那被改過的 App 是誰來擋？",
        "寫 TODO ②，選「修正版」執行：伺服器自己檢查累計金額，被改過的 App 送出的 500 萬被拒絕。",
        "進階：這就是 MASVS 與縱深防禦的出發點：手機是不可信的環境，App 端的防護只是提高攻擊成本。",
    ),
    expectBug = "「被改過的 App（跳過檢查），出金 5000000：伺服器受理」，「伺服器今天累計出金：5500000」，遠超過 100 萬的限額。",
    expectFixed = "被改過的 App 的 500 萬被伺服器拒絕，「伺服器今天累計出金：500000」（只有正常的那一筆）。",
    file = "advanced08-security/L01_ClientOnlyLimit.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("伺服器今天累計出金") } -> "演練沒有跑完：要用 scenario 執行"
            "伺服器今天累計出金：5500000" !in lines -> "Bug 還沒重現：伺服器要相信 App，受理所有請求（accepts 回傳 true）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("伺服器今天累計出金") } -> "演練沒有跑完：要用 scenario 執行"
            "被改過的 App（跳過檢查），出金 5000000：伺服器拒絕" !in lines -> "被改過的 App 送出 500 萬應該被伺服器拒絕：伺服器要檢查 totalSoFar + amount 不超過 DAILY_LIMIT"
            "伺服器今天累計出金：500000" !in lines -> "伺服器今天累計應該只有正常的那一筆 500000"
            else -> null
        }
    },
)
