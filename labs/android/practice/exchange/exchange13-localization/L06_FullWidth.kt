// L06：日本使用者輸入全形的 １２３．５，數量變成 0（輸入的正規化與嚴格解析）
// 對應複習站：Exchange13Localization 第 6 題「日本使用者輸入全形數字時，
// 要怎麼解析？」
//
// 情境：日文輸入法可能輸入全形的數字與符號，例如 １２３．５。
// 下單的數量欄位要解析成數字。規則：先做 NFKC 正規化（全形轉半形），
//        再用嚴格的格式解析（只接受數字加最多一個小數點），
// 不合法的一律拒絕並顯示錯誤，不可以悄悄採用部分結果，更不可以把失敗當成 0。
//        parse 回傳解析出的數字字串，拒絕時回傳 null。八筆輸入：１２３．５、
// 123.5、０．００１２、1,234.5abc、−5（U+2212 的負號）、空字串、1
// 000（中間有空白）、12.5.3。
//        （各個內建函式的行為不一致：本機 JDK 21 實測，NumberFormat.
// getInstance(Locale.JAPAN).parse("１２３．５") 得到 123，
// 在全形小數點處悄悄截斷；
//         parse("1,234.5abc") 得到 1234.5，忽略尾端的垃圾；
// 這正是要自己先正規化、再嚴格驗證的原因。Android 的行為要另外實測。）
//
// 你要寫：
//   ① Bug 版（L06Bug.parse）：回傳 (text.toBigDecimalOrNull() ?: BigDecimal.
// ZERO).toPlainString()。
//   ② 修正版（L06Fixed.parse）：text 先 trim，做 NFKC 正規化（java.text.
// Normalizer.normalize(…, Normalizer.Form.NFKC)），
//      用 Regex("[0-9]+(\\.[0-9]+)?").matches(normalized) 檢查，不符合回傳
// null；符合就回傳 BigDecimal(normalized).toPlainString()。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange13-localization/L06_FullWidth.kt
package tw.harry.labs.practice.exchange13localization

import java.math.BigDecimal
import java.text.Normalizer
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** （描述, 使用者輸入, 正確的結果；null 代表要拒絕） */
private class L06Case(
    val name: String,
    val input: String,
    val expected: String?,
)

private val cases = listOf(
    L06Case("全形的 123.5", "１２３．５", "123.5"),
    L06Case("半形的 123.5", "123.5", "123.5"),
    L06Case("全形的 0.0012", "０．００１２", "0.0012"),
    L06Case("1,234.5abc（後面有垃圾）", "1,234.5abc", null),
    L06Case("負號 U+2212", "−5", null),
    L06Case("空字串", "", null),
    L06Case("中間有空白", "1 000", null),
    L06Case("兩個小數點", "12.5.3", null),
)

/** 演練流程（兩個版本共用）：八筆輸入逐一解析，和正確答案比對 */
private fun scenario(log: LabLog, parse: (String) -> String?) {
    var wrong = 0
    for (c in cases) {
        val result = parse(c.input)
        val shown = result ?: "拒絕"
        if (result == c.expected) {
            log("${c.name} → $shown ✓")
        } else {
            wrong++
            log("${c.name} → $shown ✗（應該是 ${c.expected ?: "拒絕"}）")
        }
    }
    log("共 8 筆輸入，解析結果錯的有 $wrong 筆")
}

// MARK: - Bug 版
private object L06Bug {
    fun parse(text: String): String? {
        // TODO ①：回傳 (text.toBigDecimalOrNull() ?: BigDecimal.ZERO).
        // toPlainString()
        return null
    }

    suspend fun run(log: LabLog) = scenario(log) { parse(it) }
}

// MARK: - 修正版
private object L06Fixed {
    fun parse(text: String): String? {
        // TODO ②：normalized = text.trim() 做 NFKC 正規化（Normalizer.
        // normalize(…, Normalizer.Form.NFKC)）；
        //        Regex("[0-9]+(\\.[0-9]+)?").matches(normalized) 不成立就回傳
        // null；成立就回傳 BigDecimal(normalized).toPlainString()
        return null
    }

    suspend fun run(log: LabLog) = scenario(log) { parse(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("共 8 筆")
}?.let { Regex("(\\d+) 筆$").find(it)?.groupValues?.get(1)?.toInt() }

val l06 = Lab(
    id = "L06",
    title = "全形輸入 １２３．５ 變成 0，不合法的輸入被悄悄採用",
    question = "第 6 題：日本使用者輸入全形數字時，要怎麼解析？",
    goal = "數量欄位先 NFKC 正規化（全形轉半形），再用嚴格的格式解析；不合法就拒絕並顯示錯誤，不可以悄悄採用部分結果，更不可以把失敗當成 0。",
    steps = listOf(
        "打開 practice/exchange/exchange13-localization/L06_FullWidth.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看八筆輸入哪些解析錯了：全形的數字變成多少？不合法的輸入變成多少？",
        "想一想：數量變成 0 時，使用者按下買入會發生什麼事？",
        "寫 TODO ②（修正版），選「修正版」執行，八筆都要和正確答案一致（全形要解出來，其餘不合法的要拒絕）。",
        "進階：負號 −（U+2212）和長音符 ー 不會被 NFKC 轉成 -，交易所的數量與價格欄位不需要負號，直接拒絕；貼上的字串可能有千分位與不換行空白（NBSP），先定義清楚接受哪些格式，其餘一律當錯誤。",
    ),
    expectBug = "「全形的 123.5 → 0 ✗」「全形的 0.0012 → 0 ✗」（解析失敗被當成 0），「1,234.5abc」「負號 U+2212」「空字串」「中間有空白」「兩個小數點」也都被悄悄變成 0 ✗（應該拒絕）：「共 8 筆輸入，解析結果錯的有 7 筆」（只有半形的 123.5 是對的）。iOS 的 Decimal(string:) 對 1,234.5abc 等前面像數字的字串則是解成 1、12.5 這類部分結果。",
    expectFixed = "全形的 １２３．５ 解出 123.5，０．００１２ 解出 0.0012，其餘不合法的都「拒絕」：「共 8 筆輸入，解析結果錯的有 0 筆」。",
    file = "exchange13-localization/L06_FullWidth.kt",
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
            "半形的 123.5 → 123.5 ✓" !in lines -> "Bug 版對半形的 123.5 要解對（toBigDecimalOrNull 認得）"
            "全形的 123.5 → 0 ✗（應該是 123.5）" !in lines -> "Bug 還沒重現：BigDecimal 不認得全形的小數點，回傳 null，再 ?: 0 就變成 0"
            n < 6 -> "Bug 版應該有 7 筆解析錯（目前 $n 筆）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { parse(it) } 執行"
            n != 0 -> "還有 $n 筆解析錯：先 trim 與 NFKC，再用 [0-9]+(\\.[0-9]+)? 嚴格檢查，不符合回傳 null"
            else -> null
        }
    },
)
