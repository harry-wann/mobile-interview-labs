// L08：伺服器新增了一種出金狀態，整份出金記錄就載入失敗（出金狀態用 enum 解碼，
// 遇到不認識的值）
// 對應複習站：Exchange12Wallet 第 5 題「出金申請的 API
// 長什麼樣子？送出逾時時為什麼不能直接重送？」
//
// 情境：出入金記錄的 state 是字串：submitted、reexamine、pass、pre-transfer、
// wallet-transfer、confirmed、reject、wallet-reject、confirm-error、canceled、
// repealed…
//        App 要把它們對應成使用者看得懂的階段（申請中、審查中、處理中、已送出、
// 完成、失敗、已取消）。複習站的建議：不認識的狀態，
//        顯示原始狀態碼，不要當成失敗，也不能讓整份記錄載入失敗。
// 伺服器日後會新增狀態（演練用一個假的 "compliance-hold"），舊版 App
// 一定會遇到。
//        演練的 JSON 有 3 筆記錄：confirmed、compliance-hold（不認識）、
// wallet-transfer。parse 要把它解碼成「畫面上的每一列」。
//        JSON 用 Gson 解析（不用 org.json：JVM 單元測試裡它是空殼）。
//
// 你要寫：
//   ① Bug 版（L08Bug.parse）：宣告 enum class State（只列 7 個已知狀態，用
// @SerializedName 對應 "pre-transfer"、"wallet-transfer"…）
//      與 class Record(val id: Int, val state: State)（放在 L08Bug 裡面）；用
// Gson 解析 Response，取用每筆的 state 轉成畫面的一列（rows），
//      發生例外（runCatching 得到 null）就回傳 null。
//   ② 修正版（L08Fixed.parse）：Record 的 state 用 String；用 stage(state)
// 對應成階段文字，對應不到就顯示「未知狀態（原始狀態碼）」。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange12-wallet/L08_UnknownState.kt
package tw.harry.labs.practice.exchange12wallet

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val JSON = """
{"status":"ok","data":[
  {"id":101,"state":"confirmed"},
  {"id":102,"state":"compliance-hold"},
  {"id":103,"state":"wallet-transfer"}
]}
"""

/** 畫面上的一列：出金 id 與顯示的階段文字 */
private data class L08Row(val id: Int, val text: String)

private class L08Response<R>(val data: List<R>)

/** 已知狀態對應的階段文字；不認識回傳 null */
private fun stage(state: String): String? = when (state) {
    "submitted" -> "申請中"
    "reexamine" -> "審查中"
    "pass", "pre-transfer" -> "處理中"
    "wallet-transfer" -> "已送出"
    "confirmed" -> "完成"
    "reject", "wallet-reject", "confirm-error" -> "失敗"
    "canceled", "repealed" -> "已取消"
    else -> null
}

/** 演練流程（兩個版本共用）：把 JSON 解碼成畫面上的列 */
private fun scenario(log: LabLog, parse: (String) -> List<L08Row>?) {
    val rows = parse(JSON)
    if (rows == null) {
        log("解析失敗：整份出金記錄載入失敗")
        log("畫面上顯示 0 筆出金記錄")
        return
    }
    for (row in rows) log("#${row.id} → ${row.text}")
    log("畫面上顯示 ${rows.size} 筆出金記錄")
}

// MARK: - Bug 版
private object L08Bug {
    // TODO ①：在這裡宣告 enum class State（只列 7 個已知狀態：submitted、
    // reexamine、pass、pre-transfer、wallet-transfer、confirmed、reject，
    //        每個值用 @SerializedName("…") 對應 JSON 的字串，並有一個 val raw:
    // String 存原始字串）與 class Record(val id: Int, val state: State)。
    //        parse 用 runCatching { … }.getOrNull() 包起來：用 Gson().
    // fromJson<L08Response<Record>>(json, TypeToken.
    // getParameterized(L08Response::class.java, Record::class.java).type)
    // 解析，
    //        再把每筆轉成 L08Row(it.id, stage(it.state.raw) ?: it.state.raw)；
    // 例外（得到 null）就回傳 null
    fun parse(json: String): List<L08Row>? = emptyList()

    suspend fun run(log: LabLog) = scenario(log) { parse(it) }
}

// MARK: - 修正版
private object L08Fixed {
    // TODO ②：在這裡宣告 class Record(val id: Int, val state: String)（state
    // 先當字串讀進來）。
    //        parse 用 runCatching { … }.getOrNull() 包起來：用 Gson().
    // fromJson<L08Response<Record>>(json, TypeToken.
    // getParameterized(L08Response::class.java, Record::class.java).type)
    // 解析，
    //        再把每筆轉成 L08Row(it.id, stage(it.state) ?: "未知狀態（${it.
    // state}）")；例外就回傳 null
    fun parse(json: String): List<L08Row>? = emptyList()

    suspend fun run(log: LabLog) = scenario(log) { parse(it) }
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "伺服器新增一種狀態，整份出金記錄載入失敗",
    question = "第 5 題：出金申請的 API 長什麼樣子？送出逾時時為什麼不能直接重送？",
    goal = "API 回傳的狀態字串日後一定會新增。解碼時不要把它限制成 enum；先讀成字串，再對應成階段，對應不到就顯示原始狀態碼（不要當成失敗，也不能讓整份記錄失敗）。",
    steps = listOf(
        "打開 practice/exchange/exchange12-wallet/L08_UnknownState.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看畫面上顯示幾筆記錄：只有 compliance-hold 一筆不認識，為什麼另外兩筆也不見了？",
        "想一想：Gson 遇到 enum 沒有的字串，為什麼不丟例外？null 是在哪一行才爆出來的？舊版 App 在使用者手機上，沒辦法立刻更新。",
        "寫 TODO ②（修正版），選「修正版」執行，3 筆都顯示，不認識的那一筆顯示「未知狀態（compliance-hold）」。",
        "進階：iOS 的 JSONDecoder 遇到不認識的 enum 值會直接丟 DecodingError（見 iOS 版）；兩個平台的行為不同，但結果同樣不能接受。",
    ),
    expectBug = "「解析失敗：整份出金記錄載入失敗」，「畫面上顯示 0 筆出金記錄」（只有一筆不認識，三筆都不見了；Gson 把不認識的 state 悄悄設成 null，取用時 NullPointerException）。",
    expectFixed = "「#101 → 完成」「#102 → 未知狀態（compliance-hold）」「#103 → 已送出」，「畫面上顯示 3 筆出金記錄」。",
    file = "exchange12-wallet/L08_UnknownState.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("畫面上顯示")
            } -> "演練沒有跑完：要用 scenario(log) { parse(it) } 執行"

            "解析失敗：整份出金記錄載入失敗" !in lines -> "Bug 還沒重現：state 用 enum，Gson 遇到 compliance-hold 會把它設成 null，取用 state 時 NullPointerException（parse 要回傳 null）"

            "畫面上顯示 0 筆出金記錄" !in lines -> "整份失敗時，畫面上應該是 0 筆"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("畫面上顯示")
            } -> "演練沒有跑完：要用 scenario(log) { parse(it) } 執行"

            "畫面上顯示 3 筆出金記錄" !in lines -> "三筆記錄都要顯示（不認識的狀態不能讓整份失敗）"

            "#101 → 完成" !in lines || "#103 → 已送出" !in lines -> "已知的狀態要對應成階段：confirmed → 完成、wallet-transfer → 已送出"

            "#102 → 未知狀態（compliance-hold）" !in lines -> "不認識的狀態要顯示「未知狀態（原始狀態碼）」"

            else -> null
        }
    },
)
