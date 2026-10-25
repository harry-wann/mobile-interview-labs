// L02：apply 區塊裡設定了數量，送出的訂單數量卻是 0（scope function 與同名變數）
// 對應複習站：Kotlin02Functions 第 2 題「let、run、with、apply、also 怎麼選？」
//
// 情境：下單畫面把輸入框目前的文字存在區域變數 qty，建立 OrderRequest 時用 apply 設定它的欄位。
//
// 你要寫：
//   ① Bug 版（L02Bug）：在 apply { } 裡直接寫 qty = "0.5"。外面有同名的區域變數，被改到的是區域變數，不是 OrderRequest。
//   ② 修正版（L02Fixed）：明確寫 this.qty = "0.5"。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin02-functions/L02_ApplyShadowing.kt
package tw.harry.labs.practice.kotlin02functions

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 下單請求（兩個版本共用） */
private class OrderRequest {
    var symbol = ""
    var qty = "0"
}

// MARK: - Bug 版
private object L02Bug {
    fun run(log: LabLog) {
        var qty = "0"                                      // 輸入框目前的文字（區域變數）
        val request = OrderRequest().apply {
            symbol = "BTC_JPY"                             // 沒有同名的區域變數，這行改的是 OrderRequest.symbol
            // TODO ①：直接寫 qty = "0.5"（不要加 this）。外面有同名的區域變數，Kotlin 會優先用它
        }
        log("送出的 symbol = ${request.symbol}")
        log("送出的 qty = ${request.qty}")
        log("外面的區域變數 qty = $qty")
    }
}

// MARK: - 修正版
private object L02Fixed {
    fun run(log: LabLog) {
        var qty = "0"
        val request = OrderRequest().apply {
            symbol = "BTC_JPY"
            // TODO ②：明確寫 this.qty = "0.5"，指定要設定的是 OrderRequest 的 qty
        }
        log("送出的 symbol = ${request.symbol}")
        log("送出的 qty = ${request.qty}")
        log("外面的區域變數 qty = $qty")
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "apply 設定了數量，送出的數量卻是 0",
    question = "第 2 題：let、run、with、apply、also 怎麼選？",
    goal = "在 apply 裡設定 OrderRequest 的欄位，但外面有同名的區域變數。先寫出被改錯的 Bug 版，再修好。",
    steps = listOf(
        "打開 practice/kotlin/kotlin02-functions/L02_ApplyShadowing.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「送出的 qty」和「外面的區域變數 qty」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認 OrderRequest 拿到 0.5，外面的區域變數沒被動到。",
        "想一想：symbol 那一行為什麼沒有問題？（外面沒有叫 symbol 的區域變數）",
    ),
    expectBug = "「送出的 qty = 0」，而「外面的區域變數 qty = 0.5」：被改到的是區域變數。",
    expectFixed = "「送出的 qty = 0.5」，「外面的區域變數 qty = 0」。",
    file = "kotlin02-functions/L02_ApplyShadowing.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "送出的 symbol = BTC_JPY" !in lines -> "symbol 要用 apply 設定成 BTC_JPY"
            "外面的區域變數 qty = 0.5" !in lines -> "Bug 版 apply 裡要直接寫 qty = \"0.5\"（不加 this），這樣改到的才是外面的區域變數"
            "送出的 qty = 0" !in lines -> "OrderRequest 的 qty 應該還是預設的 0"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "送出的 symbol = BTC_JPY" !in lines -> "symbol 要用 apply 設定成 BTC_JPY"
            "送出的 qty = 0.5" !in lines -> "OrderRequest 的 qty 要是 0.5：apply 裡用 this.qty = \"0.5\""
            "外面的區域變數 qty = 0" !in lines -> "外面的區域變數 qty 不應該被動到"
            else -> null
        }
    },
)
