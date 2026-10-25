// L05：登記出金地址時，該填的資料沒填也能過（旅行規則的必填欄位依收款對象不同，
// 空白不算有填）
// 對應複習站：Exchange11eKYC 第 6 題「旅行規則、同意書、風險揭露，對 App
// 有什麼影響？」
//
// 情境：旅行規則（travel rule）要求出金到外部地址時，要收集收款方的資訊。
// 所以登記出金地址的表單，要依「這個地址屬於誰」要求不同的欄位。
//        演練用的是自訂的示範規則（不是實際法規；實際要收集什麼，
// 以法務確認為準）：
//        ・屬於其他交易所：交易所名稱、收款人姓名 都要填
//        ・使用者自己的錢包：要勾選「這是我本人持有的錢包」的所有權聲明
//        ・其他人的錢包：收款人姓名、與收款人的關係 都要填
//        欄位只有空白（空格、換行）也算沒填。validate 回傳「缺少的欄位名稱」，
// 空 list 代表可以送出。
//        六種表單：交易所（完整／缺收款人姓名／交易所名稱只有空白）、
// 自己的錢包（沒勾聲明／有勾）、其他人的錢包（什麼都沒填）。
//
// 你要寫：
//   ① Bug 版（L05Bug.validate）：只有 form.type 是 Exchange 時才檢查：
// exchangeName、beneficiaryName 用 isEmpty() 判斷，空的就加進缺少清單；
// 其他類型不檢查。
//   ② 修正版（L05Fixed.validate）：Exchange 檢查交易所名稱與收款人姓名；
// OwnWallet 檢查 ownershipConfirmed；OtherPerson 檢查收款人姓名與關係。
//      字串欄位用 isBlank() 判斷（空白也算沒填）。缺少的欄位名稱依序是：
// "交易所名稱"、"收款人姓名"、"所有權聲明"、"與收款人的關係"。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange11-ekyc/L05_TravelRule.kt
package tw.harry.labs.practice.exchange11ekyc

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class L05Destination {
    Exchange, // 屬於其他交易所
    OwnWallet, // 使用者自己的錢包
    OtherPerson, // 其他人的錢包
}

private class L05Form(
    val type: L05Destination,
    val exchangeName: String = "",
    val beneficiaryName: String = "",
    val relationship: String = "",
    val ownershipConfirmed: Boolean = false,
)

private class L05Case(
    val name: String,
    val form: L05Form,
    val expected: List<String>,
)

private val cases = listOf(
    L05Case(
        "交易所，資料完整",
        L05Form(
            L05Destination.Exchange,
            exchangeName = "Example Exchange",
            beneficiaryName = "山田 太郎",
        ),
        emptyList(),
    ),
    L05Case(
        "交易所，缺收款人姓名",
        L05Form(L05Destination.Exchange, exchangeName = "Example Exchange"),
        listOf("收款人姓名"),
    ),
    L05Case(
        "交易所，交易所名稱只有空白",
        L05Form(
            L05Destination.Exchange,
            exchangeName = "   ",
            beneficiaryName = "山田 太郎",
        ),
        listOf("交易所名稱"),
    ),
    L05Case(
        "自己的錢包，沒勾所有權聲明",
        L05Form(L05Destination.OwnWallet),
        listOf("所有權聲明"),
    ),
    L05Case(
        "自己的錢包，有勾所有權聲明",
        L05Form(L05Destination.OwnWallet, ownershipConfirmed = true),
        emptyList(),
    ),
    L05Case(
        "其他人的錢包，什麼都沒填",
        L05Form(L05Destination.OtherPerson),
        listOf("收款人姓名", "與收款人的關係"),
    ),
)

/** 演練流程（兩個版本共用）：六種表單逐一驗證，和正確答案比對 */
private fun scenario(log: LabLog, validate: (L05Form) -> List<String>) {
    var wrong = 0
    for (c in cases) {
        val missing = validate(c.form)
        val text = if (missing.isEmpty()) {
            "可以送出"
        } else {
            "缺 " +
                missing.joinToString("、")
        }
        if (missing == c.expected) {
            log("${c.name} → $text ✓")
        } else {
            wrong++
            val expectedText = if (c.expected.isEmpty()) {
                "可以送出"
            } else {
                "缺 " +
                    c.expected.joinToString("、")
            }
            log("${c.name} → $text ✗（應該是：$expectedText）")
        }
    }
    log("6 種表單，判斷錯的有 $wrong 種")
}

// MARK: - Bug 版
private object L05Bug {
    fun validate(form: L05Form): List<String> {
        // TODO ①：只有 form.type == L05Destination.Exchange 才檢查：
        // exchangeName、beneficiaryName 用 isEmpty() 判斷，空的就把
        // "交易所名稱"、"收款人姓名" 加進 missing；
        //        其他類型不檢查。回傳 missing
        return emptyList()
    }

    suspend fun run(log: LabLog) = scenario(log) { validate(it) }
}

// MARK: - 修正版
private object L05Fixed {
    fun validate(form: L05Form): List<String> {
        // TODO ②：用 when (form.type)（不寫 else）分別檢查；字串欄位用
        // isBlank() 判斷（空白也算沒填）：
        //        Exchange → exchangeName、beneficiaryName；OwnWallet →
        // ownershipConfirmed 要是 true；OtherPerson → beneficiaryName、
        // relationship
        //        缺少的欄位名稱："交易所名稱"、"收款人姓名"、"所有權聲明"、
        // "與收款人的關係"
        return emptyList()
    }

    suspend fun run(log: LabLog) = scenario(log) { validate(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("6 種表單")
}?.let { Regex("(\\d+) 種$").find(it)?.groupValues?.get(1)?.toInt() }

val l05 = Lab(
    id = "L05",
    title = "該填的收款人資料沒填也能登記出金地址",
    question = "第 6 題：旅行規則、同意書、風險揭露，對 App 有什麼影響？",
    goal = "旅行規則要收集的資料依收款對象不同，每一種對象都要檢查它該填的欄位；只有空白的字串不算有填。畫面的檢查是為了讓流程順暢，伺服器還是要再驗一次。",
    steps = listOf(
        "打開 practice/exchange/exchange11-ekyc/L05_TravelRule.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看六種表單：哪些不該通過卻通過了？交易所名稱只有空白為什麼也過了？",
        "想一想：「其他人的錢包」是風險最高的一種，Bug 版對它做了什麼檢查？",
        "寫 TODO ②（修正版），選「修正版」執行，六種表單的缺少欄位都要和規則一致。",
        "進階：登記地址的流程還要問「這個地址屬於哪家交易所」；規則細節以法務確認為準，欄位要由伺服器下發，不要寫死在 App。",
    ),
    expectBug = "「交易所，交易所名稱只有空白 → 可以送出 ✗」「自己的錢包，沒勾所有權聲明 → 可以送出 ✗」「其他人的錢包，什麼都沒填 → 可以送出 ✗」：「6 種表單，判斷錯的有 3 種」。",
    expectFixed = "六種表單都符合規則，例如「其他人的錢包，什麼都沒填 → 缺 收款人姓名、與收款人的關係 ✓」：「6 種表單，判斷錯的有 0 種」。",
    file = "exchange11-ekyc/L05_TravelRule.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { form -> … } 執行"
            "交易所，缺收款人姓名 → 缺 收款人姓名 ✓" !in lines -> "Bug 版對「屬於其他交易所」要檢查 exchangeName 與 beneficiaryName（isEmpty 判斷）"
            n != 3 -> "Bug 還沒重現：只檢查交易所、用 isEmpty 判斷，會有 3 種表單判斷錯（目前 $n 種）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { form -> … } 執行"
            n != 0 -> "還有 $n 種表單判斷錯：三種收款對象各有自己要檢查的欄位，字串要去掉前後空白再判斷"
            else -> null
        }
    },
)
