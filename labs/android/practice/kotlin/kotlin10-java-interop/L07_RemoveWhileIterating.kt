// L07：迴圈裡移除已成交的訂單：電腦上默默漏掉一筆，手機上丟 ConcurrentModificationException（迭代時修改集合）
// 對應複習站：Kotlin10JavaInterop 第 6 題「HashMap 和 ConcurrentHashMap 差在哪？」
//
// 情境：未成交訂單清單有 3 筆：O1（掛單中）、O2（已成交）、O3（已成交）。要把已成交的訂單都從清單移除，應該只剩 O1。
//
// 你要寫：
//   ① Bug 版（L07Bug）：for (order in orders) { if (order.filled) orders.remove(order) }。
//   ② 修正版（L07Fixed）：orders.removeIf { it.filled }。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin10-java-interop/L07_RemoveWhileIterating.kt
package tw.harry.labs.practice.kotlin10javainterop

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L07Order(val id: String, val filled: Boolean)

/** 演練流程（兩個版本共用）：[cleanUp] 把已成交的訂單從清單移除 */
private fun scenario(log: LabLog, cleanUp: (MutableList<L07Order>) -> Unit) {
    val orders = mutableListOf(L07Order("O1", filled = false), L07Order("O2", filled = true), L07Order("O3", filled = true))
    val failure = try {
        cleanUp(orders)
        null
    } catch (e: ConcurrentModificationException) {
        e
    }
    log("清理時丟出 ConcurrentModificationException：${if (failure != null) "是" else "否"}")
    log("清單剩下：${orders.joinToString("、") { it.id }}")
    log("清單裡還留著已成交的訂單：${orders.count { it.filled }} 筆")
}

// MARK: - Bug 版
private object L07Bug {
    fun run(log: LabLog) = scenario(log) { orders ->
        // TODO ①：for (order in orders) 迴圈裡，已成交的訂單直接 orders.remove(order)（迭代中修改清單）
    }
}

// MARK: - 修正版
private object L07Fixed {
    fun run(log: LabLog) = scenario(log) { orders ->
        // TODO ②：用 orders.removeIf { it.filled } 移除已成交的訂單
    }
}

// MARK: - 演練設定
private fun leftover(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("清單裡還留著已成交的訂單：") }?.removePrefix("清單裡還留著已成交的訂單：")?.removeSuffix(" 筆")?.toIntOrNull()

val l07 = Lab(
    id = "L07",
    title = "迴圈裡移除訂單：漏掉一筆，或直接丟例外",
    question = "第 6 題：HashMap 和 ConcurrentHashMap 差在哪？",
    goal = "把清單裡已成交的訂單移除。看在 for 迴圈裡直接 remove 會發生什麼（電腦上的 JVM 漏掉一筆、Android 丟例外），再改用 removeIf。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin10-java-interop/L07_RemoveWhileIterating.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「清理時丟出 ConcurrentModificationException」、「清單剩下」有哪些、「還留著已成交的訂單」有幾筆。",
        "寫 TODO ②（修正版），選「修正版」執行，確認只剩 O1。",
        "想一想：同一段程式，電腦上的 JVM 默默漏掉 O3，Android 卻丟例外。兩邊 ArrayList 迭代器判斷「還有沒有下一個」的方式不同（JVM 比較目前的大小，Android 比較迭代開始時的大小）。所以不要依賴例外來發現這種錯誤。",
    ),
    expectBug = "電腦上的 JVM：「丟出 ConcurrentModificationException：否」、「清單剩下：O1、O3」、「還留著已成交的訂單：1 筆」。Android（模擬器 API 29 實測）：「丟出 ConcurrentModificationException：是」。兩種都是這個 bug。",
    expectFixed = "「清單剩下：O1」，「還留著已成交的訂單：0 筆」。",
    file = "kotlin10-java-interop/L07_RemoveWhileIterating.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "清理時丟出 ConcurrentModificationException：是" in lines -> null     // Android 的 ArrayList 迭代器會丟例外，也是這個 bug
            "清單剩下：O1、O2、O3" in lines -> "沒有移除任何訂單：要在 for (order in orders) 裡，對已成交的訂單 orders.remove(order)"
            (leftover(lines) ?: 0) == 0 -> "已成交的訂單都移除了：Bug 版要在 for 迴圈裡直接 remove"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "清單剩下：O1、O2、O3" in lines -> "沒有移除任何訂單：用 orders.removeIf { it.filled }"
            "清單剩下：O1" !in lines -> "清單剩下的不是 O1：已成交的 O2、O3 都要移除，用 orders.removeIf { it.filled }"
            else -> null
        }
    },
)
