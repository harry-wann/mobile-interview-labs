// L02：同一個價格，iOS 和 Android 對齊的結果不一樣（共用測試資料與 BigDecimal）
// 對應複習站：Platform08Modules 第 5 題「Kotlin Multiplatform（KMP）
// 是什麼？該共用什麼？有什麼代價？」（價格精度的規則要一致）
//
// 情境：限價單的價格必須是 tick size（最小跳動單位）的整數倍。App
// 要把使用者輸入的價格「往下對齊」到 tick size 的倍數，
//        例如價格 0.57、tick size 0.01，結果還是 0.57。這個規則 iOS、Android、
// 伺服器都要一模一樣。
//        就算不導入 KMP，至少兩個平台、伺服器共用同一份測試資料（下面的
// VECTORS：價格、tick size、標準答案），各自跑一次，不一致就是 bug。
//        （演練把測試資料直接寫在檔案裡，iOS 與 Android 的文字一模一樣；
// 真的專案會放成一份 JSON 檔，兩邊都讀。）
// Bug 版：用 Double 計算：floor(價格 ÷ tick size) × tick size。0.57 ÷ 0.01 在
// Double 裡是 56.99999999999999，往下取整變成 56，
//        結果 0.56，使用者明明輸入了正確的價格，卻被悄悄改小一格。
// 修正版：用 BigDecimal 計算（十進位的精確運算）：價格 ÷ tick size 往下取整，
// 再乘回 tick size。
package tw.harry.labs.solution.platform08modules

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 共用測試資料（兩個平台的文字一模一樣）：價格,tick size,標準答案 */
private const val VECTORS = """
0.3,0.1,0.3
0.57,0.01,0.57
4.35,0.05,4.35
19.99,0.01,19.99
0.00012345,0.00000001,0.00012345
0.57,0.05,0.55
15000123,5,15000120
1.1,0.1,1.1
"""

/**
 * 去掉小數尾端多餘的 0（兩個版本共用）："0.30000000" → "0.3"、"15000120.0" →
 * "15000120"
 */
private fun trimmed(text: String): String =
    if ('.' in text) text.trimEnd('0').trimEnd('.') else text

/** 演練流程（兩個版本共用）：逐筆跑共用測試資料 */
private fun scenario(log: LabLog, align: (String, String) -> String) {
    var mismatches = 0
    val rows = VECTORS.trim().lines().map { it.split(",") }
    for ((price, tick, expected) in rows) {
        val result = trimmed(align(price, tick))
        if (result == expected) {
            log("✓ $price 對齊 $tick → $result")
        } else {
            mismatches++
            log("✗ $price 對齊 $tick → $result（標準答案是 $expected）")
        }
    }
    log("共 ${rows.size} 筆共用測試資料，和標準答案不一致 $mismatches 筆")
}

// MARK: - Bug 版
private object L02Bug {
    fun alignToTick(price: String, tick: String): String {
        val p = price.toDoubleOrNull() ?: return ""
        val t = tick.toDoubleOrNull()?.takeIf { it > 0 } ?: return ""
        // Double：0.57 / 0.01 = 56.99999999999999
        val aligned = floor(p / t) * t
        return String.format(java.util.Locale.US, "%.8f", aligned)
    }

    suspend fun run(log: LabLog) = scenario(log, ::alignToTick)
}

// MARK: - 修正版
private object L02Fixed {
    fun alignToTick(price: String, tick: String): String {
        val p = price.toBigDecimalOrNull() ?: return ""
        val t =
            tick.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                ?: return ""
        // BigDecimal：十進位運算，0.57 / 0.01 剛好是 57，往下取整到個位數
        val quotient = p.divide(t, 0, RoundingMode.DOWN)
        return quotient.multiply(t).stripTrailingZeros().toPlainString()
    }

    suspend fun run(log: LabLog) = scenario(log, ::alignToTick)
}

// MARK: - 演練設定
private fun mismatchCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("共 ") && "不一致" in it
}?.let { Regex("(\\d+) 筆$").find(it)?.groupValues?.get(1)?.toInt() }

val l02 = Lab(
    id = "L02",
    title = "同一個價格，iOS 和 Android 對齊的結果不一樣",
    question = "第 5 題：Kotlin Multiplatform（KMP）是什麼？該共用什麼？有什麼代價？",
    goal = "價格對齊 tick size 的規則，兩個平台算出來要一模一樣，而且要和共用測試資料的標準答案一致。價格要用什麼型別計算？",
    steps = listOf(
        "選「Bug 版」執行，看哪些筆和標準答案不一致，不一致的結果比標準答案大還是小？",
        "想一想：Double 能精確表示 0.01 嗎？0.57 ÷ 0.01 算出來是剛好 57 嗎？往下取整會怎樣？",
        "選「修正版」執行，8 筆都要和標準答案一致。",
        "進階：iOS 的演練用同一份測試資料與 Decimal；兩個平台各自都通過，才代表規則真的一致。價格與數量永遠不要用 Double（見 Exchange 區的精度單元）。",
    ),
    expectBug = "「共 8 筆共用測試資料，和標準答案不一致 5 筆」：0.3 變成 0.2、0.57 變成 0.56、4.35 變成 4.3、19.99 變成 19.98、0.00012345 變成 0.00012344，都被悄悄改小一格。",
    expectFixed = "「共 8 筆共用測試資料，和標準答案不一致 0 筆」。",
    file = "platform08-modules/L02_SharedVectors.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = mismatchCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::alignToTick) 執行"
            "✓ 15000123 對齊 5 → 15000120" !in lines -> "Bug 版也要能算整數：先轉成 Double，floor(p / t) * t，再用 String.format(\"%.8f\") 轉回文字"
            n != 5 -> "Bug 還沒重現：用 Double 計算 floor(p / t) * t，應該有 5 筆和標準答案不一致（目前 $n 筆）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = mismatchCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::alignToTick) 執行"

            n == 0 -> null

            else -> "還有 $n 筆和標準答案不一致，例如：${lines.firstOrNull {
                it.startsWith("✗")
            }}。用 toBigDecimalOrNull() 計算，divide(t, 0, RoundingMode.DOWN) 往下取整"
        }
    },
)
