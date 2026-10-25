// L01：單元測試根本沒跑，PR 卻可以合併（把「被略過」「被取消」當成通過）
// 對應複習站：Testing06CI 第 1 題「CI 要分成哪幾段？每段跑什麼？」
//
// 情境：PR 的 CI 有幾個檢查（lint、建置、單元測試、截圖比對，另外有一個非必要的夜間檢查）。
//       canMerge(checks) 決定「這個 PR 能不能合併」。每個檢查的結果有四種：成功、失敗、被取消（例如逾時）、被略過（例如條件寫錯）。
//       演練用五種情況各判斷一次。
//
// 你要寫：
//   ① Bug 版：canMerge 只要「沒有必要（required 是 true）的檢查失敗（result 是 FAILURE）」就回傳 true。
//   ② 修正版：canMerge 只看必要的檢查（required 是 true），而且每一個的 result 都必須是 SUCCESS 才回傳 true。
// 只改有 TODO 的地方。解答：solution/testing/testing06-ci/L01_GatingSkipped.kt
package tw.harry.labs.practice.testing06ci

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private fun check(name: String, result: T06JobResult, required: Boolean = true) = T06Check(name, result, required)

/** 演練流程（兩個版本共用）：五種情況各判斷一次 */
private fun scenario(log: LabLog, canMerge: (List<T06Check>) -> Boolean) {
    val cases = listOf(
        "① 全部成功" to listOf(check("lint", T06JobResult.SUCCESS), check("build", T06JobResult.SUCCESS), check("unit-tests", T06JobResult.SUCCESS), check("snapshot", T06JobResult.SUCCESS)),
        "② 單元測試失敗" to listOf(check("lint", T06JobResult.SUCCESS), check("build", T06JobResult.SUCCESS), check("unit-tests", T06JobResult.FAILURE), check("snapshot", T06JobResult.SUCCESS)),
        "③ 單元測試逾時被取消" to listOf(check("lint", T06JobResult.SUCCESS), check("build", T06JobResult.SUCCESS), check("unit-tests", T06JobResult.CANCELLED), check("snapshot", T06JobResult.SUCCESS)),
        "④ 單元測試被略過（條件寫錯）" to listOf(check("lint", T06JobResult.SUCCESS), check("build", T06JobResult.SUCCESS), check("unit-tests", T06JobResult.SKIPPED), check("snapshot", T06JobResult.SUCCESS)),
        "⑤ 非必要的夜間檢查失敗" to listOf(check("lint", T06JobResult.SUCCESS), check("build", T06JobResult.SUCCESS), check("unit-tests", T06JobResult.SUCCESS), check("nightly", T06JobResult.FAILURE, required = false)),
    )
    for ((title, checks) in cases) {
        log("$title：${if (canMerge(checks)) "允許合併" else "擋下"}")
    }
}

// MARK: - Bug 版
private object L01Bug {
    fun canMerge(checks: List<T06Check>): Boolean =
        // TODO ①：沒有任何「必要的檢查失敗（result 是 FAILURE）」就回傳 true（目前一律回傳 false）
        false

    fun run(log: LabLog) = scenario(log, ::canMerge)
}

// MARK: - 修正版
private object L01Fixed {
    fun canMerge(checks: List<T06Check>): Boolean =
        // TODO ②：只看必要的檢查（required 是 true），每一個的 result 都是 SUCCESS 才回傳 true（目前一律回傳 false）
        false

    fun run(log: LabLog) = scenario(log, ::canMerge)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "單元測試根本沒跑，PR 卻可以合併",
    question = "第 1 題：CI 要分成哪幾段？每段跑什麼？",
    goal = "合併 PR 的條件要寫成「必要的檢查都成功」，而不是「沒有失敗」：被取消、被略過的檢查沒有驗證任何東西。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看五種情況各自允許合併還是擋下。③ ④ 的單元測試有沒有真的跑完？",
        "想一想：GitHub 的 required check 被略過（skipped）時，會被當成通過。要怎麼避免？（提示：再加一個 job，檢查前面的 job 結果都是 success）",
        "寫 TODO ②，選「修正版」執行：只有 ① ⑤ 允許合併。",
    ),
    expectBug = "① 允許合併；② 擋下；③ 允許合併、④ 允許合併（單元測試沒有跑完或根本沒跑）；⑤ 允許合併。",
    expectFixed = "① 允許合併；② ③ ④ 擋下；⑤ 允許合併（非必要的檢查失敗不影響）。",
    file = "testing06-ci/L01_GatingSkipped.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "① 全部成功：允許合併" !in lines -> "全部成功應該允許合併"
            "② 單元測試失敗：擋下" !in lines -> "必要的檢查失敗應該擋下"
            "③ 單元測試逾時被取消：允許合併" !in lines || "④ 單元測試被略過（條件寫錯）：允許合併" !in lines ->
                "Bug 還沒重現：只擋下 result 是 FAILURE 的必要檢查，被取消、被略過的都放行"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "① 全部成功：允許合併" !in lines -> "全部成功應該允許合併"
            "⑤ 非必要的夜間檢查失敗：允許合併" !in lines -> "非必要的檢查（required 是 false）失敗，不該擋住合併"
            "② 單元測試失敗：擋下" !in lines -> "必要的檢查失敗應該擋下"
            "③ 單元測試逾時被取消：擋下" !in lines || "④ 單元測試被略過（條件寫錯）：擋下" !in lines ->
                "必要的檢查必須是 SUCCESS：被取消、被略過也要擋下"
            else -> null
        }
    },
)
