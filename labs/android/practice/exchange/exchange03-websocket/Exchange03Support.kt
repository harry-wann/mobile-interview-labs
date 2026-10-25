// Exchange03WebSocket 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：JSON 文字轉物件、「線路上的 binary frame」（這套交易所公開 WebSocket 的每則訊息都是 gzip 壓縮）的壓縮與解壓。
// 這個單元的演練都不連真的伺服器：用程式裡的假伺服器，或直接準備好的訊息。
// JSON 用 Gson：Android 內建的 org.json 在 JVM 單元測試裡是空殼，Gson 在電腦和手機上行為一樣。
package tw.harry.labs.practice.exchange03websocket

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** JSON 文字轉成物件。不是 JSON 或不是物件時回傳 null */
fun e03Parse(text: String): JsonObject? =
    try {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }

/** 取欄位的小幫手：欄位不存在或型別不對時回傳 null */
fun JsonObject.e03Long(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull()
fun JsonObject.e03String(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
fun JsonObject.e03Object(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

/** 線路格式：每一則訊息是一個 gzip 壓縮的 binary frame */
object E03Wire {
    /** 假伺服器用：把 JSON 文字壓成 gzip 的 frame */
    fun frame(json: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** 其他演練用：把 gzip 的 frame 解開成文字，失敗回傳 null（演練 2 要你自己寫一份） */
    fun text(frame: ByteArray): String? =
        try {
            GZIPInputStream(ByteArrayInputStream(frame)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
}
