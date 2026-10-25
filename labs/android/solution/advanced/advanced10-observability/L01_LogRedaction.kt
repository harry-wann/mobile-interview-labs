// L01：送到遠端 log 服務的訊息裡，有登入 token、完整的錢包地址和餘額（log 要遮罩個資）
// 對應複習站：Advanced10Observability 第 1 題「可觀測性是什麼？log、指標、追蹤各回答什麼問題？」（隱私優先）
//
// 情境：下單時 App 會把一行 log 送到遠端 log 服務，方便追問題。log 裡有 requestId（要留著，才能串起 App 與伺服器的紀錄），
//       也被不小心寫進了 token、地址、餘額；另外還有兩種常見的洩漏：token 出現在網址的查詢參數裡、錯誤訊息裡印出整個 Authorization 標頭。
//       演練的 log 服務是假的（只是一個清單），token 與地址都是編出來的。
// Bug 版：log 原封不動送出去。遠端 log 服務、任何有權限看 log 的人、log 被外洩時，都拿得到完整的 token（有效期內能直接登入）、地址與餘額。
// 修正版：送出前先遮罩：Bearer token、token／password 查詢參數、balance 開頭的欄位換成 ***，錢包地址只留頭尾幾碼；requestId 不是敏感資料，保留。
package tw.harry.labs.solution.advanced10observability

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJ1aWQiOjQyfQ.c2lnbmF0dXJl"
private const val ADDRESS = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

/** 演練流程（兩個版本共用）：redact 是送出前的處理，傳進去的是一行 log，回傳真正送出的內容 */
private fun scenario(log: LabLog, redact: (String) -> String) {
    val sent = mutableListOf<String>()                          // 假的遠端 log 服務收到的內容
    fun emit(line: String) { sent += redact(line) }

    emit("order submit requestId=req-1a2b token=$TOKEN address=$ADDRESS balanceJPY=1234567")
    emit("GET /api/assets?token=$TOKEN&limit=20 requestId=req-1a2c")
    emit("decode failed requestId=req-1a2d header Authorization: Bearer $TOKEN")

    sent.forEach { log("遠端 log：$it") }
    log("含完整 token 的行：${sent.count { TOKEN in it }}")
    log("含完整錢包地址的行：${sent.count { ADDRESS in it }}")
    log("含 requestId 的行：${sent.count { "requestId=req-1a2" in it }}")
}

// MARK: - Bug 版
private object L01Bug {
    fun redact(line: String): String =
        line                                                    // 原封不動送出

    suspend fun run(log: LabLog) = scenario(log, ::redact)
}

// MARK: - 修正版
private object L01Fixed {
    /** (規則, 取代成什麼)：依序套用 */
    private val rules = listOf(
        Regex("""Bearer\s+[A-Za-z0-9._-]+""") to "Bearer ***",                        // Authorization 標頭
        Regex("""(token|password|secret)=[^&\s]+""") to "$1=***",                     // 網址或欄位裡的 token、密碼
        Regex("""(balance\w*)=\S+""") to "$1=***",                                    // 餘額
        Regex("""\b(bc1[a-z0-9]{3})[a-z0-9]+([a-z0-9]{4})\b""") to "$1…$2",           // 錢包地址只留頭尾
    )

    fun redact(line: String): String = rules.fold(line) { text, (regex, template) -> regex.replace(text, template) }

    suspend fun run(log: LabLog) = scenario(log, ::redact)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "遠端 log 裡有 token、地址和餘額",
    question = "第 1 題：可觀測性是什麼？log、指標、追蹤各回答什麼問題？（隱私優先）",
    goal = "送到遠端的 log 不能有 token、地址、餘額、個資；但 requestId 要留著，才能串起一次操作。",
    steps = listOf(
        "選「Bug 版」執行，看送到遠端的每一行，以及最後三行的計數。",
        "想一想：token 在三行 log 裡用三種不同的樣子出現（欄位、網址查詢參數、Authorization 標頭），只遮第一種夠嗎？",
        "選「修正版」執行：token、地址、餘額都遮掉，requestId 還在。",
        "進階：更好的做法是一開始就不要把敏感值寫進 log（Android 的 Timber 在 release 不種輸出的 Tree，iOS 的 os.Logger 用 privacy: .private），遮罩是最後一道防線。",
    ),
    expectBug = "三行 log 都含完整 token（含完整 token 的行：3），地址也在（含完整錢包地址的行：1）。",
    expectFixed = "含完整 token 的行：0，含完整錢包地址的行：0，含 requestId 的行：3。地址變成 bc1qxy2…0wlh。",
    file = "advanced10-observability/L01_LogRedaction.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("含完整 token 的行") } -> "演練沒有跑完：要用 scenario 執行"
            "含完整 token 的行：3" !in lines -> "Bug 還沒重現：redact 要原封不動回傳，三行都應該含完整 token"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("含完整 token 的行") } -> "演練沒有跑完：要用 scenario 執行"
            "含完整 token 的行：0" !in lines -> "還有 token 沒遮掉：欄位、網址查詢參數、Authorization 標頭三種樣子都要處理"
            "含完整錢包地址的行：0" !in lines -> "錢包地址沒遮掉：只留頭尾幾碼"
            "含 requestId 的行：3" !in lines -> "requestId 不是敏感資料，要保留，不要全部遮掉"
            lines.none { "balanceJPY=***" in it } -> "餘額（balanceJPY=…）沒遮掉"
            else -> null
        }
    },
)
