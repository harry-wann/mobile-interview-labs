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
// Bug 版：用 Double 除以單位，乘 100 之後 Math.round 再除以 100.
// 0（常見的「取兩位小數」寫法），去掉尾端的 0：10050 ÷ 10000 = 1.005，
//        在二進位裡 1.005 × 100 = 100.49999999999999，Math.round 得到 100，
// 顯示成「1万」（應該是 1.01万）；
//        99999999 ÷ 10000 = 9999.9999 捨入成 10000.0，顯示「10000万」，
// 沒有升級成 1億；小於 1万的數字沒有千分位（9999）。
//        （Java 的 "%.2f".format(1.005) 反而會得到 1.01：它是用 Double
// 的最短十進位表示再 HALF_UP，和 C 語言的 printf 不一樣；平台之間的行為不同，
// 所以不要靠 Double 的捨入。）
// 修正版：全程用整數運算：先放大 100 倍再整除（加上單位的一半 = half-up），
// 捨入後如果達到下一個單位，就升級；最後把整數部分與兩位小數拼回字串。
package tw.harry.labs.solution.exchange13localization

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
        // 小於最小單位：沒有千分位
        val unit =
            units(language).lastOrNull { value >= it.value }
                ?: return value.toString()
        // Double 運算：1.005 × 100 = 100.49999999999999
        val rounded =
            Math.round(value.toDouble() / unit.value.toDouble() * 100) / 100.0
        // "1.0"、"10000.0"、"1.23"
        var text = rounded.toString()
        // 去掉結尾的 .0
        if (text.endsWith(".0")) text = text.dropLast(2)
        // 沒有檢查捨入後是不是已經到下一個單位
        return text + unit.label
    }

    suspend fun run(log: LabLog) = scenario(log) { value, language ->
        compact(value, language)
    }
}

// MARK: - 修正版
private object L03Fixed {
    fun compact(value: Long, language: String): String {
        val us = units(language)
        var index = us.indexOfLast { value >= it.value }
        // 小於最小單位：不縮寫，有千分位
        if (index < 0) return grouped(value)
        // 兩位小數、half-up，全程整數
        var scaled = (value * 100 + us[index].value / 2) / us[index].value
        // 捨入後達到下一個單位：升級
        while (index + 1 < us.size &&
            scaled >= (us[index + 1].value / us[index].value) * 100
        ) {
            index++
            scaled = (value * 100 + us[index].value / 2) / us[index].value
        }
        // 整數部分
        var text = (scaled / 100).toString()
        val fraction = scaled % 100
        if (fraction != 0L) {
            // 兩位小數，去掉尾端的 0
            text +=
                "." +
                (
                    if (fraction % 10 ==
                        0L
                    ) {
                        (fraction / 10).toString()
                    } else {
                        "%02d".format(fraction)
                    }
                )
        }
        return text + us[index].label
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
        "選「Bug 版」執行，看 11 筆哪些縮寫錯了：10050、99999999、9999 各顯示成什麼？",
        "想一想：1.005 乘 100 用 Double 算是多少？Math.round 為什麼得到 100？99999999 捨入後為什麼不應該顯示成 10000万？",
        "選「修正版」執行，11 筆都要和正確答案一致。",
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
