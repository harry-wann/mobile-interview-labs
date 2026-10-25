// Exchange12Wallet 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：Base58 解碼成位元組（大整數轉位元組，不處理開頭的 0）、雙 SHA-256 的前 4 個位元組、Bech32／Bech32m 的校驗碼（polymod）、
//      BIP21 付款連結的切分（scheme、地址、query 的原始 key／value）。
// 地址都是公開的測試地址（BIP 文件與比特幣創世區塊的地址），不是任何人的真實地址。
package tw.harry.labs.practice.exchange12wallet

import java.math.BigInteger
import java.security.MessageDigest

object E12 {
    // MARK: Base58Check

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /**
     * 把 Base58 字串當成 58 進位的大整數，轉成位元組（大端序）。
     * 注意：開頭的 `1` 代表 0x00 位元組，轉成大整數時會消失，這個函式不會補回來；字元不在字母表裡回傳 null。
     */
    fun base58Magnitude(s: String): ByteArray? {
        var n = BigInteger.ZERO
        for (c in s) {
            val digit = BASE58.indexOf(c)
            if (digit < 0) return null
            n = n * BigInteger.valueOf(58) + BigInteger.valueOf(digit.toLong())
        }
        if (n.signum() == 0) return ByteArray(0)
        val raw = n.toByteArray()                                   // BigInteger.toByteArray 可能多一個符號用的 0x00，要去掉
        return if (raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    /** 校驗碼：對 payload 連續做兩次 SHA-256，取前 4 個位元組 */
    fun checksum4(payload: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        return sha.digest(sha.digest(payload)).copyOfRange(0, 4)
    }

    // MARK: Bech32 / Bech32m

    enum class Bech32Kind { Bech32, Bech32m }

    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun polymod(values: List<Int>): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if (((top shr i) and 1) == 1) chk = chk xor generator[i]
        }
        return chk
    }

    /**
     * 檢查校驗碼。傳進來的字串必須已經是全小寫（大小寫混用的檢查由呼叫端負責）。
     * 只檢查校驗碼，不檢查 witness 版本與資料長度。校驗碼不對或字元不合法回傳 null。
     */
    fun bech32Kind(lowercased: String): Bech32Kind? {
        val sep = lowercased.lastIndexOf('1')
        if (sep < 1) return null
        val hrp = lowercased.substring(0, sep)
        val dataPart = lowercased.substring(sep + 1)
        if (dataPart.length < 6) return null
        val values = dataPart.map { c -> BECH32_CHARSET.indexOf(c).also { if (it < 0) return null } }
        val expanded = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + values
        return when (polymod(expanded)) {
            1 -> Bech32Kind.Bech32
            0x2bc830a3 -> Bech32Kind.Bech32m
            else -> null
        }
    }

    // MARK: BIP21

    /** BIP21 付款連結的切分結果：地址，以及 query 裡每一個參數的原始 key 與 value（還沒做百分比解碼） */
    class Bip21Parts(val address: String, val query: List<Pair<String, String>>)

    /** scheme 不分大小寫（`bitcoin:`）；地址到 `?` 為止；query 用 `&` 分隔、第一個 `=` 分出 key 與 value。不是 bitcoin 連結回傳 null。 */
    fun splitBip21(uri: String): Bip21Parts? {
        val prefix = "bitcoin:"
        if (!uri.lowercase().startsWith(prefix)) return null
        val rest = uri.substring(prefix.length)
        val q = rest.indexOf('?')
        val address = if (q < 0) rest else rest.substring(0, q)
        if (address.isEmpty()) return null
        val query = mutableListOf<Pair<String, String>>()
        if (q >= 0) {
            for (part in rest.substring(q + 1).split("&")) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                query += if (eq < 0) part to "" else part.substring(0, eq) to part.substring(eq + 1)
            }
        }
        return Bip21Parts(address, query)
    }
}
