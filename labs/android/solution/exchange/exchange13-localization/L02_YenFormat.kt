// L02：日圓金額顯示成「¥1,234,567.80」，負數變成「¥-1,234.40」（貨幣符號、小數位數、負號位置寫死）
// 對應複習站：Exchange13Localization 第 2 題「數字怎麼顯示？千分位、日圓沒有小數、預設捨入有什麼陷阱？」
//
// 情境：日圓（JPY）的最小單位就是 1 圓，沒有小數位數（Currency.getInstance("JPY").defaultFractionDigits 是 0）；貨幣符號和負號的位置依語系不同。
//        演練把四筆資料交給 formatYen 顯示：①1234567.8（ja-JP）②-1234.4（ja-JP）③1234567.8（en-US）④1234567.8（zh-Hans-CN）。
//        檢查看三件事：數字是不是「整數、有千分位」（日圓沒有小數）、負數的 - 是不是在最前面、符號出現在哪裡不檢查（交給平台依語系決定）。
//        （四捨五入的模式在 Exchange02 的演練 5 練過，這題的資料都避開剛好在中間的值。）
// Bug 版：自己拼字串：寫死 "¥"，數字用固定兩位小數的格式（DecimalFormat("#,##0.00")）。日圓顯示成「¥1,234,567.80」，多了不存在的小數；
//        負數變成「¥-1,234.40」，負號跑到符號後面；其他語系的符號位置（例如簡體中文的 JP¥）也被忽略。
// 修正版：用 NumberFormat.getCurrencyInstance(locale)，currency 設 JPY，locale 用明確傳進來的使用者語系：
//        符號、符號位置、負號交給平台依語系處理；小數位數明確設成 JPY 的 defaultFractionDigits（0）——
//        只設 currency 不會改小數位數（本機實測 en-US 加上 JPY 還是 ¥1,234,567.80）；捨入模式明確設成 HALF_UP（DecimalFormat 預設是 HALF_EVEN）。
package tw.harry.labs.solution.exchange13localization

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** （描述, 金額, 語系, 數字部分應該是什麼（只有數字）, 是不是負數） */
private class L02Case(val name: String, val amount: BigDecimal, val localeTag: String, val digits: String, val negative: Boolean)

private val cases = listOf(
    L02Case("1234567.8，ja-JP", BigDecimal("1234567.8"), "ja-JP", "1234568", false),
    L02Case("-1234.4，ja-JP", BigDecimal("-1234.4"), "ja-JP", "1234", true),
    L02Case("1234567.8，en-US", BigDecimal("1234567.8"), "en-US", "1234568", false),
    L02Case("1234567.8，zh-Hans-CN", BigDecimal("1234567.8"), "zh-Hans-CN", "1234568", false),
)

/** 演練流程（兩個版本共用）：四筆金額逐一顯示，檢查數字、千分位與負號的位置 */
private fun scenario(log: LabLog, formatYen: (BigDecimal, Locale) -> String) {
    var wrong = 0
    for (c in cases) {
        val text = formatYen(c.amount, Locale.forLanguageTag(c.localeTag))
        val shownDigits = text.filter { it in '0'..'9' }
        val negativeOK = if (c.negative) text.firstOrNull() == '-' || text.firstOrNull() == '−' else !text.contains('-')
        val groupingOK = c.digits.length <= 3 || text.contains(',')
        val problems = mutableListOf<String>()
        if (shownDigits != c.digits) problems += "數字應該是 ${c.digits}（日圓沒有小數）"
        if (!negativeOK) problems += "負號要在最前面"
        if (!groupingOK) problems += "要有千分位"
        if (problems.isEmpty()) {
            log("${c.name} → $text ✓")
        } else {
            wrong++
            log("${c.name} → $text ✗（${problems.joinToString("；")}）")
        }
    }
    log("共 4 筆金額，顯示錯的有 $wrong 筆")
}

// MARK: - Bug 版
private object L02Bug {
    fun formatYen(amount: BigDecimal, locale: Locale): String {
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))      // 固定兩位小數：日圓沒有小數
        return "¥" + format.format(amount)                                           // 符號寫死在最前面：負號跑到後面
    }

    suspend fun run(log: LabLog) = scenario(log) { amount, locale -> formatYen(amount, locale) }
}

// MARK: - 修正版
private object L02Fixed {
    fun formatYen(amount: BigDecimal, locale: Locale): String {
        val format = NumberFormat.getCurrencyInstance(locale)         // 明確傳進來的使用者語系，不用 Locale.getDefault()
        val yen = Currency.getInstance("JPY")
        format.currency = yen                                         // 日圓：符號、負號的位置由平台依語系決定
        format.minimumFractionDigits = yen.defaultFractionDigits      // 只設 currency 不會改小數位數：JPY 的 defaultFractionDigits 是 0，要自己套用
        format.maximumFractionDigits = yen.defaultFractionDigits
        format.roundingMode = RoundingMode.HALF_UP                    // 預設是 HALF_EVEN，明確指定
        return format.format(amount)
    }

    suspend fun run(log: LabLog) = scenario(log) { amount, locale -> formatYen(amount, locale) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("共 4 筆金額") }?.let { Regex("(\\d+) 筆$").find(it)?.groupValues?.get(1)?.toInt() }

val l02 = Lab(
    id = "L02",
    title = "日圓顯示成 ¥1,234,567.80，負數的負號在符號後面",
    question = "第 2 題：數字怎麼顯示？千分位、日圓沒有小數、預設捨入有什麼陷阱？",
    goal = "日圓沒有小數位數；貨幣符號與負號的位置依語系不同。不要自己拼字串，用平台的貨幣格式，指定 currency 與明確的使用者語系。",
    steps = listOf(
        "選「Bug 版」執行，看四筆金額顯示成什麼：小數位數、負號的位置各有什麼問題？",
        "想一想：同樣是 1234567.8 圓，ja-JP、en-US、zh-Hans-CN 的符號各是什麼？自己寫死 ¥ 會漏掉什麼？只設 currency、不設小數位數，en-US 的結果是什麼？",
        "選「修正版」執行，四筆都沒有小數、有千分位，負數的 - 在最前面。",
        "進階：DecimalFormat 與 NumberFormat 不能在多執行緒共用；顯示用的字串和送給 API 的字串要分開，API 一律用固定格式（toPlainString()）；加密資產用代號（BTC、ETH），不用符號。",
    ),
    expectBug = "「1234567.8，ja-JP → ¥1,234,567.80 ✗」（多了小數，數字變成 123456780），「-1234.4，ja-JP → ¥-1,234.40 ✗」（負號在符號後面），其他兩筆同樣多了小數：「共 4 筆金額，顯示錯的有 4 筆」。",
    expectFixed = "「￥1,234,568」「-￥1,234」（電腦上 ja-JP 的符號是全形的 ￥，手機上可能是 ¥），簡體中文是「JP¥1,234,568」：「共 4 筆金額，顯示錯的有 0 筆」。",
    file = "exchange13-localization/L02_YenFormat.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { amount, locale -> … } 執行"
            lines.none { it.startsWith("1234567.8，ja-JP → ¥1,234,567.80") } -> "Bug 還沒重現：寫死 \"¥\" 並固定兩位小數，1234567.8 會顯示成 ¥1,234,567.80"
            lines.none { it.startsWith("-1234.4，ja-JP → ¥-1,234.40") } -> "Bug 還沒重現：符號寫死在最前面，負號會跑到後面（¥-1,234.40）"
            n != 4 -> "Bug 版四筆都應該顯示錯（目前錯 $n 筆）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { amount, locale -> … } 執行"
            n != 0 -> "還有 $n 筆顯示錯：用 NumberFormat.getCurrencyInstance 加 currency JPY 與傳進來的 locale，不要自己拼符號和小數位數"
            else -> null
        }
    },
)
