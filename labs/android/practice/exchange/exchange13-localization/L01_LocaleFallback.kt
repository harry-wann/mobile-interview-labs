// L01：香港和簡體區的使用者看到的是英文介面（語系標籤寫死 zh-CN、zh-TW，
// 沒有降級鏈）
// 對應複習站：Exchange13Localization 第 1 題「四種語言（日文、簡體中文、
// 繁體中文、英文）要怎麼標記與降級？」
//
// 情境：App 提供四種語言：ja、en、zh-Hans（簡體）、zh-Hant（繁體）。
// 使用者的系統語言標籤五花八門：zh-Hant-TW、zh-Hant-HK、zh-Hans-CN、
//        舊式的 zh-TW／zh-CN／zh-HK、ja-JP、en-US，甚至沒有支援的 fr-FR。
// 簡體和繁體是「文字（script）」的差異，不是地區的差異。
//        resolve 要回傳 App 該用的語言；找不到時，依「完整標籤、去掉最後一段、
// 繁簡推斷、預設語言 en」的順序降級。九個標籤：
//        ja-JP、en-US、zh-Hant-TW、zh-Hant-HK、zh-Hans-CN、zh-TW、zh-CN、
// zh-HK、fr-FR。
//
// 你要寫：
//   ① Bug 版（L01Bug.resolve）：requested 等於 "zh-CN" 回傳 "zh-Hans"，等於
// "zh-TW" 回傳 "zh-Hant"；否則取第一個 "-" 前面的語言碼，
//      supported 有就回傳它，沒有回傳 "en"。
//   ② 修正版（L01Fixed.resolve）：requested 用 "-" 拆開；(1) 完整標籤在
// supported 裡就回傳；(2) 語言是 zh 時：有 4 個字母的 script 段（Hans／Hant）
// 就用它，
//      沒有就看地區段（TW、HK、MO 用 Hant，其他用 Hans），回傳 "zh-" 加
// script；(3) 語言碼（第一段）在 supported 裡就回傳；(4) 都不是就回傳 "en"。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange13-localization/L01_LocaleFallback.kt
package tw.harry.labs.practice.exchange13localization

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val supported = listOf("ja", "en", "zh-Hans", "zh-Hant")

/** （使用者的語言標籤, 正確的結果） */
private val cases = listOf(
    "ja-JP" to "ja",
    "en-US" to "en",
    "zh-Hant-TW" to "zh-Hant",
    "zh-Hant-HK" to "zh-Hant",
    "zh-Hans-CN" to "zh-Hans",
    "zh-TW" to "zh-Hant",
    "zh-CN" to "zh-Hans",
    "zh-HK" to "zh-Hant",
    "fr-FR" to "en",
)

/** 演練流程（兩個版本共用）：九個標籤逐一決定語言，和正確答案比對 */
private fun scenario(log: LabLog, resolve: (String, List<String>) -> String) {
    var wrong = 0
    for ((tag, expected) in cases) {
        val result = resolve(tag, supported)
        if (result == expected) {
            log("$tag → $result ✓")
        } else {
            wrong++
            log("$tag → $result ✗（應該是 $expected）")
        }
    }
    log("共 9 個語言標籤，選錯語言的有 $wrong 個")
}

// MARK: - Bug 版
private object L01Bug {
    fun resolve(requested: String, supported: List<String>): String {
        // TODO ①：requested == "zh-CN" 回傳 "zh-Hans"，== "zh-TW" 回傳
        // "zh-Hant"；否則取 requested.substringBefore("-") 當語言碼，
        //        語言碼在 supported 裡就回傳語言碼，否則回傳 "en"
        return "en"
    }

    suspend fun run(log: LabLog) = scenario(log) { requested, supported ->
        resolve(requested, supported)
    }
}

// MARK: - 修正版
private object L01Fixed {
    fun resolve(requested: String, supported: List<String>): String {
        // TODO ②：(1) requested 在 supported 裡就回傳 requested；parts =
        // requested.split("-")，language = parts.first()；
        //        (2) language == "zh"：parts 裡有 4 個字母的段（Hans／Hant）
        // 就用它當 script，沒有就看有沒有 "TW"、"HK"、"MO"（有用 "Hant"，
        // 沒有用 "Hans"），回傳 "zh-" + script；
        //        (3) language 在 supported 裡就回傳 language；(4) 都不是回傳
        // "en"
        return "en"
    }

    suspend fun run(log: LabLog) = scenario(log) { requested, supported ->
        resolve(requested, supported)
    }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("共 9 個語言標籤")
}?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l01 = Lab(
    id = "L01",
    title = "香港和簡體區的使用者看到英文介面",
    question = "第 1 題：四種語言（日文、簡體中文、繁體中文、英文）要怎麼標記與降級？",
    goal = "簡體和繁體是文字（script）的差異，用 zh-Hans 和 zh-Hant；語系標籤要有降級鏈（完整標籤、去掉最後一段、繁簡推斷、預設語言），不要只寫死 zh-CN、zh-TW。",
    steps = listOf(
        "打開 practice/exchange/exchange13-localization/L01_LocaleFallback.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看九個標籤哪些變成了英文：香港的使用者（zh-Hant-HK）看到什麼語言？",
        "想一想：zh-Hant-TW 去掉最後一段是 zh-Hant，再去掉一段才是 zh。App 的字串資源裡有 zh 嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，九個標籤都要選對（fr-FR 才退回預設語言 en）。",
        "進階：Android 的資源限定詞可以寫 b+zh+Hans、b+zh+Hant（BCP 47 寫法），也可以寫舊式的 zh-rCN、zh-rTW；App 內選的語言和系統地區是兩件事，數字和日期的格式要用同一個 Locale。",
    ),
    expectBug = "zh-Hant-TW、zh-Hant-HK、zh-Hans-CN、zh-HK 都變成 en ✗（舊式的 zh-TW、zh-CN 因為寫死所以剛好對）：「共 9 個語言標籤，選錯語言的有 4 個」。",
    expectFixed = "九個標籤都選對：zh-Hant-HK → zh-Hant、zh-HK → zh-Hant、zh-Hans-CN → zh-Hans，fr-FR → en：「共 9 個語言標籤，選錯語言的有 0 個」。",
    file = "exchange13-localization/L01_LocaleFallback.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { requested, supported -> … } 執行"
            "zh-TW → zh-Hant ✓" !in lines || "ja-JP → ja ✓" !in lines -> "Bug 版要能處理 ja-JP 與寫死的 zh-TW、zh-CN"
            "zh-Hant-HK → en ✗（應該是 zh-Hant）" !in lines -> "Bug 還沒重現：只比對語言碼時，zh-Hant-HK 會退回英文"
            n != 4 -> "Bug 版應該有 4 個標籤選錯（目前 $n 個）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { requested, supported -> … } 執行"
            n != 0 -> "還有 $n 個標籤選錯：完整標籤 → 中文用 script（沒有就用地區推斷）→ 語言碼 → en"
            else -> null
        }
    },
)
