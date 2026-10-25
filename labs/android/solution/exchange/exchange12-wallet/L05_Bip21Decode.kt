// L05：付款連結的備註 a+b 變成「a b」，不完整的百分比編碼也解出亂碼（BIP21 的百分比解碼）
// 對應複習站：Exchange12Wallet 第 4 題「掃描 QR 得到的付款連結（BIP21）要怎麼解析？」
//
// 情境：BIP21 的參數值用百分比編碼（RFC 3986）：`%XX` 是一個位元組，中文等字元是 UTF-8 的位元組。**`+` 在這裡是字面的加號，不是空白**
//        （網頁表單的規則才是 + 當空白，Java 的 URLDecoder 就是用表單的規則）。解碼後必須是合法的 UTF-8，不完整的 `%` 序列要視為無效。
//        連結的切分（scheme、地址、query）已經寫好在 Exchange12Support.kt，這題只寫「單一個參數值怎麼解碼」。
//        演練檢查 7 個值：Luke-Jr、Donation%20for%20project%20xyz、a+b、%E5%92%96%E5%95%A1（咖啡）、
//        %E5%92（UTF-8 只有一半）、100%（% 後面沒有兩個十六進位字元）、%zz（不是十六進位）。
// Bug 版：用 Java 的 URLDecoder.decode(raw, "UTF-8")（表單的規則）：a+b 變成「a b」，備註被悄悄改掉；
//        只有半個字元的 %E5%92 不會丟例外，而是被解成替代字元 U+FFFD（畫面上是一個菱形問號）；只有不完整的 % 序列才會丟例外（這裡當成無效）。
// 修正版：自己逐個位元組掃描：遇到 % 就讀兩個十六進位字元還原成位元組，+ 原樣保留；最後用嚴格的 UTF-8 解碼器檢查，不合法就回傳 null。
package tw.harry.labs.solution.exchange12wallet

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** （原始的參數值, 正確的解碼結果；null 代表無效） */
private val cases = listOf(
    "Luke-Jr" to "Luke-Jr",
    "Donation%20for%20project%20xyz" to "Donation for project xyz",
    "a+b" to "a+b",
    "%E5%92%96%E5%95%A1" to "咖啡",
    "%E5%92" to null,
    "100%" to null,
    "%zz" to null,
)

private fun shown(value: String?) = if (value == null) "無效" else "\"$value\""

/** 演練流程（兩個版本共用）：7 個值逐一解碼，和正確答案比對 */
private fun scenario(log: LabLog, decode: (String) -> String?) {
    var wrong = 0
    for ((raw, expected) in cases) {
        val result = decode(raw)
        if (result == expected) {
            log("解碼 \"$raw\" → ${shown(result)} ✓")
        } else {
            wrong++
            log("解碼 \"$raw\" → ${shown(result)} ✗（應該是 ${shown(expected)}）")
        }
    }
    log("共 7 個值，解碼結果錯的有 $wrong 個")
}

// MARK: - Bug 版
private object L05Bug {
    fun decode(raw: String): String? {
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()      // 表單的規則：+ 變成空白；半個字元變成亂碼
    }

    suspend fun run(log: LabLog) = scenario(log) { decode(it) }
}

// MARK: - 修正版
private object L05Fixed {
    fun decode(raw: String): String? {
        val input = raw.toByteArray(Charsets.UTF_8)
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            if (b == 0x25) {                                                    // %：後面要有兩個十六進位字元
                if (i + 2 >= input.size) return null
                val high = hexValue(input[i + 1].toInt() and 0xFF) ?: return null
                val low = hexValue(input[i + 2].toInt() and 0xFF) ?: return null
                bytes.write(high * 16 + low)
                i += 3
            } else {
                bytes.write(b)                                                  // 其他位元組（包含 +）原樣保留
                i += 1
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)                         // 不合法的 UTF-8（例如只有半個字元）要報錯，不要變成亂碼
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private fun hexValue(b: Int): Int? = when (b) {
        in 0x30..0x39 -> b - 0x30
        in 0x41..0x46 -> b - 0x41 + 10
        in 0x61..0x66 -> b - 0x61 + 10
        else -> null
    }

    suspend fun run(log: LabLog) = scenario(log) { decode(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("共 7 個值") }?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l05 = Lab(
    id = "L05",
    title = "付款連結的備註 a+b 變成 a b",
    question = "第 4 題：掃描 QR 得到的付款連結（BIP21）要怎麼解析？",
    goal = "BIP21 的百分比解碼，+ 是字面的加號，不是空白；解碼後要是合法的 UTF-8，不完整的 % 序列視為無效。掃描到的內容是不可信的輸入，要嚴格解析。",
    steps = listOf(
        "選「Bug 版」執行，看 7 個值哪些解碼錯了（iOS 版的結果少一個）。",
        "想一想：a+b 是使用者想傳的備註，變成 a b 之後，金額沒變，但資訊被悄悄改了；如果是地址的一部分呢？",
        "選「修正版」執行，7 個值都要和正確答案一致。",
        "進階：掃到的 amount 也要用 BigDecimal 重新驗證，不要用 Double；掃描只填入欄位，不自動送出。",
    ),
    expectBug = "「解碼 \"a+b\" → \"a b\" ✗」，只有半個字元的 %E5%92 被解成替代字元 U+FFFD（不是「無效」）✗，其他 5 個剛好對（不完整的 % 序列 URLDecoder 會丟例外，這裡當成無效）：「共 7 個值，解碼結果錯的有 2 個」。iOS 的 removingPercentEncoding 對不合法的序列回傳 nil，所以只錯 a+b 一個。",
    expectFixed = "7 個值都和正確答案一致：a+b 保持 \"a+b\"，咖啡解出中文，三個不完整的序列都是「無效」：「共 7 個值，解碼結果錯的有 0 個」。",
    file = "exchange12-wallet/L05_Bip21Decode.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { decode(it) } 執行"
            "解碼 \"%E5%92%96%E5%95%A1\" → \"咖啡\" ✓" !in lines -> "Bug 版也要能解出百分比編碼的中文（URLDecoder）"
            "解碼 \"a+b\" → \"a b\" ✗（應該是 \"a+b\"）" !in lines -> "Bug 還沒重現：URLDecoder 把 + 當成空白，a+b 會變成 a b"
            n < 1 -> "Bug 還沒重現：至少有一個值解碼錯"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { decode(it) } 執行"
            n != 0 -> "還有 $n 個值解碼錯：+ 原樣保留；% 後面要有兩個十六進位字元；解出來要是合法的 UTF-8"
            else -> null
        }
    },
)
