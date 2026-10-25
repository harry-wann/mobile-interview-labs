// L03：CI 看板的「flaky 比例」是 50%，其實只有 25%（把真的 bug 也算成 flaky）
// 對應複習站：Testing05AITesting 第 4 題「整體架構長什麼樣子？」（指標：flaky 的比例）
//
// 情境：CI 歷史有 3 個 commit、4 個測試的執行紀錄（T05History.runs）：
//       testFee、testChart 一直通過；testLogin 在 c2 壞掉（兩次重試都失敗，是真的 bug），c3 修好；
//       testOrderUI 在 c1 和 c3 的同一個 commit 上，有時失敗有時通過（flaky）。
//       flakyTests(runs) 回傳「flaky 的測試名稱」，看板用它算 flaky 比例 = flaky 測試數 ÷ 全部測試數。
//
// 你要寫：
//   ① Bug 版：flakyTests 回傳「失敗過（passed 是 false）的測試名稱」的集合。
//   ② 修正版：以「同一個 commit 上的同一個測試」為單位（groupBy），收集它出現過的結果；既有通過也有失敗的，測試名稱才放進集合。
// 只改有 TODO 的地方。解答：solution/testing/testing05-ai-testing/L03_FlakyRate.kt
package tw.harry.labs.practice.testing05aitesting

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：算出 flaky 的測試，再印出 flaky 比例 */
private fun scenario(log: LabLog, flakyTests: (List<T05CIRun>) -> Set<String>) {
    val runs = T05History.runs
    val all = runs.map { it.test }.toSet()
    val flaky = flakyTests(runs)
    log("flaky 的測試：" + if (flaky.isEmpty()) "（沒有）" else flaky.sorted().joinToString("、"))
    log("flaky 比例：${flaky.size}／${all.size} = ${flaky.size * 100 / all.size}%")
}

// MARK: - Bug 版
private object L03Bug {
    fun flakyTests(runs: List<T05CIRun>): Set<String> =
        // TODO ①：回傳所有「失敗過（passed 是 false）」的測試名稱（Set<String>）
        emptySet()

    fun run(log: LabLog) = scenario(log, ::flakyTests)
}

// MARK: - 修正版
private object L03Fixed {
    fun flakyTests(runs: List<T05CIRun>): Set<String> =
        // TODO ②-1：以「同一個 commit 上的同一個測試」為單位（groupBy { it.commit to it.test }），收集它出現過的結果（passed 的 true／false）
        // TODO ②-2：這個 commit 上既有通過也有失敗的測試，才把測試名稱放進回傳的集合
        emptySet()

    fun run(log: LabLog) = scenario(log, ::flakyTests)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "flaky 比例顯示 50%，其實只有 25%",
    question = "第 4 題：整體架構長什麼樣子？（指標：flaky 的比例）",
    goal = "flaky 的定義是「程式沒改，同一個測試有時過有時不過」：同一個 commit 上既有通過也有失敗。真的 bug 不能算進去。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看被算成 flaky 的測試有哪些、比例是多少。",
        "對照 T05History.runs：testLogin 在 c2 為什麼不是 flaky？",
        "寫 TODO ②，選「修正版」執行：只剩 testOrderUI，比例 25%。",
    ),
    expectBug = "flaky 的測試：testLogin、testOrderUI；flaky 比例：2／4 = 50%（testLogin 是真的 bug，不該算）。",
    expectFixed = "flaky 的測試：testOrderUI；flaky 比例：1／4 = 25%。",
    file = "testing05-ai-testing/L03_FlakyRate.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        if ("flaky 比例：2／4 = 50%" in lines) null else "Bug 還沒重現：只要有失敗紀錄（passed 是 false）的測試都算 flaky，預期 testLogin 與 testOrderUI（2／4 = 50%）"
    },
    checkFixed = { lines ->
        when {
            "flaky 的測試：testOrderUI" !in lines -> "只有 testOrderUI 在同一個 commit 上既有通過也有失敗"
            "flaky 比例：1／4 = 25%" !in lines -> "flaky 比例應該是 1／4 = 25%"
            else -> null
        }
    },
)
