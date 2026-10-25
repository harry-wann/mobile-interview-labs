// L01：沒有 NFC 的手機看得到「讀卡」，伺服器停用的方式還在選單上（可用的驗證方式寫死在 App）
// 對應複習站：Exchange11eKYC 第 1 題「在日本開戶為什麼要做本人確認？ホ、ヘ、ワ三種方式差在哪裡？」
//
// 情境：線上本人確認有三種方式：ホ（證件影像加臉部影像，用相機）、ヘ（證件的 IC 晶片資訊加臉部影像，要用 NFC 讀卡）、ワ（電子證明書，要用 NFC 讀卡）。
//        複習站的設計重點：可用的方式不寫死在 App，改由伺服器下發（功能旗標）；法規或公司政策改變時，不用發版。
//        畫面上要顯示哪些方式 = 伺服器開放的方式 ∩ 這支手機做得到的方式（沒有 NFC 的手機不能用 ヘ、ワ）；一個都沒有時，要顯示說明頁，不能留一個空白選單。
//        演練有四種情況：①一般手機（有 NFC）、伺服器開放三種 ②沒有 NFC 的手機、伺服器開放三種 ③有 NFC、伺服器日後停用了 ホ（只開放 ヘ、ワ）
//        ④沒有 NFC、伺服器只開放 ヘ、ワ。（方式與規則是演練用的示範，實際細節以法務確認為準。）
//
// 你要寫：
//   ① Bug 版（L01Bug.screen）：不管伺服器和手機，一律回傳 Choose(L01Method.entries)（三種方式都顯示）。
//   ② 修正版（L01Fixed.screen）：從 L01Method.entries 留下「server 有開放，而且（不需要 NFC，或手機有 NFC）」的方式；
//      留下來的是空的就回傳 NoMethod，否則回傳 Choose(留下來的方式)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange11-ekyc/L01_Methods.kt
package tw.harry.labs.practice.exchange11ekyc

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class L01Method(val symbol: String, val needsNfc: Boolean) {
    Ho("ホ", false),     // 證件影像加臉部影像（相機）
    He("ヘ", true),      // IC 晶片資訊加臉部影像（NFC）
    Wa("ワ", true),      // 電子證明書（NFC）
}

private sealed interface L01Screen {
    data class Choose(val methods: List<L01Method>) : L01Screen    // 選擇驗證方式的畫面
    data object NoMethod : L01Screen                                  // 沒有可用的方式：顯示說明與客服入口
}

private fun text(screen: L01Screen): String = when (screen) {
    is L01Screen.Choose -> "選單 " + screen.methods.joinToString("、") { it.symbol }
    L01Screen.NoMethod -> "「沒有可用方式」說明頁"
}

private class L01Case(val name: String, val server: List<L01Method>, val hasNfc: Boolean, val expected: L01Screen)

private val cases = listOf(
    L01Case("有 NFC 的手機，伺服器開放三種", listOf(L01Method.Ho, L01Method.He, L01Method.Wa), true, L01Screen.Choose(listOf(L01Method.Ho, L01Method.He, L01Method.Wa))),
    L01Case("沒有 NFC 的手機，伺服器開放三種", listOf(L01Method.Ho, L01Method.He, L01Method.Wa), false, L01Screen.Choose(listOf(L01Method.Ho))),
    L01Case("有 NFC 的手機，伺服器停用了 ホ", listOf(L01Method.He, L01Method.Wa), true, L01Screen.Choose(listOf(L01Method.He, L01Method.Wa))),
    L01Case("沒有 NFC 的手機，伺服器只開放 ヘ、ワ", listOf(L01Method.He, L01Method.Wa), false, L01Screen.NoMethod),
)

/** 演練流程（兩個版本共用）：四種情況逐一決定畫面，和正確答案比對 */
private fun scenario(log: LabLog, screen: (server: List<L01Method>, hasNfc: Boolean) -> L01Screen) {
    var wrong = 0
    for (c in cases) {
        val shown = screen(c.server, c.hasNfc)
        if (shown == c.expected) {
            log("${c.name} → 顯示${text(shown)} ✓")
        } else {
            wrong++
            log("${c.name} → 顯示${text(shown)} ✗（應該顯示${text(c.expected)}）")
        }
    }
    log("4 種情況，畫面判斷錯的有 $wrong 種")
}

// MARK: - Bug 版
private object L01Bug {
    fun screen(server: List<L01Method>, hasNfc: Boolean): L01Screen {
        // TODO ①：一律回傳 L01Screen.Choose(L01Method.entries)
        return L01Screen.NoMethod
    }

    suspend fun run(log: LabLog) = scenario(log) { server, hasNfc -> screen(server, hasNfc) }
}

// MARK: - 修正版
private object L01Fixed {
    fun screen(server: List<L01Method>, hasNfc: Boolean): L01Screen {
        // TODO ②：用 L01Method.entries.filter 留下「it in server，而且（!it.needsNfc || hasNfc）」的方式；
        //        留下來的是空的就回傳 L01Screen.NoMethod，否則回傳 L01Screen.Choose(留下來的方式)
        return L01Screen.NoMethod
    }

    suspend fun run(log: LabLog) = scenario(log) { server, hasNfc -> screen(server, hasNfc) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("4 種情況") }?.let { Regex("(\\d+) 種$").find(it)?.groupValues?.get(1)?.toInt() }

val l01 = Lab(
    id = "L01",
    title = "沒有 NFC 的手機也看得到讀卡，停用的方式還在選單上",
    question = "第 1 題：在日本開戶為什麼要做本人確認？ホ、ヘ、ワ三種方式差在哪裡？",
    goal = "可用的本人確認方式由伺服器下發，再依這支手機做得到的功能（有沒有 NFC）過濾；一個都沒有時顯示說明頁。不要把選單寫死在 App。",
    steps = listOf(
        "打開 practice/exchange/exchange11-ekyc/L01_Methods.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看四種情況的選單：哪些情況顯示了不能用或已停用的方式？",
        "想一想：法規或政策改變、伺服器停用某個方式時，寫死的選單要怎麼處理？要等使用者更新 App 嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，四種情況都要顯示正確的畫面（最後一種是說明頁）。",
        "進階：NFC 讀卡的成功率也要追蹤，依機型和失敗原因分類；沒有 NFC 的使用者要有替代方式，不能卡在這一步。",
    ),
    expectBug = "第 1 種情況是對的；沒有 NFC 的手機顯示了 ホ、ヘ、ワ ✗，伺服器停用 ホ 之後選單還有 ホ ✗，沒有可用方式時顯示選單而不是說明頁 ✗：「4 種情況，畫面判斷錯的有 3 種」。",
    expectFixed = "選單依序是 ホ、ヘ、ワ／只有 ホ／ヘ、ワ，最後一種顯示「沒有可用方式」說明頁：「4 種情況，畫面判斷錯的有 0 種」。",
    file = "exchange11-ekyc/L01_Methods.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { server, hasNfc -> … } 執行"
            "有 NFC 的手機，伺服器開放三種 → 顯示選單 ホ、ヘ、ワ ✓" !in lines -> "Bug 版的選單永遠是 ホ、ヘ、ワ 三種（有 NFC、伺服器開放三種時剛好是對的）"
            n != 3 -> "Bug 還沒重現：寫死三種方式，後三種情況都會判斷錯（目前錯 $n 種）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { server, hasNfc -> … } 執行"
            n != 0 -> "還有 $n 種情況畫面不對：留下「伺服器有開放，而且手機做得到」的方式，空的就顯示說明頁"
            else -> null
        }
    },
)
