// L09：連按兩次買入，下了兩張單（先鎖住按鈕，再開始非同步工作）
// 對應複習站：Platform02Threading 第 6 題「行情每秒推幾百筆，執行緒要怎麼設計？」（下單按鈕要立即回饋）
//
// 情境：按下「買入」之後，要先向伺服器取最新報價（約 100 ms），再送出委託。使用者看沒有反應，30 ms 之後又按了一次。
// Bug 版：函式開頭檢查 isSubmitting，但要等取到報價之後才把它設成 true。這 100 ms 之內，第二次按下去看到的還是 false，兩次都送出。
// 修正版：檢查通過之後，在 launch 之前（任何 suspend 函式之前），先同步把 isSubmitting 設成 true（按鈕馬上鎖住、顯示 loading），做完再放開。
package tw.harry.labs.solution.platform02threading

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的伺服器（兩個版本共用）：記下收到幾張委託 */
private class L09Exchange {
    var orders = 0
        private set

    suspend fun fetchQuote(): Int {
        delay(100)
        return 15_000_000
    }

    fun place(@Suppress("UNUSED_PARAMETER") price: Int) {
        orders++
    }
}

/** 演練流程（兩個版本共用）：按下買入，30 ms 後又按一次 */
private suspend fun scenario(log: LabLog, tap: (CoroutineScope, L09Exchange) -> Unit) = coroutineScope {
    val exchange = L09Exchange()
    tap(this, exchange)
    delay(30)
    tap(this, exchange)                                 // 使用者看沒有反應，又按了一次
    delay(300)
    log("連按兩次買入，伺服器收到 ${exchange.orders} 張委託")
}

// MARK: - Bug 版
private object L09Bug {
    var isSubmitting = false

    fun tapBuy(scope: CoroutineScope, exchange: L09Exchange) {
        if (isSubmitting) return
        scope.launch {
            val quote = exchange.fetchQuote()           // 取報價的 100 ms：旗標還是 false，第二次按下去也通過檢查
            isSubmitting = true                         // 太晚才鎖
            exchange.place(quote)
            isSubmitting = false
        }
    }

    suspend fun run(log: LabLog) {
        isSubmitting = false
        scenario(log, ::tapBuy)
    }
}

// MARK: - 修正版
private object L09Fixed {
    var isSubmitting = false

    fun tapBuy(scope: CoroutineScope, exchange: L09Exchange) {
        if (isSubmitting) return
        isSubmitting = true                             // 先鎖住：在任何 suspend 函式之前
        scope.launch {
            try {
                val quote = exchange.fetchQuote()
                exchange.place(quote)
            } finally {
                isSubmitting = false                    // 做完再放開（失敗也要放開）
            }
        }
    }

    suspend fun run(log: LabLog) {
        isSubmitting = false
        scenario(log, ::tapBuy)
    }
}

// MARK: - 演練設定
private fun orders(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("連按兩次買入，伺服器收到 ") }
        ?.removePrefix("連按兩次買入，伺服器收到 ")?.removeSuffix(" 張委託")?.toIntOrNull()

val l09 = Lab(
    id = "L09",
    title = "連按兩次買入，下了兩張單",
    question = "第 6 題：行情每秒推幾百筆，執行緒要怎麼設計？（下單按鈕要立即回饋）",
    goal = "按下買入要立刻鎖住按鈕，再開始非同步工作。連按兩次只能送出一張委託。",
    steps = listOf(
        "選「Bug 版」按執行，看「伺服器收到幾張委託」。",
        "找出旗標 isSubmitting 在哪一行才被設成 true：它前面有一個 suspend 函式。",
        "選「修正版」執行：只收到 1 張委託。",
    ),
    expectBug = "連按兩次買入，伺服器收到 2 張委託。",
    expectFixed = "連按兩次買入，伺服器收到 1 張委託。",
    file = "platform02-threading/L09_DoubleTap.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L09Bug.run(log)
            Variant.FIXED -> L09Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = orders(lines)
        when {
            n == null -> "演練沒有跑完"
            n == 0 -> "伺服器沒有收到委託：tapBuy 要先取報價（exchange.fetchQuote()），再 exchange.place(quote)"
            n < 2 -> "Bug 還沒重現：isSubmitting 要等取到報價之後才設成 true"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = orders(lines)
        when {
            n == null -> "演練沒有跑完"
            n == 0 -> "伺服器沒有收到委託：tapBuy 要先取報價（exchange.fetchQuote()），再 exchange.place(quote)"
            n != 1 -> "伺服器收到 $n 張委託：把 isSubmitting = true 移到 launch 之前（檢查通過之後立刻鎖住）"
            else -> null
        }
    },
)
