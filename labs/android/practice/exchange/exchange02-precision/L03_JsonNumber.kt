// L03：伺服器回 1.234567890123456789 ETH，App 讀進來卻是 1.2345678901234567（JSON 的數字被當成 Double）
// 對應複習站：Exchange02Precision 第 3 題「API 回來的數字要怎麼讀，才不會一開始就不準？」
//
// 情境：資產 API 回傳 JSON，balance 是一個有 18 位小數的數字（ETH 最小單位 wei 是小數第 18 位）：
//        {"symbol":"ethjpy","balance":1.234567890123456789}
//        App 用 Gson 解析成資料類別。Double 只有 15 到 17 位有效數字，超過的位數在讀進來的那一刻就丟了。
//
// 你要寫：
//   ① Bug 版（L03Bug）：Account 的 balance 宣告成 Double，用 Gson 解析。
//   ② 修正版（L03Fixed）：balance 改宣告成 BigDecimal。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange02-precision/L03_JsonNumber.kt
package tw.harry.labs.practice.exchange02precision

import com.google.gson.Gson
import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val JSON = """{"symbol":"ethjpy","balance":1.234567890123456789}"""
private const val ORIGINAL = "1.234567890123456789"

/** 演練流程（兩個版本共用）：解析 JSON，和伺服器的原文比對 */
private fun scenario(log: LabLog, parse: (String) -> String) {
    log("伺服器回傳：$JSON")
    val parsed = parse(JSON)
    log("App 讀到 balance = $parsed")
    log(if (parsed == ORIGINAL) "和伺服器的原文一致" else "和伺服器的原文 $ORIGINAL 不一致，位數被吃掉了")
}

// MARK: - Bug 版
private object L03Bug {
    // TODO ①-1：宣告 class Account(val symbol: String, val balance: ???)，balance 的型別用 Double
    class Account(val symbol: String)

    fun parse(json: String): String {
        // TODO ①-2：用 Gson().fromJson(json, Account::class.java) 解析（失敗回傳 "解析失敗"），成功回傳 "${account.balance}"
        return "解析失敗"
    }

    suspend fun run(log: LabLog) = scenario(log, ::parse)
}

// MARK: - 修正版
private object L03Fixed {
    // TODO ②-1：宣告 class Account(val symbol: String, val balance: ???)，這次 balance 的型別用 BigDecimal
    class Account(val symbol: String)

    fun parse(json: String): String {
        // TODO ②-2：一樣用 Gson().fromJson 解析（失敗回傳 "解析失敗"），成功回傳 account.balance.toPlainString()
        return "解析失敗"
    }

    suspend fun run(log: LabLog) = scenario(log, ::parse)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "伺服器回的位數，讀進來少了幾位",
    question = "第 3 題：API 回來的數字要怎麼讀，才不會一開始就不準？",
    goal = "API 回傳的金額有很多位小數時，解析後的數值要和伺服器的原文一模一樣。資料類別的欄位要用什麼型別？先寫出位數被吃掉的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange02-precision/L03_JsonNumber.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「App 讀到的 balance」和伺服器原文差在哪。",
        "想一想：Double 有幾位有效數字？1.234567890123456789 有幾位？",
        "寫 TODO ②（修正版），選「修正版」執行，位數要完整保留。",
        "進階：如果能改 API，讓伺服器把金額用字串傳最保險，App 用 BigDecimal(String) 建立；Android 內建的 org.json 解析數字時會先變成 Double，位數也會掉。",
    ),
    expectBug = "「App 讀到 balance = 1.2345678901234567」，「和伺服器的原文 1.234567890123456789 不一致，位數被吃掉了」。",
    expectFixed = "「App 讀到 balance = 1.234567890123456789」，「和伺服器的原文一致」。",
    file = "exchange02-precision/L03_JsonNumber.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("App 讀到") } -> "演練沒有跑完：要用 scenario(log, ::parse) 執行"
            "App 讀到 balance = 解析失敗" in lines -> "還沒讀到 balance：Account 要宣告 balance 欄位（Double），再用 Gson().fromJson 解析"
            "App 讀到 balance = 1.2345678901234567" !in lines -> "Bug 還沒重現：balance 宣告成 Double，讀進來會變成 1.2345678901234567"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("App 讀到") } -> "演練沒有跑完：要用 scenario(log, ::parse) 執行"
            "和伺服器的原文一致" !in lines -> "位數還是被吃掉了：Account.balance 要宣告成 BigDecimal，不要經過 Double"
            else -> null
        }
    },
)
