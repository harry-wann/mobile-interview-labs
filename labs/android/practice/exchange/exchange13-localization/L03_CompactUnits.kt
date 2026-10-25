// L03：成交量顯示「10000万」、「1万」少了零頭（万／億的縮寫：沒有進位升級，用
// Double 除再捨入）
// 對應複習站：Exchange13Localization 第 3 題「万、億這類大數單位怎麼顯示？」
//
// 情境：產品規格要把市值、成交量縮寫成大數單位：兩位小數、四捨五入（half-up）、
// 去掉尾端的 0、捨入後剛好到下一個單位要進位升級。
//        日文：万（10 的 4 次方）、億（8 次方）、兆（12 次方）；繁體中文：萬、
// 億、兆；簡體中文：万、亿、万亿（12 次方）；英文：K、M、B、T（三位一組）。
//        小於最小單位的數字不縮寫，但要有千分位。演練檢查 11 筆：
//        ja 9999 → 9,999；12345 → 1.23万；10050 → 1.01万；100000000 → 1億；
// 99999999 → 1億（9999.9999万 捨入成 10000万，要升級成 1億）；
//        123456789 → 1.23億；1234567890123 → 1.23兆；en 123456789 → 123.46M；
// zh-Hans 123456789 → 1.23亿；1234567890123 → 1.23万亿；zh-Hant 12345 → 1.
// 23萬。
//        compact 是有損的顯示，只用在市值、成交量這類輔助標籤，
// 不能用在下單數量、確認頁、損益明細。
//
// 你要寫：
//   ① Bug 版（L03Bug.compact）：由大到小找第一個 value >= 單位的單位；用 value.
// toDouble() / 單位.toDouble() 除，乘 100 後 Math.round 再除以 100.0，
//      轉成字串（toString()），去掉結尾的 ".0"，接上單位文字；
// 小於最小單位就直接 value.toString()。
//   ② 修正版（L03Fixed.compact）：整數運算：scaled = (value * 100 + 單位 / 2)
// / 單位（兩位小數、half-up）；
//      如果 scaled 已經達到「下一個單位 ÷ 這個單位 × 100」，
// 就改用下一個單位重算（可以連續升級）；整數部分 = scaled / 100，小數 = scaled
// % 100（去掉尾端的 0）；
//      小於最小單位就用千分位格式（這個檔案裡已經寫好的 grouped）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange13-localization/L03_CompactUnits.kt
package tw.harry.labs.practice.exchange13localization

import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 一個大數單位：數值與顯示文字 */
private class L03Unit(val value: Long, val label: String)

/** 每個語言的單位，由小排到大 */
private fun units(language: String): List<L03Unit> = when (language) {
    "ja" -> listOf(
        L03Unit(10_000, "万"),
        L03Unit(100_000_000, "億"),
        L03Unit(1_000_000_000_000, "兆"),
    )

    "zh-Hant" -> listOf(
        L03Unit(10_000, "萬"),
        L03Unit(100_000_000, "億"),
        L03Unit(1_000_000_000_000, "兆"),
    )

    "zh-Hans" -> listOf(
        L03Unit(10_000, "万"),
        L03Unit(100_000_000, "亿"),
        L03Unit(1_000_000_000_000, "万亿"),
    )

    else -> listOf(
        L03Unit(1_000, "K"),
        L03Unit(1_000_000, "M"),
        L03Unit(1_000_000_000, "B"),
        L03Unit(1_000_000_000_000, "T"),
    )
}

/** 千分位（小於最小單位的數字用） */
private fun grouped(value: Long): String =
    String.format(Locale.US, "%,d", value)

/** （語言, 數值, 正確的結果） */
private class L03Case(
    val language: String,
    val value: Long,
    val expected: String,
)

private val cases = listOf(
    L03Case("ja", 9_999, "9,999"),
    L03Case("ja", 12_345, "1.23万"),
    L03Case("ja", 10_050, "1.01万"),
    L03Case("ja", 100_000_000, "1億"),
    L03Case("ja", 99_999_999, "1億"),
    L03Case("ja", 123_456_789, "1.23億"),
    L03Case("ja", 1_234_567_890_123, "1.23兆"),
    L03Case("en", 123_456_789, "123.46M"),
    L03Case("zh-Hans", 123_456_789, "1.23亿"),
    L03Case("zh-Hans", 1_234_567_890_123, "1.23万亿"),
    L03Case("zh-Hant", 12_345, "1.23萬"),
)

/** 演練流程（兩個版本共用）：11 筆逐一縮寫，和正確答案比對 */
private fun scenario(log: LabLog, compact: (Long, String) -> String) {
    var wrong = 0
    for (c in cases) {
        val result = compact(c.value, c.language)
        if (result == c.expected) {
            log("${c.language} ${c.value} → $result ✓")
        } else {
            wrong++
            log("${c.language} ${c.value} → $result ✗（應該是 ${c.expected}）")
        }
    }
    log("共 11 筆，縮寫錯的有 $wrong 筆")
}

// MARK: - Bug 版
private object L03Bug {
    fun compact(value: Long, language: String): String {
        // TODO ①：由大到小找第一個 value >= it.value 的單位（units(language).
        // lastOrNull），找不到就回傳 value.toString()；
        //        rounded = Math.round(value.toDouble() / unit.value.toDouble()
        // * 100) / 100.0；text = rounded.toString()，結尾是 ".0" 就去掉；回傳
        // text + unit.label
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log) { value, language ->
        compact(value, language)
    }
}

// MARK: - 修正版
private object L03Fixed {
    fun compact(value: Long, language: String): String {
        // TODO ②：us = units(language)；index = us.indexOfLast { value >= it.
        // value }，小於 0 就回傳 grouped(value)；
        //        scaled = (value * 100 + us[index].value / 2) / us[index].
        // value；
        //        while 還有下一個單位，而且 scaled >= (下一個單位的 value /
        // 這個單位的 value) * 100：index 加 1，用新的單位重算 scaled；
        //        text = (scaled / 100).toString()；fraction = scaled % 100，
        // 不是 0 就加上 "." 和小數（兩位，尾端的 0 去掉）；回傳 text +
        // us[index].label
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log) { value, language ->
        compact(value, language)
    }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("共 11 筆")
}?.let { Regex("(\\d+) 筆$").find(it)?.groupValues?.get(1)?.toInt() }

val l03 = Lab(
    id = "L03",
    title = "成交量顯示 10000万，1.005万 少了零頭",
    question = "第 3 題：万、億這類大數單位怎麼顯示？",
    goal = "万／億是四位一組，K／M／B 是三位一組；縮寫要用整數運算（不用 Double），捨入後達到下一個單位要進位升級，小於最小單位的數字要有千分位。",
    steps = listOf(
        "打開 practice/exchange/exchange13-localization/L03_CompactUnits.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 11 筆哪些縮寫錯了：10050、99999999、9999 各顯示成什麼？",
        "想一想：1.005 乘 100 用 Double 算是多少？Math.round 為什麼得到 100？99999999 捨入後為什麼不應該顯示成 10000万？",
        "寫 TODO ②（修正版），選「修正版」執行，11 筆都要和正確答案一致。",
        "進階：平台的 compact formatter（Android 的 CompactDecimalFormat，API 24 起）只保留約兩位有效數字，依 CLDR 版本結果不同；產品要明確規則時，自己實作並用固定輸入的測試固定下來。",
    ),
    expectBug = "「ja 10050 → 1万 ✗」（應該是 1.01万）、「ja 99999999 → 10000万 ✗」（應該升級成 1億）、「ja 9999 → 9999 ✗」（沒有千分位），其他 8 筆剛好對：「共 11 筆，縮寫錯的有 3 筆」。",
    expectFixed = "11 筆都和正確答案一致，例如 1.01万、1億、1.23万亿、123.46M：「共 11 筆，縮寫錯的有 0 筆」。",
    file = "exchange13-localization/L03_CompactUnits.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { value, language -> … } 執行"
            "ja 123456789 → 1.23億 ✓" !in lines -> "Bug 版在一般的數字要算對：123456789 ÷ 1億 = 1.23456789，捨入成 1.23億"
            "ja 10050 → 1万 ✗（應該是 1.01万）" !in lines -> "Bug 還沒重現：用 Double 乘 100 再取整，10050 ÷ 10000 = 1.005 會算成 1.00，顯示 1万"
            "ja 99999999 → 10000万 ✗（應該是 1億）" !in lines -> "Bug 還沒重現：沒有檢查捨入後是不是已經到下一個單位，99999999 會顯示成 10000万"
            n != 3 -> "Bug 版應該有 3 筆縮寫錯（目前 $n 筆）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { value, language -> … } 執行"
            n != 0 -> "還有 $n 筆縮寫錯：用整數運算 (value * 100 + 單位 / 2) / 單位 做 half-up，達到下一個單位要升級，小於最小單位用 grouped"
            else -> null
        }
    },
)
