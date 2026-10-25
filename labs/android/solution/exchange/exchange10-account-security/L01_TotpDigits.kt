// L01：驗證碼有時候少一位、有時候是負數（TOTP 的動態截斷沒清最高位元、
// 沒有補零）
// 對應複習站：Exchange10AccountSecurity 第 1 題「兩步驟驗證的 6
// 位數驗證碼（TOTP）是怎麼算出來的？」
//
// 情境：TOTP（RFC 6238）把「現在是第幾個 30 秒」當 counter，用秘密算
// HMAC-SHA-1（20 個位元組），再做動態截斷：
//        取最後一個位元組的低 4 位元當 offset，從 offset 取 4 個位元組，
// 最高位元清成 0（and 0x7f），對 1,000,000 取餘數，不足 6 位時左邊補零。
//        演練拿 RFC 6238 附錄 B 的 6 組測試資料（秘密 12345678901234567890）：
// 時間 59、1111111109、1111111111、1234567890、2000000000、20000000000 秒，
//        答案分別是 287082、081804、050471、005924、279037、353130。HMAC
// 已經算好（在 Exchange10Support.kt），這題只寫「20 個位元組 → 6 位數碼」。
// Bug 版：取 4 個位元組組成整數時，第一個位元組沒有清最高位元（沒有 and
// 0x7f），取餘數後直接轉成文字，沒有補零。
//        Kotlin 的 Int 有正負號：最高位元是 1 的資料（6 組裡有 4 組）
// 整數變成負數，取餘數的結果是負數；開頭是 0 的碼（081804）變成 5 位數。
// 修正版：第一個位元組先 and 0x7f 再組成整數；取後 6 位之後用 padStart(6, '0')
// 補零。
package tw.harry.labs.solution.exchange10accountsecurity

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** RFC 6238 附錄 B 的 SHA-1 測試資料：時間（Unix 秒）與 6 位數答案 */
private val vectors = listOf(
    59L to "287082",
    1_111_111_109L to "081804",
    1_111_111_111L to "050471",
    1_234_567_890L to "005924",
    2_000_000_000L to "279037",
    20_000_000_000L to "353130",
)

/** 演練流程（兩個版本共用）：6 組時間逐一算出驗證碼，和 RFC 的答案比對 */
private fun scenario(log: LabLog, code: (ByteArray) -> String) {
    var wrong = 0
    for ((time, expected) in vectors) {
        // counter = 第幾個 30 秒
        val mac = E10.mac(E10.rfcSecret, time / 30)
        val result = code(mac)
        if (result == expected) {
            log("時間 $time 秒 → 驗證碼 $result ✓")
        } else {
            wrong++
            log("時間 $time 秒 → 驗證碼 $result ✗（RFC 的答案是 $expected）")
        }
    }
    log("共 6 組 RFC 6238 測試資料，算錯的有 $wrong 組")
}

// MARK: - Bug 版
private object L01Bug {
    fun code(mac: ByteArray): String {
        // 最後一個位元組的低 4 位元
        val offset = mac[mac.size - 1].toInt() and 0x0f
        // 沒有 and 0x7f：最高位元可能是 1，Int 變成負數
        val binary = (mac[offset].toInt() shl 24) or
            ((mac[offset + 1].toInt() and 0xff) shl 16) or
            ((mac[offset + 2].toInt() and 0xff) shl 8) or
            (mac[offset + 3].toInt() and 0xff)
        // 沒有補零：081804 變成 81804
        return (binary % 1_000_000).toString()
    }

    suspend fun run(log: LabLog) = scenario(log) { code(it) }
}

// MARK: - 修正版
private object L01Fixed {
    fun code(mac: ByteArray): String {
        val offset = mac[mac.size - 1].toInt() and 0x0f
        // 第一個位元組 and 0x7f：最高位元清成 0
        val binary = ((mac[offset].toInt() and 0x7f) shl 24) or
            // 其他位元組 and 0xff：Byte 有正負號
            ((mac[offset + 1].toInt() and 0xff) shl 16) or
            ((mac[offset + 2].toInt() and 0xff) shl 8) or
            (mac[offset + 3].toInt() and 0xff)
        // 補零到 6 位
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    suspend fun run(log: LabLog) = scenario(log) { code(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("共 6 組")
}?.let { Regex("(\\d+) 組$").find(it)?.groupValues?.get(1)?.toInt() }

val l01 = Lab(
    id = "L01",
    title = "驗證碼有時少一位、有時對不上",
    question = "第 1 題：兩步驟驗證的 6 位數驗證碼（TOTP）是怎麼算出來的？",
    goal = "動態截斷要把最高位元清成 0，取後 6 位之後要左邊補零。用 RFC 6238 附錄 B 的測試資料驗證，是確認 TOTP 寫對的標準做法。",
    steps = listOf(
        "選「Bug 版」執行，看 6 組裡哪些算錯了：開頭是 0 的碼變成幾位？最高位元是 1 的資料算出什麼（負數？）？",
        "想一想：081804 轉成整數是什麼？為什麼要 and 0x7f？Kotlin 的 Int 最高位元是 1 的時候代表什麼？",
        "選「修正版」執行，6 組都要和 RFC 的答案一致。",
        "進階：伺服器端驗證時，要容許前後各 1 個 time step、同一個 counter 只能用一次，見下一個演練。",
    ),
    expectBug = "時間 1111111109 秒算出 81804（應該是 081804，少了開頭的 0）；時間 59、1111111111、1234567890、20000000000 秒的最高位元是 1，Int 變成負數，算出負的數字；只有 2000000000 秒剛好對：「共 6 組 RFC 6238 測試資料，算錯的有 5 組」。",
    expectFixed = "6 組都和 RFC 的答案一致：287082、081804、050471、005924、279037、353130，「共 6 組 RFC 6238 測試資料，算錯的有 0 組」。",
    file = "exchange10-account-security/L01_TotpDigits.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> {
                "演練沒有跑完：要用 scenario(log) { mac -> … } 執行"
            }

            lines.none {
                it.startsWith("時間 2000000000 秒") && it.endsWith("✓")
            } -> {
                "Bug 版也要算出大部分的碼：時間 2000000000 秒的結果應該是 279037（這一組不需要補零，最高位元也是 0）"
            }

            "時間 1111111109 秒 → 驗證碼 81804 ✗（RFC 的答案是 081804）" !in lines -> {
                "Bug 還沒重現：取餘數之後直接轉成文字、不補零，081804 會變成 81804"
            }

            n < 3 -> {
                "Bug 還沒重現：至少有 3 組開頭是 0 的碼會算錯（目前錯 $n 組）"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { mac -> … } 執行"
            n != 0 -> "還有 $n 組算錯：第一個位元組要先 and 0x7f 清掉最高位元；取餘數後用 padStart(6, '0') 補零到 6 位"
            else -> null
        }
    },
)
