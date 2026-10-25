// L04：每一版只大了一點點，兩年後 App 卻大了 12 MB（CI 的體積檢查要跟基準比）
// 對應複習站：Advanced07AppSize 第 6 題「縮小體積有什麼風險？怎麼確認沒有弄壞東西？」（把體積檢查加進 CI）
//
// 情境：團隊在 CI 加了體積檢查，避免 App 悄悄變大。基準版本是 100.0 MB；之後連續 12 個 build，每個都只比上一個大 0.6 MB（新功能、新圖片的正常成長），
//       第 13 個 build 一次大了 4.5 MB（有人不小心把原圖放進去）。團隊的預算是：整體不超過基準 + 3 MB，單一 build 不超過上一個 + 1 MB。
//
// 你要寫：
//   ① Bug 版：只檢查這個 build 比上一個大多少（超過 1 MB 才擋下）。每次只大 0.6 MB，一路放行。
//   ② 修正版：除了單一 build 的增幅，還要檢查比基準版本大多少（整體預算 3 MB）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced07-app-size/L04_SizeGate.kt
package tw.harry.labs.practice.advanced07appsize

import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val BASELINE_KB = 100_000
private const val PER_BUILD_LIMIT_KB = 1_000
private const val BUDGET_KB = 3_000

/** 13 個 build 的大小（KB）：前 12 個每次 +600 KB，第 13 個 +4500 KB（兩個版本共用） */
private val builds: List<Int> = buildList {
    var size = BASELINE_KB
    repeat(12) { size += 600; add(size) }
    add(size + 4_500)
}

/** 演練流程（兩個版本共用）：passes(這個 build 的大小, 上一個 build 的大小) 回傳這個 build 能不能通過檢查 */
private fun scenario(log: LabLog, passes: (size: Int, previous: Int) -> Boolean) {
    val blocked = mutableListOf<Int>()
    var previous = BASELINE_KB
    builds.forEachIndexed { index, size ->
        if (!passes(size, previous)) blocked += index + 1
        previous = size                                          // 擋下的 build 在演練裡仍然當作「已經存在」，只是被標記
    }
    log("共 ${builds.size} 個 build，被擋下 ${blocked.size} 個：${blocked.joinToString("、") { "#$it" }}")
    log("最後一個 build 比基準版本大 ${String.format(Locale.ROOT, "%.1f", (builds.last() - BASELINE_KB) / 1_000.0)} MB")
}

// MARK: - Bug 版
private object L04Bug {
    fun passes(size: Int, previous: Int): Boolean {
        // TODO ①：只檢查「這個 build 比上一個大多少」：size - previous 不超過 PER_BUILD_LIMIT_KB 才通過
        return true
    }

    suspend fun run(log: LabLog) = scenario(log, ::passes)
}

// MARK: - 修正版
private object L04Fixed {
    fun passes(size: Int, previous: Int): Boolean {
        // TODO ②：除了單一 build 的增幅（size - previous 不超過 PER_BUILD_LIMIT_KB），
        //         還要檢查整體預算：size - BASELINE_KB 不超過 BUDGET_KB。兩個都成立才通過
        return true
    }

    suspend fun run(log: LabLog) = scenario(log, ::passes)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "每版只大一點點，兩年後大了 12 MB",
    question = "第 6 題：縮小體積有什麼風險？怎麼確認沒有弄壞東西？（把體積檢查加進 CI）",
    goal = "體積檢查不能只比上一個 build：要有基準版本與整體預算，才擋得住「一次只大一點」的慢性成長。 先寫出只跟上一個 build 比的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced07-app-size/L04_SizeGate.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看被擋下幾個 build，以及最後一個 build 比基準大多少。",
        "想一想：12 個 build 每個都只大 0.6 MB，為什麼檢查完全沒發現？",
        "寫 TODO ②，選「修正版」執行：加上「比基準版本大多少」的預算，看從第幾個 build 開始被擋下。",
        "進階：真正的檢查可以放在 CI 裡，Android 用 bundletool get-size total，iOS 讀 App Thinning Size Report；基準放在版本控制裡，要調整就留下紀錄。",
    ),
    expectBug = "「共 13 個 build，被擋下 1 個：#13」，最後一個 build 比基準版本大 11.7 MB。",
    expectFixed = "「共 13 個 build，被擋下 8 個：#6、#7、#8、#9、#10、#11、#12、#13」：從累積超過預算（3.6 MB）的第 6 個 build 開始擋下。",
    file = "advanced07-app-size/L04_SizeGate.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("共 13 個 build") } -> "演練沒有跑完：要用 scenario 執行"
            "共 13 個 build，被擋下 1 個：#13" !in lines -> "Bug 還沒重現：只比上一個 build 的增幅（> 1 MB 才擋），應該只擋下 #13"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("共 13 個 build") } -> "演練沒有跑完：要用 scenario 執行"
            "共 13 個 build，被擋下 8 個：#6、#7、#8、#9、#10、#11、#12、#13" !in lines ->
                "應該從 #6 開始擋下（比基準大 3.6 MB，超過 3 MB 預算）：除了單一 build 的增幅，還要檢查 size - BASELINE_KB 不超過 BUDGET_KB"
            else -> null
        }
    },
)
