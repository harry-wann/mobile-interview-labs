// Exchange10AccountSecurity 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：RFC 4226／6238 的 HMAC-SHA-1 與完整的 HOTP（驗證碼算法的正確版本）、
// HMAC-SHA256 的十六進位字串（示範用的「裝置簽章」）。
// 秘密與金鑰都是 RFC 的測試資料或演練用的假資料，不是真的帳號。
package tw.harry.labs.solution.exchange10accountsecurity

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object E10 {
    /** RFC 6238 附錄 B 的秘密（SHA-1 版）：文字 12345678901234567890 */
    val rfcSecret: ByteArray = "12345678901234567890".toByteArray(
        Charsets.US_ASCII,
    )

    /**
     * HMAC-SHA-1：金鑰是秘密，資料是 8 個位元組的 counter（ByteBuffer 預設就是
     * big-endian）。結果是 20 個位元組。
     */
    fun mac(secret: ByteArray, counter: Long): ByteArray {
        val message = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        return mac.doFinal(message)
    }

    /** 正確的 HOTP（RFC 4226）：動態截斷、清掉最高位元、取後 6 位並補零 */
    fun hotp(secret: ByteArray, counter: Long): String {
        val h = mac(secret, counter)
        val offset = h[h.size - 1].toInt() and 0x0f
        // Kotlin 的 Byte 有正負號，所以每個位元組都要先 and 0xff，再組成整數
        val binary = ((h[offset].toInt() and 0x7f) shl 24) or
            ((h[offset + 1].toInt() and 0xff) shl 16) or
            ((h[offset + 2].toInt() and 0xff) shl 8) or
            (h[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    /**
     * HMAC-SHA256 的十六進位字串。演練用它代替「裝置私鑰的簽章」（真實的 App
     * 是 Keystore 裡的私鑰簽章）。
     */
    fun hmacHex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(
            message.toByteArray(Charsets.UTF_8),
        ).joinToString("") {
            "%02x".format(it)
        }
    }
}
