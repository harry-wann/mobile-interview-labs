// L02：行情 API 維護中少了一個欄位，App 一打開就當掉（!! 強制解包）
// 對應複習站：Advanced06Crash 第 2 題「iOS 和 Android 常見的 crash 有哪幾種？」（!! 與 NullPointerException）
//
// 情境：行情 API 回傳 JSON，App 取出 "last"（最新成交價）顯示。API 維護期間會回傳不完整的資料：
//       少了 last，或 last 是 "--"。演練依序處理三筆回應：正常、少欄位、"--"
//       （JSON 解析完的結果用 Map 表示，和 Gson、Moshi 解析成 Map 的結果一樣）。
//
// 你要寫：
//   ① Bug 版：用 `!!` 假設 last 一定存在。第二筆回應沒有 last，會丟出 NullPointerException，整個 App 當掉。
//   ② 修正版：在解碼的那一層處理「欄位可能不存在」：last 是 BigDecimal?，缺資料或格式不對就是 null。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced06-crash/L02_ForceUnwrap.kt
package tw.harry.labs.practice.advanced06crash

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 三筆回應（兩個版本共用） */
private val responses: List<Map<String, Any?>> = listOf(
    mapOf("symbol" to "BTC_JPY", "last" to "15000000"),
    mapOf("symbol" to "BTC_JPY"),                          // 維護中：少了 last
    mapOf("symbol" to "BTC_JPY", "last" to "--"),          // 維護中：格式不對
)

// MARK: - Bug 版
private object L02Bug {
    fun parseLast(raw: Map<String, Any?>): BigDecimal {
        // TODO ①：用 raw["last"]!!.toString() 取出價格字串（以為欄位一定存在），再轉成 BigDecimal 回傳
        //         （骨架先回傳 0，不會當掉；寫了之後從 App 執行才看得到當掉）
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) {
        for (raw in responses) {
            log("BTC_JPY 最新價 ${parseLast(raw).toPlainString()}")
        }
        log("三筆回應都處理完，App 沒有當掉")
    }
}

// MARK: - 修正版
private object L02Fixed {
    class Ticker(val symbol: String, val last: BigDecimal?) {
        companion object {
            /** 解碼時就處理好：缺欄位、型別不對、"--" 都變成 null */
            fun from(raw: Map<String, Any?>): Ticker? {
                val symbol = raw["symbol"] as? String ?: return null
                // TODO ②：last 缺欄位、型別不對、"--" 都要變成 null（不丟例外）。
                //         提示：raw["last"] as? String，再用 toBigDecimalOrNull()
                return Ticker(symbol, null)
            }
        }
    }

    suspend fun run(log: LabLog) {
        var previous: BigDecimal? = null
        for (raw in responses) {
            val ticker = Ticker.from(raw)
            if (ticker == null) {
                log("回應缺少 symbol，略過這一筆")
                continue
            }
            val last = ticker.last
            if (last != null) {
                previous = last
                log("${ticker.symbol} 最新價 ${last.toPlainString()}")
            } else {
                log("${ticker.symbol} 最新價 ${previous?.toPlainString() ?: "—"}（這筆資料缺少 last，沿用上一筆）")
            }
        }
        log("三筆回應都處理完，App 沒有當掉")
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "API 少一個欄位，App 一打開就當掉",
    question = "第 2 題：iOS 和 Android 常見的 crash 有哪幾種？（!! 與 NullPointerException）",
    goal = "API 回傳的資料不可靠。缺欄位、格式不對時，App 要沿用上一筆價格，而不是當掉。 先寫出會當掉的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced06-crash/L02_ForceUnwrap.kt，寫 TODO ①。",
        "打開 Android Studio 下方的 Logcat。",
        "選「練習」「Bug 版」執行：第二筆回應時 App 當掉，Logcat 出現 FATAL EXCEPTION 與 NullPointerException，stack 指到 `raw[\"last\"]!!` 那一行。",
        "想一想：與其一行一行補 `?.`，能不能在解碼的那一層就把「欄位可能不存在」處理掉？",
        "寫 TODO ②，重新打開 App，選「修正版」執行，確認三筆都處理完。",
    ),
    expectBug = "App 當掉。Logcat：java.lang.NullPointerException（來自 `!!`）。",
    expectFixed = "第一筆印出價格 15000000，第二、第三筆印出「沿用上一筆」，最後印出「三筆回應都處理完，App 沒有當掉」。",
    file = "advanced06-crash/L02_ForceUnwrap.kt",
    bugCrashes = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkFixed = { lines ->
        val fallbacks = lines.count { "沿用上一筆" in it }
        when {
            "BTC_JPY 最新價 15000000" !in lines -> "第一筆正常的回應應該印出「BTC_JPY 最新價 15000000」"
            fallbacks != 2 -> "第二、第三筆（缺 last、\"--\"）都應該印出「沿用上一筆」，目前有 $fallbacks 筆"
            lines.lastOrNull() != "三筆回應都處理完，App 沒有當掉" -> "三筆回應要全部處理完，最後印出「三筆回應都處理完，App 沒有當掉」"
            else -> null
        }
    },
)
