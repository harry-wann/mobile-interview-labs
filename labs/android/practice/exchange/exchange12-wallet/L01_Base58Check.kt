// L01：合法的比特幣地址被判定無效（Base58Check 解碼丟掉了開頭的 1）
// 對應複習站：Exchange12Wallet 第 2 題「加密資產的地址怎麼驗證？Base58Check、Bech32、EIP-55 是什麼？」
//
// 情境：比特幣傳統地址是 Base58Check：把字串當成 58 進位的大整數轉成位元組，第一個位元組是版本、最後 4 個位元組是校驗碼，
//        校驗碼 = 前面所有位元組做兩次 SHA-256 的前 4 個位元組。**開頭每個 `1` 代表一個 0x00 位元組**，轉成大整數時開頭的 0 會消失，要自己補回來。
//        大整數轉位元組（E12.base58Magnitude，不處理開頭的 0，BigInteger 多出來的符號位元組也處理好了）與校驗碼（E12.checksum4）已經寫好在 Exchange12Support.kt。
//        演練檢查 5 個地址（都是公開的測試地址）：比特幣創世區塊的地址 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa、另一個以 1 開頭的合法地址、
//        以 3 開頭的合法地址（P2SH），最後一個字元被改掉的地址（校驗碼不符），含有 0（不在字母表）的地址。
//
// 你要寫：
//   ① Bug 版（L01Bug.isValid）：bytes 直接用 magnitude（不補開頭的 0x00），之後檢查長度至少 5、校驗碼是否相符。
//   ② 修正版（L01Fixed.isValid）：數出 s 開頭連續幾個 '1'（takeWhile），bytes = 同樣數量的 0（ByteArray(count)）加上 magnitude，之後同樣檢查。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange12-wallet/L01_Base58Check.kt
package tw.harry.labs.practice.exchange12wallet

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L01Case(val name: String, val address: String, val expected: Boolean)

private val cases = listOf(
    L01Case("創世區塊的地址（以 1 開頭）", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", true),
    L01Case("另一個以 1 開頭的合法地址", "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", true),
    L01Case("以 3 開頭的合法地址（P2SH）", "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", true),
    L01Case("最後一個字元被改掉", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb", false),
    L01Case("含有 0（不在字母表）", "0A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", false),
)

/** 演練流程（兩個版本共用）：5 個地址逐一驗證，和正確答案比對 */
private fun scenario(log: LabLog, isValid: (String) -> Boolean) {
    var wrong = 0
    for (c in cases) {
        val result = isValid(c.address)
        val text = if (result) "合法" else "無效"
        if (result == c.expected) {
            log("${c.name}：$text ✓")
        } else {
            wrong++
            log("${c.name}：$text ✗（應該${if (c.expected) "合法" else "無效"}）")
        }
    }
    log("5 個地址，驗證結果錯的有 $wrong 個")
}

// MARK: - Bug 版
private object L01Bug {
    fun isValid(s: String): Boolean {
        val magnitude = E12.base58Magnitude(s) ?: return false
        // TODO ①：val bytes = magnitude（不補開頭的 0x00）
        val bytes = ByteArray(0)
        if (bytes.size < 5) return false                           // 至少要有 1 個版本位元組加 4 個校驗位元組
        return E12.checksum4(bytes.copyOfRange(0, bytes.size - 4)).contentEquals(bytes.copyOfRange(bytes.size - 4, bytes.size))
    }

    suspend fun run(log: LabLog) = scenario(log) { isValid(it) }
}

// MARK: - 修正版
private object L01Fixed {
    fun isValid(s: String): Boolean {
        val magnitude = E12.base58Magnitude(s) ?: return false
        // TODO ②：數出 s 開頭連續幾個 '1'（s.takeWhile），bytes = ByteArray(那個數量) + magnitude
        val bytes = ByteArray(0)
        if (bytes.size < 5) return false                           // 至少要有 1 個版本位元組加 4 個校驗位元組
        return E12.checksum4(bytes.copyOfRange(0, bytes.size - 4)).contentEquals(bytes.copyOfRange(bytes.size - 4, bytes.size))
    }

    suspend fun run(log: LabLog) = scenario(log) { isValid(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("5 個地址") }?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l01 = Lab(
    id = "L01",
    title = "合法的比特幣地址被判定無效",
    question = "第 2 題：加密資產的地址怎麼驗證？Base58Check、Bech32、EIP-55 是什麼？",
    goal = "Base58 解碼要把開頭的每個 1 補回成 0x00 位元組，否則以 1 開頭的地址會算錯校驗碼，被誤判為無效。",
    steps = listOf(
        "打開 practice/exchange/exchange12-wallet/L01_Base58Check.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 5 個地址哪些判斷錯了：以 1 開頭和以 3 開頭的地址有什麼不同？",
        "想一想：大整數 0x0062e9… 轉成位元組時，開頭的 00 會怎樣？校驗碼是用哪一段資料算的？",
        "寫 TODO ②（修正版），選「修正版」執行，5 個地址都要判斷對。",
        "進階：Kotlin 的 Byte 有正負號，版本位元組如果大於 127，要先 and 0xFF；BigInteger.toByteArray 可能多一個符號用的 0x00，也要處理（共用檔案已處理）。",
    ),
    expectBug = "兩個以 1 開頭的合法地址都被判成「無效 ✗」，以 3 開頭的剛好判對，校驗碼被改過的、含 0 的本來就無效所以也對：「5 個地址，驗證結果錯的有 2 個」。",
    expectFixed = "5 個地址都判斷對：「5 個地址，驗證結果錯的有 0 個」。",
    file = "exchange12-wallet/L01_Base58Check.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { isValid(it) } 執行"
            "以 3 開頭的合法地址（P2SH）：合法 ✓" !in lines -> "Bug 版對以 3 開頭的地址要判斷對（沒有開頭的 1，不補 0x00 也不影響）"
            n != 2 -> "Bug 還沒重現：不補開頭的 0x00，兩個以 1 開頭的合法地址都會被判成無效（目前錯 $n 個）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { isValid(it) } 執行"
            n != 0 -> "還有 $n 個地址判斷錯：數出開頭有幾個 1，在位元組前面補同樣數量的 0x00"
            else -> null
        }
    },
)
