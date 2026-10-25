// L06：德文手機下單，數量 0.5 BTC 被伺服器拒絕（送給 API 的字串跟著手機語言變）
// 對應複習站：Exchange02Precision 第 5 題「數字要怎麼顯示？」（送給 API
// 的字串要用固定格式）
//
// 情境：使用者的手機語言是德文（de_DE），小數點是逗號、千分位是句點。App
// 把下單數量轉成字串，送給 API。
//        假伺服器只接受「數字加一個小數點」的格式（不能有千分位，小數點一定是
//        三筆下單：數量 0.5、1234.5、0.00012345。
//
// 你要寫：
//   ① Bug 版（L06Bug.apiString）：NumberFormat.getNumberInstance 用傳進來的
// userLocale。
//   ② 修正版（L06Fixed.apiString）：locale 固定用 Locale.ROOT，並關掉千分位。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange02-precision/L06_LocaleDecimal.kt
package tw.harry.labs.practice.exchange02precision

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 使用者手機的語言（演練把它傳進來，不管電腦或模擬器本身設定成什麼語言，
 * 結果都一樣）
 */
private val userLocale = Locale.GERMANY

/** 假伺服器：只接受「數字加一個小數點」的格式 */
private fun serverAccepts(text: String): Boolean =
    Regex("^[0-9]+(\\.[0-9]+)?$").matches(text)

/** 演練流程（兩個版本共用）：三筆下單，把數量轉成字串送出 */
private fun scenario(log: LabLog, format: (BigDecimal, Locale) -> String) {
    var rejected = 0
    for (text in listOf("0.5", "1234.5", "0.00012345")) {
        val sent = format(e02(text), userLocale)
        if (serverAccepts(sent)) {
            log("數量 $text → 送出字串 \"$sent\"：伺服器受理")
        } else {
            rejected++
            log("數量 $text → 送出字串 \"$sent\"：伺服器拒絕（invalid number）")
        }
    }
    log("3 筆下單，被伺服器拒絕 $rejected 筆")
}

// MARK: - Bug 版
private object L06Bug {
    fun apiString(value: BigDecimal, userLocale: Locale): String {
        // TODO ①：用 NumberFormat.getNumberInstance(userLocale) 建立
        // formatter，maximumFractionDigits 設 8，
        //        用 formatter.format(value) 轉成文字回傳
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log, ::apiString)
}

// MARK: - 修正版
private object L06Fixed {
    fun apiString(value: BigDecimal, userLocale: Locale): String {
        // TODO ②-1：這裡改成固定格式的 Locale（Locale.ROOT）
        val formatter = NumberFormat.getNumberInstance(userLocale)
        // TODO ②-2：關掉千分位（isGroupingUsed）
        formatter.maximumFractionDigits = 8
        return formatter.format(value)
    }

    suspend fun run(log: LabLog) = scenario(log, ::apiString)
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "德文手機下單，數量被伺服器拒絕",
    question = "第 5 題：數字要怎麼顯示？",
    goal = "送給 API 的數字字串要用固定格式（小數點是 .、沒有千分位），不能跟著使用者手機的語言變。先寫出跟著手機語言的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange02-precision/L06_LocaleDecimal.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看送出的字串長什麼樣子、伺服器的反應。",
        "想一想：德文的小數點是什麼符號？千分位是什麼符號？",
        "寫 TODO ②（修正版），選「修正版」執行，三筆都要被受理。",
        "進階：顯示給使用者看的字串才用使用者的語言（見在地化單元）；送給 API、存檔、寫 log 的字串一律用固定格式。String.format 不指定 Locale 時也會用手機的語言。",
    ),
    expectBug = "「數量 0.5 → 送出字串 \"0,5\"」「數量 1234.5 → 送出字串 \"1.234,5\"」…全部被伺服器拒絕，「3 筆下單，被伺服器拒絕 3 筆」。",
    expectFixed = "送出的字串是 \"0.5\"、\"1234.5\"、\"0.00012345\"，「3 筆下單，被伺服器拒絕 0 筆」。",
    file = "exchange02-precision/L06_LocaleDecimal.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("3 筆下單")
            } -> "演練沒有跑完：要用 scenario(log, ::apiString) 執行"

            "數量 0.5 → 送出字串 \"0,5\"：伺服器拒絕（invalid number）" !in lines -> "Bug 還沒重現：NumberFormat.getNumberInstance(userLocale) 用傳進來的 de_DE，小數點會變成逗號"

            "3 筆下單，被伺服器拒絕 3 筆" !in lines -> "三筆都應該被拒絕：1234.5 會變成 \"1.234,5\""

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("3 筆下單")
            } -> "演練沒有跑完：要用 scenario(log, ::apiString) 執行"

            "3 筆下單，被伺服器拒絕 0 筆" !in lines -> "送給 API 的字串：locale 用 Locale.ROOT，並關掉千分位（isGroupingUsed = false）"

            else -> null
        }
    },
)
