// L07：同一個 API 錯誤，三個畫面三種文案，
// 還有畫面在不該重試的錯誤上重試（錯誤處理沒有分層）
// 對應複習站：Advanced09Architecture 第 4 題「模組化怎麼切才不會切過頭？」
// （錯誤型別放在哪一層、API 的錯誤碼不能越過 Data 層）、第 5 題「依賴注入（DI）
// 怎麼做？」（Domain 宣告錯誤型別，Data 層實作並翻譯）
//
// 情境：下單 API 會失敗的原因有五種（下單帶有 clientOrderId，
// 所以重送不會重複下單）：
//       {"code": 1043} 餘額不足、HTTP 429 太頻繁（Retry-After 標頭 3 秒）、
// 連線逾時、{"code": 1102} 價格不符合最小跳動單位、{"code": 9999}
// 沒看過的錯誤碼。
//       「一般下單」「快速下單」「撤單」三個畫面都會遇到這五種錯誤。
// 演練對同一組五個錯誤，列出三個畫面各自顯示的文案與重試決定。
//
// 你要寫：
//   ① Bug 版：三個畫面各自翻譯原始錯誤。
//      一般下單：只認得 Api 1043，文案「餘額不足，請先入金或減少數量」、
// 不重試（L07Retry.Never）；其他都是「未知錯誤」、不重試。
//      快速下單：直接顯示原始碼。Api 顯示「錯誤碼 N」、不重試；Http 顯示「HTTP
// N」、立刻重試（L07Retry.Now）；Timeout 顯示「timeout」、立刻重試。
//      撤單：每一種錯誤都立刻重試（Now），文案是自己縮寫的：1043「餘額不足」、
// 1102「價格不符」、其他 Api 錯誤「系統錯誤」、Http「操作太頻繁」、
// Timeout「網路不穩」。
//   ② 修正版：分三層。Data 層 OrderErrorMapper 把原始錯誤翻成 Domain 的
// OrderError；
//      Domain 的 OrderError.retry 決定可不可以重試（限流等 Retry-After 的秒數、
// 逾時立刻重試、其他不重試）；
//      Presentation 層 OrderErrorMessages 是唯一的文案對照表：
//        InsufficientBalance「餘額不足，請先入金或減少數量」、
// RateLimited「操作太頻繁，請 N 秒後再試」、Network「網路不穩，
// 請確認連線後再試」、
//        InvalidPrice「價格不符合最小跳動單位，請修改價格」、
// Unknown「發生未預期的錯誤（N），請聯絡客服」。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced09-architecture/L07_ErrorLayers.kt
package tw.harry.labs.practice.advanced09architecture

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** API 回來的原始錯誤（Data 層才看得懂） */
private sealed interface L07RawError {
    // 回應內容 {"code": 1043}
    data class Api(val code: Int) : L07RawError

    // HTTP 狀態碼；429 會帶 Retry-After 標頭（秒）
    data class Http(val status: Int, val retryAfter: Int?) : L07RawError

    // 連線逾時
    object Timeout : L07RawError

    val label: String
        get() = when (this) {
            is Api -> "code $code"
            is Http -> "HTTP $status"
            Timeout -> "逾時"
        }
}

/** 同一組五個錯誤，三個畫面都會遇到 */
private val L07_RAW_ERRORS: List<L07RawError> = listOf(
    L07RawError.Api(1043),
    L07RawError.Http(429, retryAfter = 3),
    L07RawError.Timeout,
    L07RawError.Api(1102),
    L07RawError.Api(9999),
)

/** 能不能重試（型別兩個版本共用，差別是「誰決定」） */
private sealed interface L07Retry {
    object Never : L07Retry
    object Now : L07Retry
    data class After(val seconds: Int) : L07Retry

    val text: String
        get() = when (this) {
            Never -> "不重試"
            Now -> "可以立刻重試"
            is After -> "$seconds 秒後可重試"
        }
}

/** 畫面上最後看到的：一段文案，加上要不要讓使用者（或自動）重試 */
private data class L07Shown(val message: String, val retry: L07Retry)

/** 演練流程（兩個版本共用）：同一組五個錯誤，列出三個畫面各自的結果 */
private fun scenario(
    log: LabLog,
    screens: List<Pair<String, (L07RawError) -> L07Shown>>,
) {
    var consistent = 0
    for (raw in L07_RAW_ERRORS) {
        val results = screens.map { (name, show) ->
            show(raw).also {
                log("${raw.label}｜$name｜${it.message}｜${it.retry.text}")
            }
        }
        if (results.all { it == results[0] }) consistent++
    }
    log("${L07_RAW_ERRORS.size} 個錯誤中，三個畫面的文案與重試決定都一致的有 $consistent 個")
}

// MARK: - Bug 版
private object L07Bug {
    /** 一般下單：只認得餘額不足，其他都是「未知錯誤」，從不重試 */
    fun orderScreen(raw: L07RawError): L07Shown {
        // TODO ①-1：Api 且 code 是 1043 時回傳 L07Shown("餘額不足，
        // 請先入金或減少數量", L07Retry.Never)；其他錯誤一律
        // L07Shown("未知錯誤", L07Retry.Never)
        return L07Shown("", L07Retry.Never)
    }

    /**
     * 快速下單：直接顯示原始碼；HTTP 錯誤與逾時都立刻重試（429 沒看
     * Retry-After）
     */
    fun quickScreen(raw: L07RawError): L07Shown {
        // TODO ①-2：when (raw)：Api 顯示 "錯誤碼 ${raw.code}"（Never）；Http
        // 顯示 "HTTP ${raw.status}"（Now）；Timeout 顯示 "timeout"（Now）
        return L07Shown("", L07Retry.Never)
    }

    /** 撤單：文案是自己縮寫的，而且每一種錯誤都重試，包含餘額不足與價格錯誤 */
    fun cancelScreen(raw: L07RawError): L07Shown {
        // TODO ①-3：每一種錯誤的 retry 都是 L07Retry.Now；文案：
        // 1043「餘額不足」、1102「價格不符」、其他 Api 錯誤「系統錯誤」、
        // Http「操作太頻繁」、Timeout「網路不穩」
        return L07Shown("", L07Retry.Never)
    }

    suspend fun run(log: LabLog) = scenario(
        log,
        listOf(
            "一般下單" to L07Bug::orderScreen,
            "快速下單" to L07Bug::quickScreen,
            "撤單" to L07Bug::cancelScreen,
        ),
    )
}

// MARK: - 修正版
private object L07Fixed {
    /** Domain 層：錯誤型別在這裡定義，不認識錯誤碼，也不認識 HTTP */
    sealed interface OrderError {
        object InsufficientBalance : OrderError
        data class RateLimited(val retryAfter: Int) : OrderError
        object Network : OrderError
        object InvalidPrice : OrderError
        data class Unknown(val code: Int) : OrderError

        /**
         * 可不可以重試，由 Domain 決定（業務判斷）：
         * 餘額不足重試一百次也不會成功；逾時可以重送，因為下單帶有
         * clientOrderId
         */
        val retry: L07Retry
            get() {
                // TODO ②-2：when (this)：RateLimited 回傳 L07Retry.
                // After(retryAfter)；Network 回傳 L07Retry.Now；
                // 其他（InsufficientBalance、InvalidPrice、Unknown）回傳
                // L07Retry.Never
                return L07Retry.Never
            }
    }

    /**
     * Data 層：只有這裡看得到錯誤碼與 HTTP 狀態碼，翻成 Domain 的錯誤再往上傳
     */
    object OrderErrorMapper {
        fun map(raw: L07RawError): OrderError {
            // TODO ②-1：Api 1043 → InsufficientBalance、Api 1102 →
            // InvalidPrice、其他 Api 錯誤碼 → Unknown(code)；
            //          Http 429 → RateLimited(Retry-After 的秒數，沒有就用 1)、
            // 其他 Http → Unknown(狀態碼)；Timeout → Network
            return OrderError.Unknown(0)
        }
    }

    /** Presentation 層：只有這一張文案對照表，三個畫面共用 */
    object OrderErrorMessages {
        fun text(error: OrderError): String {
            // TODO ②-3：when (error)，每個 case
            // 回傳檔案開頭列的文案（RateLimited 與 Unknown 要把秒數、
            // 錯誤碼放進文案）
            return ""
        }
    }

    /** 三個畫面共用的錯誤顯示：拿到的是 Domain 的 OrderError，不再有原始碼 */
    fun show(raw: L07RawError): L07Shown {
        // Repository 在 Data 層翻譯
        val error = OrderErrorMapper.map(raw)
        return L07Shown(OrderErrorMessages.text(error), error.retry)
    }

    suspend fun run(log: LabLog) = scenario(
        log,
        listOf(
            "一般下單" to L07Fixed::show,
            "快速下單" to L07Fixed::show,
            "撤單" to L07Fixed::show,
        ),
    )
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "同一個錯誤三種文案，還重試了不該重試的",
    question = "第 4 題：模組化怎麼切才不會切過頭？（錯誤型別放在哪一層）、第 5 題：依賴注入（DI）怎麼做？（Domain 宣告、Data 層實作）",
    goal = "API 的錯誤碼不能越過 Data 層：Data 層把它翻成 Domain 的 error 型別；可不可以重試由 Domain 決定；Presentation 層只做一張文案對照表。 先寫出三個畫面各自翻譯的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced09-architecture/L07_ErrorLayers.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看同一個錯誤在三個畫面各顯示什麼：哪個畫面顯示「未知錯誤」？哪個顯示原始碼？哪個在餘額不足時還說可以重試？",
        "想一想：餘額不足重試一百次會成功嗎？HTTP 429 立刻重試，會發生什麼？這些判斷應該寫在哪一層？",
        "寫 TODO ②，選「修正版」執行：五個錯誤在三個畫面的文案與重試決定都一樣；429 的文案帶著 3 秒，餘額不足不重試，逾時可以立刻重試。",
        "對照三層：Data 層的 OrderErrorMapper（只有它看得懂錯誤碼）、Domain 的 OrderError.retry（可不可以重試）、Presentation 的 OrderErrorMessages（文案）。",
        "進階：Retrofit／OkHttp 的 HttpException 與 IOException 在 repository 翻成 sealed interface；非同步的 Result 也不要把 Throwable 原封不動丟給 ViewModel。iOS 一樣，用 Domain 的 enum 取代 URLError 與 API 錯誤碼。",
    ),
    expectBug = "三個畫面不一致：「HTTP 429｜一般下單｜未知錯誤｜不重試」、「code 1043｜快速下單｜錯誤碼 1043｜不重試」、「code 1043｜撤單｜餘額不足｜可以立刻重試」；「5 個錯誤中，三個畫面的文案與重試決定都一致的有 0 個」。",
    expectFixed = "三個畫面一致：「HTTP 429｜…｜操作太頻繁，請 3 秒後再試｜3 秒後可重試」、「code 1043｜…｜餘額不足，請先入金或減少數量｜不重試」；「5 個錯誤中，三個畫面的文案與重試決定都一致的有 5 個」。",
    file = "advanced09-architecture/L07_ErrorLayers.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.contains("個錯誤中，三個畫面的文案與重試決定都一致的有")
            } -> "演練沒有跑完：要用 scenario 執行"

            "HTTP 429｜一般下單｜未知錯誤｜不重試" !in lines -> "Bug 還沒重現：一般下單只認得 1043，其他錯誤（例如 HTTP 429）都要顯示「未知錯誤」、不重試"

            "code 1043｜快速下單｜錯誤碼 1043｜不重試" !in lines -> "Bug 還沒重現：快速下單把原始碼直接顯示出來，Api 要顯示「錯誤碼 1043」"

            "code 1043｜撤單｜餘額不足｜可以立刻重試" !in lines -> "Bug 還沒重現：撤單每一種錯誤都重試，包含餘額不足（L07Retry.Now）"

            "5 個錯誤中，三個畫面的文案與重試決定都一致的有 0 個" !in lines -> "Bug 還沒重現：三個畫面對同一個錯誤應該都不一樣"

            else -> null
        }
    },
    checkFixed = { lines ->
        val expected = listOf(
            Triple("code 1043", "餘額不足，請先入金或減少數量", "不重試"),
            Triple("HTTP 429", "操作太頻繁，請 3 秒後再試", "3 秒後可重試"),
            Triple("逾時", "網路不穩，請確認連線後再試", "可以立刻重試"),
            Triple("code 1102", "價格不符合最小跳動單位，請修改價格", "不重試"),
            Triple("code 9999", "發生未預期的錯誤（9999），請聯絡客服", "不重試"),
        )
        val wrong = expected.firstNotNullOfOrNull { (label, message, retry) ->
            val matched = lines.count {
                it.startsWith("$label｜") &&
                    it.endsWith("｜$message｜$retry")
            }
            if (matched == 3) {
                null
            } else {
                "$label 三個畫面都要顯示「$message」、$retry，現在只有 $matched 個畫面對：檢查 Data 層的對照（OrderErrorMapper）、Domain 的 OrderError.retry、Presentation 的 OrderErrorMessages"
            }
        }
        when {
            lines.none {
                it.contains("個錯誤中，三個畫面的文案與重試決定都一致的有")
            } -> "演練沒有跑完：要用 scenario 執行"

            wrong != null -> wrong

            "5 個錯誤中，三個畫面的文案與重試決定都一致的有 5 個" !in lines -> "三個畫面都要用同一組對照：Data 層翻譯、Domain 決定重試、Presentation 一張文案表"

            else -> null
        }
    },
)
