// L01：AI 產生的 5 個測試全過、覆蓋率 100%，被改壞的程式卻只抓到 1 個（用覆蓋率當合併條件）
// 對應複習站：Testing05AITesting 第 2 題「怎麼確保 AI 產生的測試是可信的？」
//
// 情境：AI 幫手續費函式 T05Fee.calculate 產生了 5 個單元測試，全部通過，每個檢查點都跑到（覆蓋率 100%）。
//       審查規則 accepts(suite) 決定「這批測試能不能合併」。
//       另外有 5 個「故意改壞的版本」（T05Mutation.allMutants：>= 改成 >、費率對調、四捨五入、拿掉最少 1 日圓、VIP 沒減半），
//       suite.passes(某個版本) 會用這個版本跑一次全部測試，沒有通過代表測試「抓到」這個壞版本。
//       演練分別審查兩批測試：AI 產生的、人補強過的（檢查精確數字與邊界）。
//
// 你要寫：
//   ① Bug 版：accepts 在「測試全過（suite.passes(T05Mutation.NONE)）而且覆蓋率（suite.coverage()）≥ 0.8」時回傳 true。
//   ② 修正版：accepts 先確認測試在正確的實作上全過；再對每個 T05Mutation.allMutants 跑 suite.passes(it)，沒通過就是抓到；
//      抓到的比例（mutation score）≥ 0.8 才回傳 true。
// 只改有 TODO 的地方。解答：solution/testing/testing05-ai-testing/L01_MutationGate.kt
package tw.harry.labs.practice.testing05aitesting

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：審查兩批測試，再印出實際上抓到幾個壞版本 */
private fun scenario(log: LabLog, accepts: (T05Suite) -> Boolean) {
    for (suite in listOf(T05Suite.aiGenerated, T05Suite.strengthened)) {
        val allPass = suite.passes(T05Mutation.NONE)
        log("${suite.name}：${if (allPass) "全部通過" else "有測試失敗"}，覆蓋率 ${(suite.coverage() * 100).toInt()}%")
        log("${suite.name}：審查結果：${if (accepts(suite)) "允許合併" else "擋下"}")
        val killed = suite.killedMutants()
        val total = T05Mutation.allMutants.size
        log("${suite.name}：被改壞的版本抓到 $killed／$total（mutation score ${killed * 100 / total}%）")
    }
}

// MARK: - Bug 版
private object L01Bug {
    fun accepts(suite: T05Suite): Boolean =
        // TODO ①：測試全過（suite.passes(T05Mutation.NONE)）而且覆蓋率（suite.coverage()）≥ 0.8，就回傳 true
        false

    fun run(log: LabLog) = scenario(log, ::accepts)
}

// MARK: - 修正版
private object L01Fixed {
    fun accepts(suite: T05Suite): Boolean {
        // TODO ②-1：測試在正確的實作上沒有全過，直接回傳 false
        // TODO ②-2：對每個 T05Mutation.allMutants 跑 suite.passes(it)；沒有通過就是「抓到」，數出抓到幾個
        // TODO ②-3：抓到的比例（抓到的個數 ÷ allMutants.size）≥ 0.8 才回傳 true
        return false
    }

    fun run(log: LabLog) = scenario(log, ::accepts)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "AI 的測試全過、覆蓋率 100%，卻抓不到壞掉的程式",
    question = "第 2 題：怎麼確保 AI 產生的測試是可信的？",
    goal = "審查 AI 產生的測試時，不能只看「全過」和覆蓋率，要故意把程式改壞，看測試抓得到幾個（mutation score）。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看兩批測試的審查結果，以及各自抓到幾個壞版本。",
        "想一想：AI 產生的那批測試，哪些改壞的版本沒被抓到？為什麼？（對照 T05Suite.aiGenerated 的檢查條件）",
        "寫 TODO ②，選「修正版」執行：AI 產生的那批要被擋下，補強後的要通過。",
    ),
    expectBug = "AI 產生的測試：全部通過、覆蓋率 100%、審查結果允許合併，卻只抓到 1／5（mutation score 20%）；補強後的測試也允許合併。",
    expectFixed = "AI 產生的測試被擋下（mutation score 20%）；補強後的測試允許合併（抓到 5／5，100%）。",
    file = "testing05-ai-testing/L01_MutationGate.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "AI 產生的測試：被改壞的版本抓到 1／5（mutation score 20%）" !in lines -> "找不到實際抓到的數量：要用 scenario 審查"
            "AI 產生的測試：審查結果：允許合併" !in lines -> "Bug 還沒重現：AI 產生的測試全過、覆蓋率 100%，只看這兩項就應該允許合併"
            "補強後的測試：審查結果：允許合併" !in lines -> "補強後的測試全過、覆蓋率也夠高，應該允許合併"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "AI 產生的測試：審查結果：擋下" !in lines -> "AI 產生的測試只抓到 1／5，mutation score 20% 不到 80%，應該擋下"
            "補強後的測試：審查結果：允許合併" !in lines -> "補強後的測試抓到 5／5，應該允許合併：對每個 T05Mutation.allMutants 跑 suite.passes(it)，沒通過就算抓到"
            else -> null
        }
    },
)
