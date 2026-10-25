// L03：後端欄位型別變了，轉型沒擋住，錯在使用的地方才爆（reified）
// 對應複習站：Kotlin01Types 第 3 題「inline、reified、crossinline、noinline
// 各自解決什麼問題？」
//
// 情境：App 把後端回傳的 JSON 解析成 Map<String, Any?>，再用自己寫的 getAs
// 取出欄位。
//        後端把 amount 從數字 0.5 改成字串 "0.5"（交易所常見：
// 為了避免浮點數誤差，改用字串傳金額）。
//
// 你要寫：
//   ① Bug 版（L03Bug）：寫沒有 reified 的泛型函式 getAs，用 as? T 取值。T
// 在執行時被擦除，沒有真的檢查，
//      字串被當成 Double 傳出來，用到的那一行才 ClassCastException。
//   ② 修正版（L03Fixed）：改成 inline + reified，as? T 真的會檢查，
// 型別不對就回傳 null。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin01-types/L03_Reified.kt
package tw.harry.labs.practice.kotlin01types

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 後端回傳的資料：amount 這次是字串 */
private val response: Map<String, Any?> = mapOf(
    "symbol" to "BTC_JPY",
    "amount" to "0.5",
)

// MARK: - Bug 版
private object L03Bug {
    // TODO ①：寫 fun <T> Map<String, Any?>.getAs(key: String): T?，用
    // this[key] as? T 回傳。
    //        不要加 inline、reified（編譯器會警告 unchecked cast，加
    // @Suppress("UNCHECKED_CAST") 即可）
    fun <T> Map<String, Any?>.getAs(key: String): T? = null

    fun run(log: LabLog) {
        log("symbol = ${response.getAs<String>("symbol")}")
        try {
            // 沒有回傳 null，字串被當成 Double 傳出來
            val amount: Double = response.getAs("amount") ?: 0.0
            log("amount = $amount")
        } catch (e: ClassCastException) {
            log("取 amount 時 ClassCastException")
        }
    }
}

// MARK: - 修正版
private object L03Fixed {
    // TODO ②：改成 inline fun <reified T> Map<String, Any?>.getAs(key:
    // String): T?，一樣用 this[key] as? T
    fun <T> Map<String, Any?>.getAs(key: String): T? = null

    fun run(log: LabLog) {
        log("symbol = ${response.getAs<String>("symbol")}")
        // 型別不對，回傳 null
        val amount: Double? = response.getAs("amount")
        if (amount != null) {
            log("amount = $amount")
        } else {
            // 改成用字串解析，金額用 BigDecimal
            val text = response.getAs<String>("amount")
            log("amount 不是數字，改用字串解析：${text?.let { BigDecimal(it) }}")
        }
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "泛型取值沒檢查，用到才 ClassCastException",
    question = "第 3 題：inline、reified、crossinline、noinline 各自解決什麼問題？",
    goal = "後端把 amount 改成字串。先寫出沒有 reified 的 getAs 擋不住的情況，再用 reified 讓型別不對時回傳 null。",
    steps = listOf(
        "打開 practice/kotlin/kotlin01-types/L03_Reified.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 getAs 有沒有回傳 null、ClassCastException 出現在哪一行。",
        "寫 TODO ②（修正版），選「修正版」執行，確認型別不對時回傳 null，改用字串解析。",
        "想一想：為什麼 reified 一定要搭配 inline？（inline 把函式內容貼進呼叫處，那裡的 T 是明確的型別）",
    ),
    expectBug = "symbol 正常；取 amount 時印出「取 amount 時 ClassCastException」（字串被當成 Double 傳出來）。",
    expectFixed = "getAs<Double> 回傳 null，印出「amount 不是數字，改用字串解析：0.5」。",
    file = "kotlin01-types/L03_Reified.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "symbol = BTC_JPY" !in lines -> "getAs<String>(\"symbol\") 要能取到 BTC_JPY"
            "取 amount 時 ClassCastException" !in lines -> "沒有 ClassCastException：getAs 要寫成沒有 reified 的泛型函式（fun <T> …）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "symbol = BTC_JPY" !in lines -> "getAs<String>(\"symbol\") 要能取到 BTC_JPY"

            lines.any {
                it.contains("ClassCastException")
            } -> "還是 ClassCastException：getAs 要加上 inline 和 reified"

            "amount 不是數字，改用字串解析：0.5" !in lines -> "getAs<Double> 型別不對時要回傳 null，再改用字串解析"

            else -> null
        }
    },
)
