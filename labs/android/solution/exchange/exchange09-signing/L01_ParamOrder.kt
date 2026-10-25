// L01：明明有排序，簽名還是被拒絕（參數排序要用 ASCII 順序）
// 對應複習站：Exchange09Signing 第 1 題「為什麼 API 請求要簽名？這套交易所 API 的 Signature V2 怎麼做？」
//
// 情境：Signature V2 要求把參數「依 ASCII 順序」排序，再串成簽名字串。演練送出兩個簽名請求：
//        ① 複習站的 test vector：GET /v1/order/orders?order-id=1234567890，簽名應該是 KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg=
//        ② GET /v1/order/matchresults?symbol=btcjpy&size=10
//        假伺服器用自己保存的 Secret Key，照規則重新算一次簽名，不一致就回 api-signature-not-valid。
//        （HMAC、Base64、URL 編碼都已經寫好在 Exchange09Support.kt，這題只看排序。）
// Bug 版：「依字母順序排序」，把名稱轉小寫再比較。看起來有排序，但 ASCII 裡所有大寫字母都排在小寫字母之前：
//        伺服器的順序是 AccessKeyId、SignatureMethod、SignatureVersion、Timestamp、order-id；
//        轉小寫比較之後，order-id 夾在 AccessKeyId 與 SignatureMethod 中間，簽名字串不同，簽名永遠不對。
// 修正版：依名稱直接比較（String 的預設順序就是逐字元比較，ASCII 裡大寫在小寫之前），不轉小寫、不用 Collator。
package tw.harry.labs.solution.exchange09signing

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：送出兩個簽名請求，假伺服器照規則重新算簽名 */
private fun scenario(log: LabLog, sorted: (List<Pair<String, String>>) -> List<Pair<String, String>>) {
    var rejected = 0
    val requests = listOf(
        "/v1/order/orders" to listOf("order-id" to "1234567890"),
        "/v1/order/matchresults" to listOf("symbol" to "btcjpy", "size" to "10"),
    )
    for ((index, request) in requests.withIndex()) {
        val (path, own) = request
        val ordered = sorted(own + E09.authParams())                  // 自己的參數在前、認證參數在後，由 sorted 排好
        log("$path 排序後的參數：${ordered.joinToString(", ") { it.first }}")
        val query = ordered.joinToString("&") { "${E09.percentEncode(it.first)}=${E09.percentEncode(it.second)}" }
        val signature = E09.hmacBase64("GET\n${E09.HOST}\n$path\n$query")
        if (index == 0) log("test vector 比對：${if (signature == E09.GET_VECTOR) "一致" else "不一致"}")
        val sent = query + "&Signature=" + E09.percentEncode(signature)
        val error = E09.verifyRest("GET", E09.HOST, path, sent)
        if (error != null) {
            rejected++
            log("$path：伺服器拒絕（$error）")
        } else {
            log("$path：伺服器受理")
        }
    }
    log("2 個請求，被伺服器拒絕 $rejected 個")
}

// MARK: - Bug 版
private object L01Bug {
    fun sorted(params: List<Pair<String, String>>): List<Pair<String, String>> {
        return params.sortedBy { it.first.lowercase() }            // 不分大小寫的「字母順序」：order-id 跑到 SignatureMethod 前面
    }

    suspend fun run(log: LabLog) = scenario(log) { sorted(it) }
}

// MARK: - 修正版
private object L01Fixed {
    fun sorted(params: List<Pair<String, String>>): List<Pair<String, String>> {
        return params.sortedBy { it.first }                        // 直接比較名稱：ASCII 裡大寫在小寫之前
    }

    suspend fun run(log: LabLog) = scenario(log) { sorted(it) }
}

// MARK: - 演練設定
private fun rejectedCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("2 個請求") }?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l01 = Lab(
    id = "L01",
    title = "明明有排序，簽名還是被拒絕",
    question = "第 1 題：為什麼 API 請求要簽名？這套交易所 API 的 Signature V2 怎麼做？",
    goal = "簽名字串裡的參數要依 ASCII 順序排序：大寫字母在小寫字母之前。轉小寫再比較的「字母順序」，算出來的簽名和伺服器不同。",
    steps = listOf(
        "選「Bug 版」執行，看兩個請求「排序後的參數」，order-id 排在哪裡？簽名和 test vector 一樣嗎？伺服器怎麼回？",
        "想一想：大寫的 S 和小寫的 o，在 ASCII 表裡誰先誰後？",
        "選「修正版」執行，兩個請求都要被受理，第一個請求的簽名要和 test vector 一致。",
        "進階：Java 的 Collator、Swift 的 localizedCompare 也會讓大小寫的順序和伺服器不同，簽名的排序一律用 byte 順序。",
    ),
    expectBug = "「/v1/order/orders 排序後的參數：AccessKeyId, order-id, SignatureMethod, SignatureVersion, Timestamp」（order-id 夾在中間），「test vector 比對：不一致」，「2 個請求，被伺服器拒絕 2 個」（api-signature-not-valid）。",
    expectFixed = "「排序後的參數：AccessKeyId, SignatureMethod, SignatureVersion, Timestamp, order-id」，「test vector 比對：一致」，「2 個請求，被伺服器拒絕 0 個」。",
    file = "exchange09-signing/L01_ParamOrder.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = rejectedCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { … } 執行"
            "/v1/order/orders 排序後的參數：AccessKeyId, order-id, SignatureMethod, SignatureVersion, Timestamp" !in lines ->
                "Bug 還沒重現：名稱轉小寫（lowercase()）之後再比較，order-id 會夾在 AccessKeyId 與 SignatureMethod 中間"
            n != 2 -> "兩個請求都應該被伺服器拒絕（目前拒絕 $n 個）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = rejectedCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { … } 執行"
            n != 0 -> "還有 $n 個請求被拒絕：依名稱直接排序（大寫在小寫之前），不要轉小寫"
            "test vector 比對：一致" !in lines -> "第一個請求的簽名要和 test vector 一致"
            else -> null
        }
    },
)
