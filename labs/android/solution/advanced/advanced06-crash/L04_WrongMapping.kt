// L04：crash 報告看得懂了，卻指到完全不相干的函式（mapping.txt 要用 build 的
// ID 對，不是版本名稱）
// 對應複習站：Advanced06Crash 第 3 題「crash 報告為什麼一開始看不懂？dSYM 和
// mapping 檔是什麼？」
//
// 情境：App 3.1.0 先打包了 build 1，後來為了補一個修正又重新打包了 build 2，
// versionName 還是 3.1.0。
//       R8 每次混淆都會重新取名：build 2 多了程式碼，a.a、a.b 代表的函式和
// build 1 不一樣。
//       每份 mapping.txt 開頭有一行 `# pg_map_id: …`，是這次 build 的識別碼，
// Crashlytics 把它放進 App，crash 報告會帶著。
//       上架的是 build 2，CI 卻只上傳了 build 1 的 mapping.txt。使用者的 crash
// 發生在 build 2 的 ChartRenderer.draw()。
// Bug 版：用 versionName 找 mapping.txt（versionName 是 3.1.0 的第一份）。找到
// build 1 的 mapping，名稱對不上卻不會報錯，
//        還原出另一個函式 OrderViewModel.submit()；就算補上傳了 build 2 的
// mapping，還是找到第一份，一樣是錯的。
// 修正版：用 crash 報告裡的 pg_map_id 找 mapping.txt。
// 找不到就老實說「未還原」；補上傳之後才還原出正確的名字。
package tw.harry.labs.solution.advanced06crash

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 一份 mapping.txt：pg_map_id、versionName，以及「混淆後的名稱 → 原本的名稱」
 */
private class L04Mapping(
    val mapId: String,
    val versionName: String,
    val names: Map<String, String>,
) {
    fun retrace(frame: String): String = names[frame] ?: "（不認得的名稱）"
}

/**
 * crash 報告：發生在哪個版本、哪一次 build（pg_map_id）、哪一個混淆後的 frame
 */
private class L04CrashReport(
    val versionName: String,
    val mapId: String,
    val frame: String,
)

private val build1 =
    L04Mapping(
        "1a2b3c4d",
        "3.1.0",
        mapOf("a.a" to "OrderViewModel.submit", "a.b" to "ChartRenderer.draw"),
    )

/**
 * 重新打包後，R8 重新取名：a.a 變成 ChartRenderer.draw，a.b 變成
 * OrderViewModel.submit
 */
private val build2 =
    L04Mapping(
        "9c3f5e7a",
        "3.1.0",
        mapOf("a.a" to "ChartRenderer.draw", "a.b" to "OrderViewModel.submit"),
    )
private val report = L04CrashReport("3.1.0", "9c3f5e7a", "a.a")

/**
 * 演練流程（兩個版本共用）：retrace(報告, 已上傳的 mapping)
 * 回傳還原後的函式名稱
 */
private fun scenario(
    log: LabLog,
    retrace: (L04CrashReport, List<L04Mapping>) -> String,
) {
    log(
        "crash 報告：App ${report.versionName}，pg_map_id ${report.mapId}，frame ${report.frame}",
    )
    // CI 只上傳了 build 1
    val uploaded = mutableListOf(build1)
    log("只有 build 1 的 mapping.txt：${retrace(report, uploaded)}")
    // 發現漏傳，補上傳 build 2
    uploaded += build2
    log("補上傳 build 2 的 mapping.txt 之後：${retrace(report, uploaded)}")
    log("正確答案：${build2.retrace(report.frame)}")
}

// MARK: - Bug 版
private object L04Bug {
    fun retrace(report: L04CrashReport, store: List<L04Mapping>): String {
        val mapping =
            store.firstOrNull { it.versionName == report.versionName }
                ?: return "未還原（沒有這個版本的 mapping.txt）"
        // versionName 相同就拿來用，沒有檢查是不是同一次 build
        return mapping.retrace(report.frame)
    }

    suspend fun run(log: LabLog) = scenario(log, ::retrace)
}

// MARK: - 修正版
private object L04Fixed {
    fun retrace(report: L04CrashReport, store: List<L04Mapping>): String {
        // pg_map_id 一樣才是同一次 build；找不到就承認還原不了，不要猜
        val mapping = store.firstOrNull { it.mapId == report.mapId }
            ?: return "未還原（缺少 pg_map_id ${report.mapId} 的 mapping.txt）"
        return mapping.retrace(report.frame)
    }

    suspend fun run(log: LabLog) = scenario(log, ::retrace)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "crash 報告指到不相干的函式",
    question = "第 3 題：crash 報告為什麼一開始看不懂？dSYM 和 mapping 檔是什麼？",
    goal = "crash 報告要用「同一次 build」的 mapping.txt 還原：用 pg_map_id 對，對不上就老實說還原不了，不要還原出一個錯的名字。",
    steps = listOf(
        "選「Bug 版」執行，看兩次還原的結果，和最後一行的「正確答案」比較。",
        "想一想：build 1 和 build 2 的 versionName 一樣。R8 重新取名後，a.a 代表的函式為什麼不同？",
        "選「修正版」執行：只有 build 1 的時候要回「未還原」；補上傳 build 2 之後要還原出 ChartRenderer.draw。",
        "進階：本機可以用 R8 附的 retrace 工具，輸入 mapping.txt 與 stack trace 手動還原；iOS 對應的是 dSYM 的 UUID。",
    ),
    expectBug = "兩次都還原成「OrderViewModel.submit」，和正確答案 ChartRenderer.draw 不同；補上傳了正確的 mapping 也沒有改善。",
    expectFixed = "第一次：「未還原（缺少 pg_map_id 9c3f5e7a 的 mapping.txt）」；補上傳之後：「ChartRenderer.draw」，和正確答案一致。",
    file = "advanced06-crash/L04_WrongMapping.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("只有 build 1 的 mapping.txt：") } -> {
                "演練沒有跑完：要用 scenario 執行"
            }

            "補上傳 build 2 的 mapping.txt 之後：OrderViewModel.submit" !in lines -> {
                "Bug 還沒重現：用 versionName（相同）找 mapping.txt，會找到 build 1 的那一份，還原出 OrderViewModel.submit"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("只有 build 1 的 mapping.txt：") } -> {
                "演練沒有跑完：要用 scenario 執行"
            }

            "只有 build 1 的 mapping.txt：未還原（缺少 pg_map_id 9c3f5e7a 的 mapping.txt）" !in
                lines -> {
                "只有 build 1 的 mapping.txt 時應該回「未還原」：pg_map_id 對不上，不能拿別次 build 的 mapping 來猜"
            }

            "補上傳 build 2 的 mapping.txt 之後：ChartRenderer.draw" !in lines -> {
                "補上傳 build 2 的 mapping.txt 之後應該還原出 ChartRenderer.draw：要用 report.mapId 找 mapId 一樣的 mapping"
            }

            else -> {
                null
            }
        }
    },
)
