// L03：覆蓋率 100%、測試全過，但程式被改壞測試還是過（沒有檢查邊界）
// 對應複習站：Testing01Strategy 第 4 題「怎麼知道測試真的有用？」
//
// 情境：單筆出金達到 100 萬日圓，就要再做一次兩步驟驗證。T01Withdrawal.requiresTwoFactor 是被測的函式。
//       mutation testing 的做法：故意把程式改壞（這裡手寫一個 requiresTwoFactorMutant：>= 變成 >），再跑一次測試。
//       演練把同一個測試，分別對「正確的實作」和「被改壞的實作」各跑一次。
//
// 你要寫：
//   ① Bug 版：寫兩個離邊界很遠的檢查：requires(5_000_000) 是 true、requires(100) 是 false（用 t.equal）。
//      兩個分支都跑到了，覆蓋率 100%，但被改壞的實作測試也會通過。
//   ② 修正版：再補上邊界的檢查：999_999 是 false、1_000_000 是 true、1_000_001 是 true。
// 只改有 TODO 的地方。解答：solution/testing/testing01-strategy/L03_CoverageIsNotChecking.kt
package tw.harry.labs.practice.testing01strategy

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 把同一個測試分別對「正確的實作」和「被改壞的實作」各跑一次，並記下函式被呼叫了幾次、兩個分支有沒有跑到（已寫好） */
private fun runMatrix(log: LabLog, test: (requires: (Int) -> Boolean, t: T01Expect) -> Unit) {
    val implementations = listOf<Pair<String, (Int) -> Boolean>>(
        "正確的實作" to T01Withdrawal::requiresTwoFactor,
        "被改壞的實作（>= 改成 >）" to T01Withdrawal::requiresTwoFactorMutant,
    )
    for ((name, implementation) in implementations) {
        var calls = 0
        var sawTrue = false
        var sawFalse = false
        val spy: (Int) -> Boolean = { amount ->
            calls++
            val result = implementation(amount)
            if (result) sawTrue = true else sawFalse = true
            result
        }
        val t = T01Expect()
        test(spy, t)
        log("$name：${if (t.failures.isEmpty()) "測試通過" else "測試失敗（${t.failures[0]}）"}")
        if (name == "正確的實作") {
            log("覆蓋：函式被呼叫 $calls 次，true 分支${if (sawTrue) "有" else "沒有"}跑到，false 分支${if (sawFalse) "有" else "沒有"}跑到")
        }
    }
}

// MARK: - Bug 版
private object L03Bug {
    fun test(requires: (Int) -> Boolean, t: T01Expect) {
        // TODO ①：用 t.equal(requires(5_000_000), true)、t.equal(requires(100), false) 寫兩個離邊界很遠的檢查
    }

    fun run(log: LabLog) = runMatrix(log, ::test)
}

// MARK: - 修正版
private object L03Fixed {
    fun test(requires: (Int) -> Boolean, t: T01Expect) {
        // TODO ②-1：先寫和 Bug 版一樣的兩個檢查
        // TODO ②-2：再補邊界：999_999 預期 false、1_000_000 預期 true、1_000_001 預期 true
    }

    fun run(log: LabLog) = runMatrix(log, ::test)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "覆蓋率 100%，程式被改壞測試還是過",
    question = "第 4 題：怎麼知道測試真的有用？",
    goal = "看一個「每一行都跑到、卻抓不到邊界 bug」的測試，再補上邊界的例子，讓被改壞的程式測試會失敗。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行：兩個分支都跑到了（覆蓋率 100%），再看被改壞的實作，測試是通過還是失敗？",
        "想一想：mutation score 是什麼？這個測試對「>= 改成 >」這一種改壞，抓到了幾個？",
        "寫 TODO ②，選「修正版」執行：被改壞的實作要讓測試失敗。",
    ),
    expectBug = "正確的實作：測試通過；覆蓋：函式被呼叫 2 次，兩個分支都跑到；被改壞的實作：測試也通過（沒抓到）。",
    expectFixed = "正確的實作：測試通過；被改壞的實作：測試失敗（預期 true，實際 false），抓到了。",
    file = "testing01-strategy/L03_CoverageIsNotChecking.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "正確的實作：測試通過" !in lines -> "測試在正確的實作上要通過：requires(5_000_000) 是 true、requires(100) 是 false"
            "覆蓋：函式被呼叫 2 次，true 分支有跑到，false 分支有跑到" !in lines -> "測試要呼叫 requires 兩次，讓 true 與 false 兩個分支都跑到（覆蓋率 100%）"
            "被改壞的實作（>= 改成 >）：測試通過" !in lines -> "Bug 還沒重現：被改壞的實作也要讓測試通過（不要驗剛好 1,000,000 的邊界）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "正確的實作：測試通過" !in lines -> "測試在正確的實作上要通過"
            lines.none { it.startsWith("覆蓋：函式被呼叫 ") && !it.startsWith("覆蓋：函式被呼叫 0 次") } -> "測試沒有呼叫 requires：要驗邊界 999_999、1_000_000、1_000_001"
            lines.none { it.startsWith("被改壞的實作（>= 改成 >）：測試失敗") } -> "被改壞的實作也通過了：補上剛好 1_000_000 的例子，預期 true"
            else -> null
        }
    },
)
