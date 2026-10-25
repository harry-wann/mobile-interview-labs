// L05：簽名失敗時為了除錯寫的 log，把 Secret Key
// 和完整簽名都留在紀錄裡了（金鑰不能進 log）
// 對應複習站：Exchange09Signing 第 6
// 題「簽名的程式要怎麼測試？金鑰要怎麼保管？」
//
// 情境：簽名請求被伺服器退回（api-signature-not-valid）。工程師為了找原因，在
// App 寫了一行「請求紀錄」，
//        這一行會進 App 的 log（Logcat）、crash 報告，
// 也可能被使用者截圖給客服。
//        演練給 describe 這個函式 4 樣東西：method、完整網址（含 AccessKeyId
// 與 Signature）、簽名字串、Secret Key，
//        由它決定「要寫進紀錄的那一行」；然後掃描這一行有沒有洩漏 Secret Key
// 或完整的 Signature。
// Bug 版：把網址、簽名字串、Secret Key 全部寫進紀錄。Secret Key 一旦進了 log，
// 任何看得到 log 的人都能偽造這個帳號的所有簽名請求。
// 修正版：只寫「method 加 path」、遮罩過的 AccessKeyId（前 5 碼加後 4 碼），
// Signature 寫成 ***；Secret Key 與簽名字串完全不寫。
package tw.harry.labs.solution.exchange09signing

import java.net.URI
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 演練流程（兩個版本共用）：簽一個請求，產生要寫進 log 的那一行，
 * 再掃描它有沒有洩漏
 */
private fun scenario(
    log: LabLog,
    describe: (
        method: String,
        url: String,
        stringToSign: String,
        secret: String,
    ) -> String,
) {
    val query = "AccessKeyId=${E09.ACCESS_KEY}&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=2017-05-11T15%3A19%3A30&order-id=1234567890"
    val stringToSign = "GET\n${E09.HOST}\n/v1/order/orders\n$query"
    val signature = E09.hmacBase64(stringToSign)
    val url = "https://${E09.HOST}/v1/order/orders?$query&Signature=${E09.percentEncode(
        signature,
    )}"
    val line = describe("GET", url, stringToSign, E09.SECRET)
    log("[App log] $line")
    val leaksSecret = line.contains(E09.SECRET)
    val leaksSignature =
        line.contains(signature) || line.contains(E09.percentEncode(signature))
    log(
        "掃描這一行：Secret Key ${if (leaksSecret) "洩漏" else "沒有洩漏"}，完整的 Signature ${if (leaksSignature) "洩漏" else "沒有洩漏"}",
    )
}

// MARK: - Bug 版
private object L05Bug {
    fun describe(
        method: String,
        url: String,
        stringToSign: String,
        secret: String,
    ): String {
        // 什麼都印出來，方便除錯
        return "$method $url stringToSign=$stringToSign secret=$secret"
    }

    suspend fun run(log: LabLog) = scenario(log) {
        method,
        url,
        stringToSign,
        secret,
        ->
        describe(method, url, stringToSign, secret)
    }
}

// MARK: - 修正版
private object L05Fixed {
    fun describe(
        method: String,
        url: String,
        stringToSign: String,
        secret: String,
    ): String {
        val path = URI(url).path
        val key = E09.ACCESS_KEY
        // 前 5 碼加後 4 碼：認得出是哪把，但不是完整的金鑰
        val masked = "${key.take(5)}…${key.takeLast(4)}"
        // Secret Key、簽名字串、完整簽名一個都不寫
        return "$method $path AccessKeyId=$masked Signature=***"
    }

    suspend fun run(log: LabLog) = scenario(log) {
        method,
        url,
        stringToSign,
        secret,
        ->
        describe(method, url, stringToSign, secret)
    }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "除錯用的 log 把 Secret Key 留下來了",
    question = "第 6 題：簽名的程式要怎麼測試？金鑰要怎麼保管？",
    goal = "Secret Key 和完整的簽名不能出現在 log、crash 報告或截圖裡；log 只留認得出是哪個請求的資訊，金鑰與簽名要遮罩。",
    steps = listOf(
        "選「Bug 版」執行，看「[App log]」那一行有哪些東西，再看掃描的結果。",
        "想一想：如果使用者把這一行貼給客服、或 crash 報告被上傳，會發生什麼事？Secret Key 只在建立時顯示一次，洩漏了要怎麼辦？",
        "選「修正版」執行，那一行還認得出是哪個請求，但掃描結果都要是「沒有洩漏」。",
        "進階：跟 crash 報告一樣，分析工具（analytics）的事件參數、網路除錯用的 proxy log 也都不能含金鑰。",
    ),
    expectBug = "「[App log] GET https://api.example-exchange.com/v1/order/orders?AccessKeyId=…&Signature=… stringToSign=… secret=test-secret-key-0001」，「掃描這一行：Secret Key 洩漏，完整的 Signature 洩漏」。",
    expectFixed = "「[App log] GET /v1/order/orders AccessKeyId=test-…0001 Signature=***」，「掃描這一行：Secret Key 沒有洩漏，完整的 Signature 沒有洩漏」。",
    file = "exchange09-signing/L05_SecretInLog.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("掃描這一行")
            } -> "演練沒有跑完：要用 scenario(log) { method, url, stringToSign, secret -> … } 執行"

            "掃描這一行：Secret Key 洩漏，完整的 Signature 洩漏" !in lines -> "Bug 還沒重現：這一行要同時包含 url（裡面有 Signature）和 secret"

            else -> null
        }
    },
    checkFixed = { lines ->
        val line = lines.firstOrNull { it.startsWith("[App log] ") }
        when {
            lines.none { it.startsWith("掃描這一行") } -> {
                "演練沒有跑完：要用 scenario(log) { method, url, stringToSign, secret -> … } 執行"
            }

            line == null || !line.contains("GET /v1/order/orders") ||
                !line.contains("Signature=***") -> {
                "紀錄要認得出是哪個請求：寫出 method 和 path（GET /v1/order/orders），並把 Signature 寫成 ***"
            }

            !line.contains("AccessKeyId=test-…0001") -> {
                "AccessKeyId 要遮罩：只留前 5 碼和後 4 碼，中間用 …"
            }

            "掃描這一行：Secret Key 沒有洩漏，完整的 Signature 沒有洩漏" !in lines -> {
                "不能出現 Secret Key，也不能出現完整的 Signature（簽名字串、網址整段都不能寫）"
            }

            else -> {
                null
            }
        }
    },
)
