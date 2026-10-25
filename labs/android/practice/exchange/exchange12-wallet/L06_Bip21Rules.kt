// L06：掃到的付款連結帶 amount=1e3，欄位被填入 1000 BTC（BIP21 的嚴格解析：
// 指數、req- 參數、重複的參數）
// 對應複習站：Exchange12Wallet 第 4 題「掃描 QR 得到的付款連結（BIP21）
// 要怎麼解析？」
//
// 情境：掃描或貼上的付款連結是不可信的輸入，要嚴格解析。這個單元採用比 BIP21
// 文法更嚴格的自訂規則：
//        ・scheme（bitcoin:）不分大小寫
//        ・amount 的單位是 BTC，只接受「數字，可以有最多 8 位小數」，
// 不能有指數、正負號、千分位
//        ・名稱以 req- 開頭的參數代表「必須理解」，不認識就要拒絕整個連結；
// 其他不認識的參數可以忽略
//        ・同一個參數出現兩次是有歧義的，拒絕
//        連結的切分（E12.splitBip21：scheme、地址、query 的原始 key 與 value）
// 已經寫好在 Exchange12Support.kt，
//        parse 要回傳「受理（金額）」或「拒絕」。七個連結：金額 20.3 加
// label／大寫的 BITCOIN: 加金額 0.01／沒有 amount／
//        amount=1e3／amount=1 加 req-pay=x／amount=1&amount=2／amount=1
// 加不認識的 foo=bar（要忽略）。
//
// 你要寫：
//   ① Bug 版（L06Bug.parse）：把 parts.query 轉成 Map（同名的後面蓋掉前面，用
// toMap()），不管 req- 與重複；amount 用 BigDecimal(String)
// 轉換（失敗當作沒有）；
//      回傳 Accepted(amount 的 plain 字串)。
//   ② 修正版（L06Fixed.parse）：query 裡有 key 以 "req-" 開頭 → Rejected；
// 同一個 key 出現第二次 → Rejected；
//      amount 存在時要符合 Regex("^[0-9]+(\\.[0-9]{1,8})?$")，否則 Rejected，
// 通過才用 BigDecimal 轉換；其他參數忽略；回傳 Accepted(amount)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange12-wallet/L06_Bip21Rules.kt
package tw.harry.labs.practice.exchange12wallet

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 受理時的金額用「不帶指數、沒有多餘 0 的字串」表示（BigDecimal 的 equals
 * 會比較 scale，直接比較不方便）
 */
private sealed interface L06Outcome {
    data class Accepted(val amount: String?) : L06Outcome
    data object Rejected : L06Outcome
}

private fun plain(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

private fun text(o: L06Outcome): String = when (o) {
    is L06Outcome.Accepted -> "受理，金額 " + (o.amount?.let { "$it BTC" } ?: "沒有")
    L06Outcome.Rejected -> "拒絕"
}

// 公開的測試地址（比特幣創世區塊）
private const val ADDRESS = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"

private class L06Case(
    val name: String,
    val uri: String,
    val expected: L06Outcome,
)

private val cases = listOf(
    L06Case(
        "金額 20.3 加 label",
        "bitcoin:$ADDRESS?amount=20.3&label=Luke-Jr",
        L06Outcome.Accepted("20.3"),
    ),
    L06Case(
        "大寫的 BITCOIN:",
        "BITCOIN:$ADDRESS?amount=0.01",
        L06Outcome.Accepted("0.01"),
    ),
    L06Case(
        "沒有 amount",
        "bitcoin:$ADDRESS?label=donation",
        L06Outcome.Accepted(null),
    ),
    L06Case("amount=1e3", "bitcoin:$ADDRESS?amount=1e3", L06Outcome.Rejected),
    L06Case(
        "不認識的 req- 參數",
        "bitcoin:$ADDRESS?amount=1&req-pay=x",
        L06Outcome.Rejected,
    ),
    L06Case(
        "amount 出現兩次",
        "bitcoin:$ADDRESS?amount=1&amount=2",
        L06Outcome.Rejected,
    ),
    L06Case(
        "不認識的一般參數（忽略）",
        "bitcoin:$ADDRESS?amount=1&foo=bar",
        L06Outcome.Accepted("1"),
    ),
)

/** 演練流程（兩個版本共用）：7 個連結逐一解析，和正確答案比對 */
private fun scenario(log: LabLog, parse: (E12.Bip21Parts) -> L06Outcome) {
    var wrong = 0
    for (c in cases) {
        val parts = E12.splitBip21(c.uri)
        if (parts == null) {
            wrong++
            log("${c.name} → 連結切分失敗")
            continue
        }
        val result = parse(parts)
        if (result == c.expected) {
            log("${c.name} → ${text(result)} ✓")
        } else {
            wrong++
            log("${c.name} → ${text(result)} ✗（應該是：${text(c.expected)}）")
        }
    }
    log("共 7 個連結，解析結果錯的有 $wrong 個")
}

// MARK: - Bug 版
private object L06Bug {
    fun parse(parts: E12.Bip21Parts): L06Outcome {
        // TODO ①：parts.query.toMap()（同名的後面蓋掉前面），不管 req-
        // 與重複的參數；map["amount"] 用 BigDecimal(it) 轉換（失敗就是 null）；
        //        回傳 L06Outcome.Accepted(amount 轉成 plain(it) 字串，沒有就是
        // null)
        return L06Outcome.Rejected
    }

    suspend fun run(log: LabLog) = scenario(log) { parse(it) }
}

// MARK: - 修正版
private object L06Fixed {
    fun parse(parts: E12.Bip21Parts): L06Outcome {
        // TODO ②：parts.query 裡有 key 以 "req-" 開頭 → 回傳 L06Outcome.
        // Rejected；同一個 key 出現第二次 → 回傳 L06Outcome.Rejected；
        //        amount 存在時要符合 Regex("^[0-9]+(\\.[0-9]{1,8})?$")，不符合
        // → Rejected；符合就 Accepted(plain(BigDecimal(raw)))；沒有 amount
        // 回傳 Accepted(null)
        return L06Outcome.Rejected
    }

    suspend fun run(log: LabLog) = scenario(log) { parse(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("共 7 個連結")
}?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l06 = Lab(
    id = "L06",
    title = "掃到 amount=1e3，欄位被填入 1000 BTC",
    question = "第 4 題：掃描 QR 得到的付款連結（BIP21）要怎麼解析？",
    goal = "付款連結是不可信的輸入：amount 只接受數字加最多 8 位小數（不接受指數），不認識的 req- 參數與重複的參數要拒絕整個連結。",
    steps = listOf(
        "打開 practice/exchange/exchange12-wallet/L06_Bip21Rules.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 7 個連結哪些解析錯了：amount=1e3 變成多少 BTC？req- 的參數呢？",
        "想一想：為什麼 BigDecimal(\"1e3\") 不會丟例外？這個值如果直接填進出金欄位，使用者看得出來嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，7 個連結都要和正確答案一致。",
        "進階：Decimal(string:) 與 BigDecimal 都認得科學記號，它們解決的是 Double 的精度問題，不負責「格式驗證」；格式要先檢查，再轉換。",
    ),
    expectBug = "「amount=1e3 → 受理，金額 1000 BTC ✗」「不認識的 req- 參數 → 受理 ✗」「amount 出現兩次 → 受理，金額 2 BTC ✗」，其他四個剛好對：「共 7 個連結，解析結果錯的有 3 個」。",
    expectFixed = "7 個連結都和正確答案一致（1e3、req-、重複的 amount 都是「拒絕」，不認識的 foo=bar 被忽略）：「共 7 個連結，解析結果錯的有 0 個」。",
    file = "exchange12-wallet/L06_Bip21Rules.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { parse(it) } 執行"
            "金額 20.3 加 label → 受理，金額 20.3 BTC ✓" !in lines -> "Bug 版也要能解出正常的連結：amount=20.3 → 受理，金額 20.3 BTC"
            "amount=1e3 → 受理，金額 1000 BTC ✗（應該是：拒絕）" !in lines -> "Bug 還沒重現：BigDecimal(\"1e3\") 會變成 1000，沒有先檢查格式就會填進 1000 BTC"
            n != 3 -> "Bug 版應該有 3 個連結解析錯（目前 $n 個）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { parse(it) } 執行"
            n != 0 -> "還有 $n 個連結解析錯：req- 與重複的參數要拒絕；amount 先用正規表示式檢查格式，通過才轉成 BigDecimal"
            else -> null
        }
    },
)
