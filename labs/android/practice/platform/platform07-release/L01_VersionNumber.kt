// L01：發 3.10.0 時，商店說這個版本號比以前上傳的小（用字串拼出來的 versionCode）
// 對應複習站：Platform07Release 第 3 題「測試版怎麼發給測試人員？TestFlight 和 Google Play 的測試軌差在哪？」（版本號要遞增）
//
// 情境：CI 腳本要自動從版本名稱算出 versionCode（iOS 是 build 號），免得人工忘記遞增。
//        商店規定：新上傳的 versionCode 必須比以前上傳過的都大，否則拒絕。演練依序發布 7 個版本：
//        3.2.9、3.2.10、3.9.0、3.9.10、3.10.0、3.20.0、3.21.0，看有幾個版本被商店拒絕。
//
// 你要寫：
//   ① Bug 版：versionCode 把版本名稱的「.」拿掉，直接轉成整數（3.2.10 → 3210）。
//   ② 修正版：用「.」切成三段整數（每段要小於 1000），回傳 主版號 × 1_000_000 ＋ 次版號 × 1_000 ＋ 修訂號。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform07-release/L01_VersionNumber.kt
package tw.harry.labs.practice.platform07release

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 要依序發布的版本（兩個版本共用） */
private val releases = listOf("3.2.9", "3.2.10", "3.9.0", "3.9.10", "3.10.0", "3.20.0", "3.21.0")

/** 演練流程（兩個版本共用）：依序「上傳」，versionCode 要比之前上傳過的最大值還大 */
private fun scenario(log: LabLog, versionCode: (String) -> Int) {
    var highest = 0
    var rejected = 0
    for (version in releases) {
        val code = versionCode(version)
        if (code > highest) {
            highest = code
            log("✓ $version → versionCode $code")
        } else {
            rejected++
            log("✗ $version → versionCode $code，不比之前上傳過的最大值 $highest 大，商店拒絕")
        }
    }
    log("共 ${releases.size} 個版本，被商店拒絕 $rejected 個")
}

// MARK: - Bug 版
private object L01Bug {
    fun versionCode(version: String): Int {
        // TODO ①：把 version 裡的 "." 拿掉（replace），直接轉成 Int（轉不成就回傳 0）
        return 0
    }

    suspend fun run(log: LabLog) = scenario(log, ::versionCode)
}

// MARK: - 修正版
private object L01Fixed {
    fun versionCode(version: String): Int {
        // TODO ②：用 "." 切成三段整數 parts（不是三段、或有一段 >= 1000 就回傳 0），
        //        回傳 parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        return 0
    }

    suspend fun run(log: LabLog) = scenario(log, ::versionCode)
}

// MARK: - 演練設定
private fun rejectedCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("共 ") && "被商店拒絕" in it }?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l01 = Lab(
    id = "L01",
    title = "發 3.10.0 時，商店說版本號比以前的小",
    question = "第 3 題：測試版怎麼發給測試人員？TestFlight 和 Google Play 的測試軌差在哪？（版本號要遞增）",
    goal = "自動從版本名稱算出 versionCode，而且每個新版本的 versionCode 一定比以前所有版本都大。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看哪些版本被商店拒絕、versionCode 是多少，出現 ✓。",
        "想一想：3.9.0 和 3.21.0 去掉點之後各是幾？數字大小跟版本新舊一致嗎？",
        "寫 TODO ②，選「修正版」執行，7 個版本的 versionCode 都要遞增。",
        "進階：versionCode 上限是 2,100,000,000；修正版把每一段限制在 3 位數，主版號最多到 2099。",
    ),
    expectBug = "「3.9.0 → versionCode 390」「3.10.0 → versionCode 3100」「3.20.0 → versionCode 3200」「3.21.0 → versionCode 3210」被商店拒絕，「共 7 個版本，被商店拒絕 4 個」。",
    expectFixed = "versionCode 依序是 3002009、3002010、3009000、3009010、3010000、3020000、3021000，「共 7 個版本，被商店拒絕 0 個」。",
    file = "platform07-release/L01_VersionNumber.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = rejectedCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::versionCode) 執行"
            "✓ 3.2.10 → versionCode 3210" !in lines -> "Bug 版把版本名稱的點拿掉當整數：3.2.10 要變成 3210"
            n < 3 -> "Bug 還沒重現：把點拿掉直接轉成整數，3.9.0、3.10.0 等版本的 versionCode 會比以前的小（目前只有 $n 個被拒絕）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = rejectedCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::versionCode) 執行"
            n == 0 -> null
            else -> "還有 $n 個版本被拒絕：每一段固定 3 位數：主版號 × 1_000_000 ＋ 次版號 × 1_000 ＋ 修訂號"
        }
    },
)
