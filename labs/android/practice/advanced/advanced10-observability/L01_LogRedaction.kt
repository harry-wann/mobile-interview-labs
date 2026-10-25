// L01：送到遠端 log 服務的訊息裡，有登入 token、完整的錢包地址和餘額（log
// 要遮罩個資）
// 對應複習站：Advanced10Observability 第 1 題「可觀測性是什麼？log、指標、
// 追蹤各回答什麼問題？」（隱私優先）
//
// 情境：下單時 App 會把一行 log 送到遠端 log 服務，方便追問題。log 裡有
// requestId（要留著，才能串起 App 與伺服器的紀錄），
//       也被不小心寫進了 token、地址、餘額；另外還有兩種常見的洩漏：token
// 出現在網址的查詢參數裡、錯誤訊息裡印出整個 Authorization 標頭。
//       演練的 log 服務是假的（只是一個清單），token 與地址都是編出來的。
//
// 你要寫：
//   ① Bug 版：log 原封不動送出去，遠端服務拿得到完整的 token、地址與餘額。
//   ② 修正版：送出前遮罩：Bearer token、token／password 參數、balance 欄位換成
// ***，錢包地址只留頭尾；requestId 保留。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced10-observability/L01_LogRedaction.kt
package tw.harry.labs.practice.advanced10observability

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJ1aWQiOjQyfQ.c2lnbmF0dXJl"
private const val ADDRESS = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

/**
 * 演練流程（兩個版本共用）：redact 是送出前的處理，傳進去的是一行 log，
 * 回傳真正送出的內容
 */
private fun scenario(log: LabLog, redact: (String) -> String) {
    // 假的遠端 log 服務收到的內容
    val sent = mutableListOf<String>()
    fun emit(line: String) { sent += redact(line) }

    emit(
        "order submit requestId=req-1a2b token=$TOKEN address=$ADDRESS balanceJPY=1234567",
    )
    emit("GET /api/assets?token=$TOKEN&limit=20 requestId=req-1a2c")
    emit("decode failed requestId=req-1a2d header Authorization: Bearer $TOKEN")

    sent.forEach { log("遠端 log：$it") }
    log("含完整 token 的行：${sent.count { TOKEN in it }}")
    log("含完整錢包地址的行：${sent.count { ADDRESS in it }}")
    log("含 requestId 的行：${sent.count { "requestId=req-1a2" in it }}")
}

// MARK: - Bug 版
private object L01Bug {
    fun redact(line: String): String {
        // TODO ①：原封不動送出（回傳 line，沒有任何遮罩）
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log, ::redact)
}

// MARK: - 修正版
private object L01Fixed {
    fun redact(line: String): String {
        // TODO ②：用正規表示式遮罩（Regex(…).replace(line, 取代成什麼)，
        // 可以套好幾條規則）：
        //   - Authorization 標頭：Bearer 後面的 token 換成 ***
        //   - 網址或欄位裡的 token、password、secret：等號後面的值換成 ***（用
        // $1 保留欄位名稱）
        //   - 開頭是 balance 的欄位（例如 balanceJPY）：等號後面的值換成 ***
        //   - 錢包地址（bc1 開頭）：只留開頭 6 個字元和結尾 4 個字元，
        // 中間用「…」代替
        //   requestId 不是敏感資料，不要遮掉
        return line
    }

    suspend fun run(log: LabLog) = scenario(log, ::redact)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "遠端 log 裡有 token、地址和餘額",
    question = "第 1 題：可觀測性是什麼？log、指標、追蹤各回答什麼問題？（隱私優先）",
    goal = "送到遠端的 log 不能有 token、地址、餘額、個資；但 requestId 要留著，才能串起一次操作。 先寫出什麼都不遮的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced10-observability/L01_LogRedaction.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看送到遠端的每一行，以及最後三行的計數。",
        "想一想：token 在三行 log 裡用三種不同的樣子出現（欄位、網址查詢參數、Authorization 標頭），只遮第一種夠嗎？",
        "寫 TODO ②，選「修正版」執行：token、地址、餘額都遮掉，requestId 還在。",
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
            lines.none {
                it.startsWith("含完整 token 的行")
            } -> "演練沒有跑完：要用 scenario 執行"

            "含完整 token 的行：3" !in lines -> "Bug 還沒重現：redact 要原封不動回傳，三行都應該含完整 token"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("含完整 token 的行")
            } -> "演練沒有跑完：要用 scenario 執行"

            "含完整 token 的行：0" !in lines -> "還有 token 沒遮掉：欄位、網址查詢參數、Authorization 標頭三種樣子都要處理"

            "含完整錢包地址的行：0" !in lines -> "錢包地址沒遮掉：只留頭尾幾碼"

            "含 requestId 的行：3" !in lines -> "requestId 不是敏感資料，要保留，不要全部遮掉"

            lines.none { "balanceJPY=***" in it } -> "餘額（balanceJPY=…）沒遮掉"

            else -> null
        }
    },
)
