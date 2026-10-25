// L02：為了讓編譯通過硬轉型，手續費混進整數清單，排序時才
// ClassCastException（variance）
// 對應複習站：Kotlin01Types 第 2 題「泛型的 out、in、* 是什麼意思？為什麼
// MutableList<Int> 不能當 MutableList<Number> 用？」
//
// 情境：下單畫面的數量是 MutableList<Int>（單位：張）。要把手續費 0.5
// 加進清單，算「含手續費」的總量。
//
// 你要寫：
//   ① Bug 版（L02Bug）：withFee 的參數是 MutableList<Number>，把手續費 add
// 進去；呼叫時用 as 強制轉型繞過編譯器。
//      跑起來會看到加總默默少算，排序時才 ClassCastException。
//   ② 修正版（L02Fixed）：withFee 只讀，參數改成 List<Number>，回傳新的清單，
// 不用轉型。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin01-types/L02_Variance.kt
package tw.harry.labs.practice.kotlin01types

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

// MARK: - Bug 版
private object L02Bug {
    /** 把手續費加進數量清單 */
    fun withFee(amounts: MutableList<Number>, fee: Double) {
        // TODO ①-1：把 fee 加進 amounts（add）
    }

    @Suppress("UNCHECKED_CAST")
    fun run(log: LabLog) {
        val lots: MutableList<Int> = mutableListOf(3, 1, 2)
        // TODO ①-2：呼叫 withFee(…, 0.5)。編譯器不讓 MutableList<Int> 直接傳進
        // MutableList<Number>，用 as MutableList<Number> 強制轉型
        log("含手續費後的 lots：$lots")
        // 把 0.5 當成 0 加進去，默默少算，沒有任何錯誤
        log("加總：${lots.sum()}")
        try {
            log("由小到大排序：${lots.sorted()}")
        } catch (e: ClassCastException) {
            log("排序時 ClassCastException")
        }
    }
}

// MARK: - 修正版
private object L02Fixed {
    /**
     * 只讀：List<out E> 可以放寬，List<Int> 直接當 List<Number> 傳進來；
     * 回傳新的清單
     */
    // TODO ②：參數改成 List<Number>，回傳一個新的 List<Number>（原清單 +
    // fee），不要修改傳進來的清單
    fun withFee(amounts: List<Number>, fee: Double): List<Number> = amounts

    fun run(log: LabLog) {
        val lots: MutableList<Int> = mutableListOf(3, 1, 2)
        // 不用轉型；lots 本身沒有被動到
        val withFee = withFee(lots, 0.5)
        log("含手續費後的 lots：$withFee")
        log("加總：${lots.sum()}")
        try {
            log("由小到大排序：${lots.sorted()}")
        } catch (e: ClassCastException) {
            log("排序時 ClassCastException")
        }
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "硬轉型後，排序數量時 ClassCastException",
    question = "第 2 題：泛型的 out、in、* 是什麼意思？為什麼 MutableList<Int> 不能當 MutableList<Number> 用？",
    goal = "MutableList<Int> 不能直接傳給 MutableList<Number>。先寫出硬轉型會出的事，再改成只讀的 List<Number>。",
    steps = listOf(
        "打開 practice/kotlin/kotlin01-types/L02_Variance.kt，寫 TODO ①-1、①-2（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「加總」有沒有出錯、「由小到大排序」會怎樣。",
        "寫 TODO ②（修正版），選「修正版」執行，確認不用轉型、lots 沒有被動到。",
        "想一想：如果函式是要「放 Int 進去」的，參數該寫成 MutableList<in Int> 還是 MutableList<out Int>？",
    ),
    expectBug = "含手續費後的 lots 是 [3, 1, 2, 0.5]；加總是 6（0.5 默默變成 0）；排序時印出「排序時 ClassCastException」。",
    expectFixed = "含手續費後是 [3, 1, 2, 0.5]（新的清單）；lots 加總 6，排序成 [1, 2, 3]，沒有例外。",
    file = "kotlin01-types/L02_Variance.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "含手續費後的 lots：[3, 1, 2, 0.5]" !in lines -> "手續費 0.5 沒有被加進 lots：withFee 要對傳進來的清單 add(fee)，呼叫時用 as MutableList<Number> 轉型"
            "加總：6" !in lines -> "加總要是 6：0.5 被當成 0 默默少算"
            "排序時 ClassCastException" !in lines -> "排序沒有出錯：lots 裡要真的混進 Double"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "含手續費後的 lots：[3, 1, 2, 0.5]" !in lines -> "含手續費的清單要是 [3, 1, 2, 0.5]：withFee 回傳 amounts + fee"
            "加總：6" !in lines -> "lots 的加總要是 6"
            "由小到大排序：[1, 2, 3]" !in lines -> "lots 不應該被動到：排序要得到 [1, 2, 3]（withFee 不要修改傳進來的清單）"
            else -> null
        }
    },
)
