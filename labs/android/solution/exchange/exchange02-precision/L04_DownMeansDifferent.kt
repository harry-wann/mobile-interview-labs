// L04：同樣寫 DOWN，負的損益差了 0.01（兩個平台的捨入規則不一樣）
// 對應複習站：Exchange02Precision 第 4 題「iOS 和 Android 的捨入規則有什麼差異？」
//
// 情境：損益要顯示到小數 2 位。伺服器有兩種規則（演練假設）：floor 是「取比它小的最大值」（負數往更負的方向，-1.239 → -1.24），
//        truncate 是「直接捨去小數」（往 0 的方向，-1.239 → -1.23）。
//        iOS 與 Android 要共用同一份測試資料（規則、輸入、標準答案，兩個平台的文字一模一樣），各自跑一次，不一致就是 bug，而且一定要有負數。
//        （Platform08 的演練 2 練過這個做法，那題看的是 Double；這題看的是兩個平台對 down 的定義不同。）
// Bug 版：兩種規則都照字面選 RoundingMode.DOWN。Kotlin 的 DOWN 是 truncate（往 0 截斷），所以 truncate 的 3 筆對，
//        但 floor 的負數差了 0.01：-1.239 變成 -1.23，標準答案是 -1.24。
//        （iOS 的 .down 是 floor，剛好反過來：floor 對，truncate 的負數錯。）
// 修正版：truncate 用 DOWN；floor 用 FLOOR（往負無限大）。
package tw.harry.labs.solution.exchange02precision

import java.math.BigDecimal
import java.math.RoundingMode
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 共用測試資料（兩個平台的文字一模一樣）：規則,輸入,標準答案 */
private const val VECTORS = """
floor,-1.239,-1.24
floor,-1.231,-1.24
floor,1.239,1.23
truncate,-1.239,-1.23
truncate,-1.231,-1.23
truncate,1.239,1.23
"""

/** 演練流程（兩個版本共用）：逐筆跑共用測試資料，捨入到小數 2 位 */
private fun scenario(log: LabLog, roundTo2: (BigDecimal, String) -> BigDecimal) {
    var mismatches = 0
    val rows = VECTORS.trim().lines().map { it.split(",") }
    for ((rule, input, expected) in rows) {
        val result = roundTo2(e02(input), rule).toPlainString()
        if (result == expected) {
            log("✓ $rule：$input → $result")
        } else {
            mismatches++
            log("✗ $rule：$input → $result（標準答案是 $expected）")
        }
    }
    log("共 ${rows.size} 筆共用測試資料，和標準答案不一致 $mismatches 筆")
}

// MARK: - Bug 版
private object L04Bug {
    fun roundTo2(value: BigDecimal, rule: String): BigDecimal =
        value.setScale(2, RoundingMode.DOWN)                    // 兩種規則都用 DOWN：DOWN 是往 0 截斷，不是 floor

    suspend fun run(log: LabLog) = scenario(log, ::roundTo2)
}

// MARK: - 修正版
private object L04Fixed {
    fun roundTo2(value: BigDecimal, rule: String): BigDecimal =
        value.setScale(2, if (rule == "floor") RoundingMode.FLOOR else RoundingMode.DOWN)   // floor 要用 FLOOR

    suspend fun run(log: LabLog) = scenario(log, ::roundTo2)
}

// MARK: - 演練設定
private fun mismatchCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("共 ") && "不一致" in it }?.let { Regex("(\\d+) 筆$").find(it)?.groupValues?.get(1)?.toInt() }

val l04 = Lab(
    id = "L04",
    title = "同樣寫 DOWN，負的損益差了 0.01",
    question = "第 4 題：iOS 和 Android 的捨入規則有什麼差異？",
    goal = "損益可能是負數。floor（往負無限大）和 truncate（往 0 截斷）在負數時結果差一個單位，要讓每個規則都和共用測試資料的標準答案一致。",
    steps = listOf(
        "選「Bug 版」執行，看哪幾筆和標準答案不一致，是正數還是負數。",
        "想一想：-1.239 往「下」是 -1.24 還是 -1.23？RoundingMode.DOWN 是哪一種？",
        "選「修正版」執行，6 筆都要和標準答案一致。",
        "進階：iOS 的演練用同一份測試資料。Swift 的 .down 是 floor，剛好和 Kotlin 的 DOWN 相反；兩個平台各自通過，才代表規則真的一致。",
    ),
    expectBug = "「共 6 筆共用測試資料，和標準答案不一致 2 筆」：floor 的 -1.239、-1.231 變成 -1.23，標準答案是 -1.24。",
    expectFixed = "「共 6 筆共用測試資料，和標準答案不一致 0 筆」。",
    file = "exchange02-precision/L04_DownMeansDifferent.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = mismatchCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::roundTo2) 執行"
            "✓ truncate：-1.239 → -1.23" !in lines -> "Bug 版也要能算：用 setScale(2, RoundingMode.DOWN)，truncate 的 -1.239 應該是 -1.23"
            n != 2 -> "Bug 還沒重現：兩種規則都用 DOWN，floor 的兩筆負數應該和標準答案差 0.01（目前不一致 $n 筆）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = mismatchCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::roundTo2) 執行"
            n == 0 -> null
            else -> "還有 $n 筆和標準答案不一致，例如：${lines.firstOrNull { it.startsWith("✗") }}。floor 要用 RoundingMode.FLOOR"
        }
    },
)
