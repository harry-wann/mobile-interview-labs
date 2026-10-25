// L03：已經有處理中的訂單，卻又下了一張（x?.let { } ?: y 不等於 if-else）
// 對應複習站：Kotlin02Functions 第 2 題「let、run、with、apply、also 怎麼選？」
//
// 情境：使用者在送出訂單後網路很慢，又按了一次下單。系統先找有沒有處理中的訂單：有就沿用，沒有才送出新的。
//        處理中的訂單還沒收到交易所的確認，所以 exchangeId 是 null。
//
// 你要寫：
//   ① Bug 版（L03Bug）：用 pending?.let { it.exchangeId } ?: placeOrder()。let 區塊的結果是 null，?: 也會往右走，重複下單。
//   ② 修正版（L03Fixed）：改用 if (pending != null) … else …，有處理中的訂單就沿用。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin02-functions/L03_LetElvis.kt
package tw.harry.labs.practice.kotlin02functions

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class PendingOrder(val clientId: String, val exchangeId: String?)

// MARK: - Bug 版
private object L03Bug {
    fun submit(label: String, pending: PendingOrder?, log: LabLog): String {
        var placed = 0
        fun placeOrder(): String {
            placed++
            log("送出新訂單")
            return "新訂單"
        }
        // TODO ①：寫 val result = pending?.let { it.exchangeId } ?: placeOrder()
        val result = "（還沒寫）"
        log("$label：結果：$result，共送出 $placed 張新訂單")
        return result
    }
}

// MARK: - 修正版
private object L03Fixed {
    fun submit(label: String, pending: PendingOrder?, log: LabLog): String {
        var placed = 0
        fun placeOrder(): String {
            placed++
            log("送出新訂單")
            return "新訂單"
        }
        // TODO ②：用 if (pending != null) … else … 寫兩條明確的路徑：
        //        有處理中的訂單 → 沿用它的 exchangeId，如果是 null 就用 "處理中（等交易所確認）"，不要下單；沒有 → placeOrder()
        val result = "（還沒寫）"
        log("$label：結果：$result，共送出 $placed 張新訂單")
        return result
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "已經有處理中的訂單，又下了一張",
    question = "第 2 題：let、run、with、apply、also 怎麼選？",
    goal = "使用者重複按下單時，已有處理中的訂單（還沒有交易所編號）就不能再送出新的。先寫出會重複下單的 Bug 版，再修好。",
    steps = listOf(
        "打開 practice/kotlin/kotlin02-functions/L03_LetElvis.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「又按一次」有沒有出現「送出新訂單」、共送出幾張。",
        "寫 TODO ②（修正版），選「修正版」執行，確認第一次正常下單，又按一次時沒有重複下單。",
        "想一想：pending?.let { … } ?: … 什麼時候和 if-else 一樣？（let 區塊的結果一定不是 null 的時候）",
    ),
    expectBug = "「又按一次」也出現「送出新訂單」，那一行是「又按一次：結果：新訂單，共送出 1 張新訂單」：重複下單。",
    expectFixed = "第一次正常送出；「又按一次：結果：處理中（等交易所確認），共送出 0 張新訂單」，沒有重複下單。",
    file = "kotlin02-functions/L03_LetElvis.kt",
    run = { variant, log ->
        val pending = PendingOrder(clientId = "c-001", exchangeId = null)   // 已送出，但交易所還沒確認
        when (variant) {
            Variant.BUG -> {
                L03Bug.submit("第一次按下單", null, log)
                L03Bug.submit("又按一次", pending, log)
            }
            Variant.FIXED -> {
                L03Fixed.submit("第一次按下單", null, log)
                L03Fixed.submit("又按一次", pending, log)
            }
        }
    },
    checkBug = { lines ->
        when {
            "第一次按下單：結果：新訂單，共送出 1 張新訂單" !in lines -> "沒有處理中的訂單時要送出新訂單"
            "又按一次：結果：新訂單，共送出 1 張新訂單" !in lines -> "Bug 版要重複下單：用 pending?.let { it.exchangeId } ?: placeOrder()"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "第一次按下單：結果：新訂單，共送出 1 張新訂單" !in lines -> "沒有處理中的訂單時要送出新訂單"
            "又按一次：結果：新訂單，共送出 1 張新訂單" in lines -> "還是重複下單了：有處理中的訂單就不能 placeOrder()，改用 if (pending != null) … else …"
            "又按一次：結果：處理中（等交易所確認），共送出 0 張新訂單" !in lines -> "有處理中的訂單時，結果要沿用它：exchangeId 是 null 就顯示「處理中（等交易所確認）」"
            else -> null
        }
    },
)
