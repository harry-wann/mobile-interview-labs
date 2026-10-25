// L03：帶了空白、冒號或 Base64 的請求，簽名就是不對（URL 編碼不是 RFC 3986）
// 對應複習站：Exchange09Signing 第 3 題「URL 編碼有哪些陷阱？」
//
// 情境：簽名字串裡每個參數的名稱與值都要做 URL 編碼，規則是 RFC 3986：只有 A-Z a-z 0-9 - . _ ~ 不編碼，
//        其他一律編成大寫的 %XX（非 ASCII 字元先轉 UTF-8 的 byte）。演練拿 6 個字串，和正確的編碼結果比對：
//        含空白與 & = + 的字串、Timestamp（有冒號）、é、日文、含 / * ~ 的字串，以及 Base64 的簽名（要放進 query 的 Signature）。
//
// 你要寫：
//   ① Bug 版（L03Bug.encode）：用 Java 內建的 URLEncoder.encode(s, "UTF-8")。
//   ② 修正版（L03Fixed.encode）：逐個 UTF-8 byte 處理：A-Z a-z 0-9 - . _ ~ 原樣保留，其他編成大寫的 %XX。
//      （Exchange09Support.kt 裡有一份寫好的 E09.percentEncode 給別的演練用，這題請自己寫，不要抄。）
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange09-signing/L03_UrlEncode.kt
package tw.harry.labs.practice.exchange09signing

import java.net.URLEncoder
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val cases = listOf(
    "tea & cake=1+1" to "tea%20%26%20cake%3D1%2B1",
    "2017-05-11T15:19:30" to "2017-05-11T15%3A19%3A30",
    "é" to "%C3%A9",
    "日本円" to "%E6%97%A5%E6%9C%AC%E5%86%86",
    "a/b*c~d" to "a%2Fb%2Ac~d",
    E09.GET_VECTOR to "KLn4U%2FAu9BfxNEAqNebkXqZGpgjxOmyhMwOi%2BFmMfdg%3D",
)

/** 演練流程（兩個版本共用）：6 個字串逐一編碼，和正確答案比對 */
private fun scenario(log: LabLog, encode: (String) -> String) {
    var wrong = 0
    for ((input, expected) in cases) {
        val out = encode(input)
        if (out == expected) {
            log("編碼 \"$input\" → \"$out\" ✓")
        } else {
            wrong++
            log("編碼 \"$input\" → \"$out\" ✗（應該是 \"$expected\"）")
        }
    }
    log("共 6 筆，編碼錯的有 $wrong 筆")
}

// MARK: - Bug 版
private object L03Bug {
    fun encode(s: String): String {
        // TODO ①：回傳 URLEncoder.encode(s, "UTF-8")
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log) { encode(it) }
}

// MARK: - 修正版
private object L03Fixed {
    fun encode(s: String): String {
        // TODO ②：逐個 UTF-8 byte（for (b in s.toByteArray(Charsets.UTF_8))）處理，先用 b.toInt() and 0xFF 轉成 0..255：
        //        A-Z、a-z、0-9、- . _ ~ 原樣保留；其他編成 "%" 加上兩位大寫十六進位（例如 0x20 → %20，0xC3 → %C3）
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log) { encode(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("共 6 筆") }?.let { Regex("(\\d+) 筆$").find(it)?.groupValues?.get(1)?.toInt() }

private fun isCorrect(lines: List<String>, input: String): Boolean? =
    lines.firstOrNull { it.startsWith("編碼 \"$input\"") }?.endsWith("✓")

val l03 = Lab(
    id = "L03",
    title = "帶了空白、冒號或 Base64 的請求，簽名不對",
    question = "第 3 題：URL 編碼有哪些陷阱？",
    goal = "簽名用的 URL 編碼只保留 A-Z a-z 0-9 - . _ ~，其他一律編成大寫的 %XX（空白是 %20，不是 +）；平台內建的編碼函式規則不同，不能直接拿來用。",
    steps = listOf(
        "打開 practice/exchange/exchange09-signing/L03_UrlEncode.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 6 個字串哪些編錯了：空白、/、*、~。",
        "想一想：URLEncoder 原本是拿來做什麼的？表單編碼和 RFC 3986 差在哪裡？",
        "寫 TODO ②（修正版），選「修正版」執行，6 個字串都要和正確答案一致。",
        "進階：iOS 上用 addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) 也會出錯：它保留了 & = + / : * ~（iOS 版的演練可以看到結果）。",
    ),
    expectBug = "「tea & cake=1+1」編成 \"tea+%26+cake%3D1%2B1\"（空白變 +）、\"a/b*c~d\" 編成 \"a%2Fb*c%7Ed\"（* 沒編碼、~ 被編成 %7E）；冒號、/、=、é、日文都是對的。「共 6 筆，編碼錯的有 2 筆」。",
    expectFixed = "6 筆都和正確答案一致：\"tea%20%26%20cake%3D1%2B1\"、\"2017-05-11T15%3A19%3A30\"、\"a%2Fb%2Ac~d\"…，「共 6 筆，編碼錯的有 0 筆」。",
    file = "exchange09-signing/L03_UrlEncode.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { … } 執行"
            isCorrect(lines, "é") != true || isCorrect(lines, "日本円") != true ->
                "Bug 版要用內建的 URLEncoder.encode(s, \"UTF-8\")：非 ASCII 字元（é、日文）它會編對"
            isCorrect(lines, "tea & cake=1+1") != false -> "Bug 還沒重現：URLEncoder 會把空白編成 +，簽名需要的是 %20"
            n < 1 -> "Bug 還沒重現：至少要有一筆編錯"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { … } 執行"
            n != 0 -> "還有 $n 筆編錯：只有 A-Z a-z 0-9 - . _ ~ 不編碼，其他逐個 byte 編成大寫的 %XX"
            else -> null
        }
    },
)
