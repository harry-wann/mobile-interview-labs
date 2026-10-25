// L02：連上之後每一則訊息都解析失敗（WebSocket 的訊息是 gzip 壓縮的 binary
// frame）
// 對應複習站：Exchange03WebSocket 第 2 題「為什麼每則訊息都要先解壓縮？iOS 和
// Android 怎麼解 gzip？」
//
// 情境：這套交易所公開 WebSocket 的每一則訊息都是 gzip 壓縮過的 binary frame，
// 不是文字。假伺服器送來三則：
//        一則 ping、一則最佳買賣價（BBO）的推送、一則訂閱確認。App 要先把
// frame 解成文字，才能解析 JSON。
// Bug 版：把 frame 當成 UTF-8 文字：String(frame, Charsets.UTF_8)。
// 每一則都是亂碼，JSON 解析全部失敗。
// 修正版：用 GZIPInputStream 解壓（Android／Java 認得 gzip 的標頭與結尾，
// 不用自己處理），再讀成 UTF-8 文字。
package tw.harry.labs.solution.exchange03websocket

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val messages = listOf(
    """{"ping":18212558000}""",
    """{"ch":"market.btcjpy.bbo","ts":1630994555540,"tick":{"seqId":137005210233,"ask":10001000,"askSize":1.502181,"bid":10000000,"bidSize":0.178567,"symbol":"btcjpy"}}""",
    """{"id":"id1","status":"ok","subbed":"market.btcjpy.bbo","ts":1489474081631}""",
)

/**
 * 演練流程（兩個版本共用）：假伺服器依序送三個 frame，App 解成文字再解析 JSON
 */
private fun scenario(log: LabLog, decode: (ByteArray) -> String) {
    var parsed = 0
    for ((index, message) in messages.withIndex()) {
        val frame = E03Wire.frame(message)
        val text = decode(frame)
        if (e03Parse(text) != null) {
            parsed++
            log("第 ${index + 1} 則（${frame.size} 位元組）：解析成功 ${text.take(40)}…")
        } else {
            log(
                "第 ${index + 1} 則（${frame.size} 位元組）：解析失敗（轉成的文字長度 ${text.length}）",
            )
        }
    }
    log("共 ${messages.size} 則，成功解析 $parsed 則")
}

// MARK: - Bug 版
private object L02Bug {
    fun decode(frame: ByteArray): String = // 把 binary frame 當成文字
        String(frame, Charsets.UTF_8)

    suspend fun run(log: LabLog) = scenario(log, ::decode)
}

// MARK: - 修正版
private object L02Fixed {
    fun decode(frame: ByteArray): String = try {
        // 先解壓，再讀成文字
        GZIPInputStream(
            ByteArrayInputStream(frame),
        ).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
    } catch (e: Exception) {
        // 不是 gzip：丟掉這一則，不讓連線崩潰
        ""
    }

    suspend fun run(log: LabLog) = scenario(log, ::decode)
}

// MARK: - 演練設定
private fun parsedCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("共 3 則")
}?.let { Regex("(\\d+) 則$").find(it)?.groupValues?.get(1)?.toInt() }

val l02 = Lab(
    id = "L02",
    title = "連上之後每一則訊息都解析失敗",
    question = "第 2 題：為什麼每則訊息都要先解壓縮？iOS 和 Android 怎麼解 gzip？",
    goal = "WebSocket 收到的是 gzip 壓縮的 binary frame。先解壓成文字，才能解析 JSON。",
    steps = listOf(
        "選「Bug 版」執行，看三則訊息的解析結果、轉出來的文字是什麼。",
        "想一想：frame 的前兩個位元組是 0x1f 0x8b，它是文字嗎？",
        "選「修正版」執行，三則都要解析成功。",
        "進階：iOS 的演練比較麻煩，因為 Apple 的解壓只認裸的 DEFLATE，要自己切掉 gzip 的標頭和結尾；Android 的 GZIPInputStream 直接處理。解壓請在 OkHttp 的背景執行緒做，失敗的訊息丟掉就好，不要讓連線崩潰。",
    ),
    expectBug = "三則都是「解析失敗（轉成的文字長度 40 左右）」，「共 3 則，成功解析 0 則」。",
    expectFixed = "「共 3 則，成功解析 3 則」，第一則解出 {\"ping\":18212558000}。",
    file = "exchange03-websocket/L02_Gunzip.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = parsedCount(lines)
        when {
            n == null -> {
                "演練沒有跑完：要用 scenario(log, ::decode) 執行"
            }

            lines.none { "解析失敗（轉成的文字長度" in it && !it.endsWith("長度 0）") } -> {
                "Bug 還沒重現：decode 要把 frame 直接當成 UTF-8 文字（String(frame, Charsets.UTF_8)），轉出來是亂碼，不是空字串"
            }

            n != 0 -> {
                "Bug 還沒重現：沒解壓就當文字，JSON 應該全部解析失敗（目前成功 $n 則）"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val n = parsedCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::decode) 執行"
            n != 3 -> "還有 ${3 - n} 則解析失敗：用 GZIPInputStream(ByteArrayInputStream(frame)) 解壓，再 bufferedReader(Charsets.UTF_8).readText()"
            else -> null
        }
    },
)
