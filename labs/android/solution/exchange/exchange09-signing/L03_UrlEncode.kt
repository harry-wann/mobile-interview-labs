// L03：帶了空白、冒號或 Base64 的請求，簽名就是不對（URL 編碼不是 RFC 3986）
// 對應複習站：Exchange09Signing 第 3 題「URL 編碼有哪些陷阱？」
//
// 情境：簽名字串裡每個參數的名稱與值都要做 URL 編碼，規則是 RFC 3986：只有 A-Z a-z 0-9 - . _ ~ 不編碼，
//        其他一律編成大寫的 %XX（非 ASCII 字元先轉 UTF-8 的 byte）。演練拿 6 個字串，和正確的編碼結果比對：
//        含空白與 & = + 的字串、Timestamp（有冒號）、é、日文、含 / * ~ 的字串，以及 Base64 的簽名（要放進 query 的 Signature）。
// Bug 版：用 Java 的 URLEncoder.encode(s, "UTF-8")。它是 HTML 表單的編碼規則（application/x-www-form-urlencoded）：
//        空白變成 +（簽名需要 %20）、* 不編碼（簽名需要 %2A）、~ 被編成 %7E（簽名要保留 ~）。
// 修正版：自己逐個 UTF-8 byte 處理：unreserved 字元原樣保留，其他編成大寫的 %XX。
package tw.harry.labs.solution.exchange09signing

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
        return URLEncoder.encode(s, "UTF-8")        // 表單編碼：空白變 +、* 不編碼、~ 變 %7E
    }

    suspend fun run(log: LabLog) = scenario(log) { encode(it) }
}

// MARK: - 修正版
private object L03Fixed {
    fun encode(s: String): String {
        val hex = "0123456789ABCDEF"                                // 大寫的十六進位
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {                  // 逐個 UTF-8 byte 處理
            val v = b.toInt() and 0xFF                              // Byte 有正負號，先轉成 0..255
            val c = v.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c)                                       // unreserved 字元不編碼
            } else {
                out.append('%').append(hex[v shr 4]).append(hex[v and 0x0F])
            }
        }
        return out.toString()
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
        "選「Bug 版」執行，看 6 個字串哪些編錯了：空白、/、*、~。",
        "想一想：URLEncoder 原本是拿來做什麼的？表單編碼和 RFC 3986 差在哪裡？",
        "選「修正版」執行，6 個字串都要和正確答案一致。",
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
