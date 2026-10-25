// L07：1 satoshi 的價格顯示成 1E-8（BigDecimal.toString() 用科學記號）
// 對應複習站：Exchange02Precision 第 5 題「數字要怎麼顯示？」
//
// 情境：行情畫面要顯示三個數字：0.00000001（1 satoshi，BTC 的最小單位）、0.
// 00000015、100.00000000（尾端有多餘的 0，先 stripTrailingZeros 去掉）。
//        畫面上要看到一般的小數，不能出現科學記號。
//
// 你要寫：
//   ① Bug 版（L07Bug.display）：BigDecimal 去掉尾端的 0 之後，用 toString()
// 轉成字串。
//   ② 修正版（L07Fixed.display）：改用 toPlainString()。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange02-precision/L07_ScientificNotation.kt
package tw.harry.labs.practice.exchange02precision

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val values = listOf("0.00000001", "0.00000015", "100.00000000")

/** 演練流程（兩個版本共用）：逐一顯示 */
private fun scenario(log: LabLog, display: (String) -> String) {
    var scientific = 0
    for (text in values) {
        val shown = display(text)
        val isScientific = shown.uppercase().contains("E")
        if (isScientific) scientific++
        log("$text → 畫面顯示 $shown${if (isScientific) "（科學記號）" else ""}")
    }
    log("3 個數字裡，顯示成科學記號的有 $scientific 個")
}

// MARK: - Bug 版
private object L07Bug {
    fun display(text: String): String {
        // TODO ①：BigDecimal(text) 呼叫 stripTrailingZeros() 之後，用
        // toString() 轉成字串回傳
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log, ::display)
}

// MARK: - 修正版
private object L07Fixed {
    fun display(text: String): String {
        // TODO ②：一樣 BigDecimal(text).stripTrailingZeros()，這次用
        // toPlainString() 轉成字串回傳
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log, ::display)
}

// MARK: - 演練設定
private fun scientificCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("3 個數字裡")
}?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l07 = Lab(
    id = "L07",
    title = "1 satoshi 的價格顯示成 1E-8",
    question = "第 5 題：數字要怎麼顯示？",
    goal = "很小的數字在畫面上要顯示成一般的小數，不能出現科學記號。BigDecimal 轉成顯示用的字串要呼叫哪一個函式？先寫出顯示成科學記號的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange02-precision/L07_ScientificNotation.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看三個數字在畫面上變成什麼樣子。",
        "想一想：BigDecimal.toString() 遇到很小的數字、或是 stripTrailingZeros 之後的整數，會用什麼格式？",
        "寫 TODO ②（修正版），選「修正版」執行，三個數字都要是一般的小數。",
        "進階：iOS 的演練是 Double 轉字串會出現 1e-08；Decimal 搭配 FormatStyle 才能指定顯示格式。",
    ),
    expectBug = "「0.00000001 → 畫面顯示 1E-8（科學記號）」「0.00000015 → 畫面顯示 1.5E-7（科學記號）」「100.00000000 → 畫面顯示 1E+2（科學記號）」，「3 個數字裡，顯示成科學記號的有 3 個」。",
    expectFixed = "顯示 0.00000001、0.00000015、100，「3 個數字裡，顯示成科學記號的有 0 個」。",
    file = "exchange02-precision/L07_ScientificNotation.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = scientificCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::display) 執行"
            n != 3 -> "Bug 還沒重現：stripTrailingZeros() 之後直接呼叫 toString()，三個數字都會變成科學記號（目前 $n 個）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = scientificCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::display) 執行"
            n != 0 -> "還有 $n 個數字顯示成科學記號：改用 toPlainString()"
            "0.00000001 → 畫面顯示 0.00000001" !in lines -> "1 satoshi 應該顯示 0.00000001"
            else -> null
        }
    },
)
