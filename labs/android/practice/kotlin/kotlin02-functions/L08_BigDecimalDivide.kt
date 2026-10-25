// L08：1 BTC 平分給 3 個子帳戶，每個人拿到 0（BigDecimal 的 /）
// 對應複習站：Kotlin02Functions 第 6 題「BigDecimal("1") / BigDecimal("3")
// 為什麼是 0？」
//
// 情境：使用者要把 1 BTC 平均分給 3 個子帳戶，BTC 最小單位是 0.00000001（小數
// 8 位）。
//
// 你要寫：
//   ① Bug 版（L08Bug）：用 total / accounts。Kotlin 的 / 取被除數的小數位數（1
// 的 scale 是 0），結果是 0，而且不會丟例外。
//   ② 修正版（L08Fixed）：用 divide(accounts, 8, RoundingMode.DOWN)，
// 明確指定小數 8 位、無條件捨去。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin02-functions/L08_BigDecimalDivide.kt
package tw.harry.labs.practice.kotlin02functions

import java.math.BigDecimal
import java.math.RoundingMode
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val total = BigDecimal("1")
private val accounts = BigDecimal("3")

/**
 * 分完之後：每個子帳戶拿到多少、三個加起來多少、原帳戶剩下多少（兩個版本共用）
 */
private fun report(log: LabLog, share: BigDecimal) {
    val distributed = share * accounts
    log("每個子帳戶：${share.toPlainString()}")
    log("三個子帳戶合計：${distributed.toPlainString()}")
    log("原帳戶剩下：${(total - distributed).toPlainString()}")
}

// MARK: - Bug 版
private object L08Bug {
    fun run(log: LabLog) {
        // TODO ①：寫 val share = total / accounts（直接用 /，取被除數的 scale）
        val share = total
        report(log, share)
    }
}

// MARK: - 修正版
private object L08Fixed {
    fun run(log: LabLog) {
        // TODO ②：寫 val share = total.divide(accounts, 8, RoundingMode.DOWN)
        // （小數 8 位，無條件捨去）
        val share = total
        report(log, share)
    }
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "1 BTC 平分給 3 個子帳戶，每人拿到 0",
    question = "第 6 題：BigDecimal(\"1\") / BigDecimal(\"3\") 為什麼是 0？",
    goal = "把 1 BTC 平分給 3 個子帳戶。先寫出默默算出 0 的 Bug 版，再明確指定小數位數與捨入方式。",
    steps = listOf(
        "打開 practice/kotlin/kotlin02-functions/L08_BigDecimalDivide.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「每個子帳戶」和「原帳戶剩下」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認每個子帳戶 0.33333333，剩下 0.00000001。",
        "想一想：捨入方式如果改成 RoundingMode.UP，三個子帳戶合計會是多少？（0.99999999 → 1.00000002，分出去的比原本有的還多）",
    ),
    expectBug = "「每個子帳戶：0」「三個子帳戶合計：0」「原帳戶剩下：1」，沒有任何例外。",
    expectFixed = "「每個子帳戶：0.33333333」「三個子帳戶合計：0.99999999」「原帳戶剩下：0.00000001」。",
    file = "kotlin02-functions/L08_BigDecimalDivide.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "每個子帳戶：0" !in lines -> "Bug 版每個子帳戶要拿到 0：直接用 total / accounts"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "每個子帳戶：0" in lines -> "還是 0：不要直接用 /，改用 divide(分母, 8, RoundingMode.DOWN)"
            "每個子帳戶：0.33333333" !in lines -> "每個子帳戶要拿到 0.33333333：小數 8 位"
            "三個子帳戶合計：0.99999999" !in lines -> "要無條件捨去（RoundingMode.DOWN），合計才是 0.99999999，不會多分出去"
            else -> null
        }
    },
)
