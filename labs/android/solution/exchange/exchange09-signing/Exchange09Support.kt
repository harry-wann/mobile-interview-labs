// Exchange09Signing 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：HMAC-SHA256 加 Base64、符合 RFC 3986 的 URL 編碼、REST 的 4
// 個認證參數、test vector，
//      以及一個假伺服器（照 Signature V2、私有 WebSocket 的 2.1 規則，
// 用自己保存的 Secret Key 重新算一次簽名）。
// 金鑰、host、時間都是演練用的假資料（複習站的 test vector），不是真的帳號。
package tw.harry.labs.solution.exchange09signing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object E09 {
    const val HOST = "api.example-exchange.com"
    const val ACCESS_KEY = "test-access-key-0001"
    const val SECRET = "test-secret-key-0001"

    /** REST test vector 用的時間戳記 */
    const val TIMESTAMP = "2017-05-11T15:19:30"

    /**
     * GET /v1/order/orders?order-id=1234567890 的簽名（複習站第 1 題，用
     * openssl 交叉確認過）
     */
    const val GET_VECTOR = "KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg="

    /** POST /v1/order/orders/place（只簽 4 個認證參數）的簽名 */
    const val POST_VECTOR = "zwVrzld/U+nC8m9CEzxGWbX7TMyGsK6AgtkPFEHCOXM="

    /** 私有 WebSocket（/ws/v2、版本 2.1）的 test vector：時間戳記與簽名 */
    const val WS_TIMESTAMP = "2019-09-01T18:16:16"
    const val WS_VECTOR = "+x+yn2NZlLnGl0RT5sniij34+6ooPrxqiSJ2u2GC7OU="

    /**
     * HMAC-SHA256，再轉 Base64。金鑰與字串都用 UTF-8 的 byte，不 trim、
     * 不重新編碼。
     */
    fun hmacBase64(text: String, secret: String = SECRET): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"),
        )
        return Base64.getEncoder().encodeToString(
            mac.doFinal(text.toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * RFC 3986 的 URL 編碼：只有 A-Z a-z 0-9 -  _ ~ 不編碼，其他逐個 UTF-8
     * byte 編成大寫的 %XX。
     */
    fun percentEncode(s: String): String {
        val hex = "0123456789ABCDEF"
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            // Byte 有正負號，先轉成 0..255
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' ||
                c == '.' ||
                c == '_' ||
                c == '~'
            ) {
                out.append(c)
            } else {
                out.append('%').append(hex[v shr 4]).append(hex[v and 0x0F])
            }
        }
        return out.toString()
    }

    /** REST（Signature V2）的 4 個認證參數 */
    fun authParams(timestamp: String = TIMESTAMP): List<Pair<String, String>> =
        listOf(
            "AccessKeyId" to ACCESS_KEY,
            "SignatureMethod" to "HmacSHA256",
            "SignatureVersion" to "2",
            "Timestamp" to timestamp,
        )

    // MARK: 假伺服器

    private val byteOrder = Comparator<Pair<String, String>> { a, b ->
        val x = a.first.toByteArray(Charsets.UTF_8)
        val y = b.first.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return@Comparator d
        }
        x.size - y.size
    }

    /** 表單式的解碼（伺服器常見的做法：+ 當成空白，%XX 還原成 byte） */
    private fun formDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }

    private fun query(params: List<Pair<String, String>>): String =
        params.sortedWith(byteOrder).joinToString("&") {
            "${percentEncode(it.first)}=${percentEncode(it.second)}"
        }

    /**
     * REST：收到網址上的 query（含 Signature），照規則重新算一次。一致回傳
     * null，不一致回傳錯誤碼。
     */
    fun verifyRest(
        method: String,
        host: String,
        path: String,
        rawQuery: String,
    ): String? {
        var received: String? = null
        val params = mutableListOf<Pair<String, String>>()
        for (part in rawQuery.split("&")) {
            val index = part.indexOf('=')
            if (index < 0) return "api-signature-not-valid"
            val name = formDecode(part.substring(0, index))
            val value = formDecode(part.substring(index + 1))
            if (name ==
                "Signature"
            ) {
                received = value
            } else {
                params += name to value
            }
        }
        if (received == null ||
            params.none {
                it.first == "SignatureVersion" && it.second == "2"
            }
        ) {
            return "api-signature-not-valid"
        }
        val expected =
            hmacBase64("$method\n${host.lowercase()}\n$path\n${query(params)}")
        return if (expected == received) null else "api-signature-not-valid"
    }

    /**
     * 私有 WebSocket：收到 auth 訊息的 JSON。一致回傳 null，否則回傳錯誤說明。
     */
    fun verifyWS(json: String): String? {
        val params: JsonObject = try {
            JsonParser.parseString(json).asJsonObject.getAsJsonObject("params")
        } catch (e: Exception) {
            return "auth fail: 訊息格式不對"
        }
        fun field(name: String): String? =
            params.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        for (key in listOf(
            "accessKey",
            "signatureMethod",
            "signatureVersion",
            "timestamp",
            "signature",
        )) {
            if (field(key) == null) return "auth fail: 缺少欄位 $key"
        }
        if (field("signatureVersion") !=
            "2.1"
        ) {
            return "auth fail: signatureVersion 必須是 2.1"
        }
        val signed = listOf(
            "accessKey",
            "signatureMethod",
            "signatureVersion",
            "timestamp",
        ).map {
            it to
                field(it)!!
        }
        val expected = hmacBase64("GET\n$HOST\n/ws/v2\n${query(signed)}")
        return if (expected ==
            field("signature")
        ) {
            null
        } else {
            "auth fail: signature 不一致"
        }
    }
}
