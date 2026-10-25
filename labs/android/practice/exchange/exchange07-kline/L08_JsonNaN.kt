// L08：圖表少了一筆資料，還回報錯誤（用字串插值組要傳給網頁的 JSON，遇到 NaN）
// 對應複習站：Exchange07Kline 第 7 題「用 WebView 放 TradingView Lightweight
// Charts，資料怎麼傳過去？要注意什麼？」
//
// 情境：圖表放在 WebView 裡，App 把每根 K 線的「平均成交價」（vol ÷ amount）用
// JSON 傳給網頁，網頁用 `JSON.parse` 解析後交給圖表。
//        五根 K 線裡，第 3 根這段時間完全沒有成交（vol 0、amount 0），0.0 ÷ 0.
// 0 得到 NaN（不是數字）。
//        （假的圖表用 Gson 的嚴格模式解析傳進來的文字，和網頁的 `JSON.parse`
// 一樣：不合法的 JSON 就丟掉，記一筆錯誤。）
//
// 你要寫：
//   ① Bug 版（L08Bug.run 的 payload 閉包）：用字串插值組 JSON：{"time":時間,
// "value":價格}。
//   ② 修正版（L08Fixed.run 的 payload 閉包）：value 不是有限的數字（isFinite()
// 為 false）就設成 null；用 Gson().toJson 把 L08Point 編碼成字串。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange07-kline/L08_JsonNaN.kt
package tw.harry.labs.practice.exchange07kline

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L08Bar(val time: Int, val value: Double)

/** 每根 K 線的平均成交價 = 成交金額 ÷ 成交數量；沒有成交的區間是 0 ÷ 0 = NaN */
private val bars = listOf(
    Triple(60, 4_800_000.0, 1.0),
    Triple(120, 9_700_000.0, 2.0),
    Triple(180, 0.0, 0.0),
    Triple(240, 14_500_000.0, 3.0),
    Triple(300, 4_900_000.0, 1.0),
).map { (time, vol, amount) -> L08Bar(time, vol / amount) }

/** 假的圖表：收到網頁的 JSON 文字，嚴格解析 */
private class L08FakeChart {
    var accepted = 0
        private set
    var whitespace = 0
        private set
    var rejected = 0
        private set

    fun update(json: String, time: Int, log: LabLog) {
        val reader = JsonReader(StringReader(json))
        reader.strictness = Strictness.STRICT
        val parsed = try {
            JsonParser.parseReader(reader) as? JsonObject
        } catch (e: Exception) {
            null
        }
        if (parsed == null) {
            rejected++
            log("時間 $time：圖表收到的不是合法的 JSON，丟掉：$json")
            return
        }
        accepted++
        if (!parsed.has("value")) whitespace++
        log("時間 $time：圖表收到 $json")
    }
}

/** 演練流程（兩個版本共用）：`payload` 決定怎麼把一根 K 線轉成 JSON 文字 */
private fun scenario(log: LabLog, payload: (L08Bar) -> String) {
    val chart = L08FakeChart()
    for (bar in bars) chart.update(payload(bar), bar.time, log)
    log(
        "最後：圖表收到 ${chart.accepted} 筆（其中空白點 ${chart.whitespace} 筆），格式錯誤 ${chart.rejected} 筆",
    )
}

private class L08Point(val time: Int, val value: Double?)

// MARK: - Bug 版
private object L08Bug {
    suspend fun run(log: LabLog) = scenario(log) { bar ->
        // TODO ①：用字串插值組出 {"time":時間,"value":價格}（bar.time、bar.
        // value 直接放進字串）。目前回傳空字串只是佔位
        ""
    }
}

// MARK: - 修正版
private object L08Fixed {
    suspend fun run(log: LabLog) = scenario(log) { bar ->
        // TODO ②：建立 L08Point(bar.time, bar.value.isFinite() 才放 bar.value，
        // 否則放 null；
        //        用 Gson().toJson(point) 編碼成字串回傳（null
        // 的欄位不會輸出）。目前回傳空字串只是佔位
        ""
    }
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "圖表少一筆資料，還回報錯誤",
    question = "第 7 題：用 WebView 放 TradingView Lightweight Charts，資料怎麼傳過去？要注意什麼？",
    goal = "傳給網頁的資料要用 JSON 編碼器產生，不要用字串插值；NaN、無限大要先用 `isFinite()` 檢查，不能直接送出。先寫出字串插值的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange07-kline/L08_JsonNaN.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看哪一筆被圖表丟掉，收到的文字長什麼樣子？",
        "想一想：Kotlin 把 NaN 轉成文字是什麼？JSON 認得這個字嗎？（JavaScript 的 `JSON.parse` 一樣不認得）",
        "寫 TODO ②（修正版），選「修正版」執行，五筆都要被圖表收到，沒有錯誤；第 3 根是只有時間的空白點。",
        "進階：Android 的 `evaluateJavascript` 只吃字串，要自己做 JSON 序列化並正確跳脫特殊字元；`Gson.toJson` 遇到 NaN 會直接丟出 IllegalArgumentException，所以一樣要先檢查。",
    ),
    expectBug = "第 3 根的文字是 `{\"time\":180,\"value\":NaN}`，圖表丟掉：「最後：圖表收到 4 筆（其中空白點 0 筆），格式錯誤 1 筆」。",
    expectFixed = "第 3 根的文字是 `{\"time\":180}`（空白點）：「最後：圖表收到 5 筆（其中空白點 1 筆），格式錯誤 0 筆」。",
    file = "exchange07-kline/L08_JsonNaN.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, payload) 執行"
            }

            "最後：圖表收到 4 筆（其中空白點 0 筆），格式錯誤 1 筆" !in lines -> {
                "Bug 還沒重現：用字串插值組 JSON（\"\"\"{\"time\":\${bar.time},\"value\":\${bar.value}}\"\"\"），第 3 根的 value 是 NaN，會變成不合法的 JSON"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, payload) 執行"
            }

            "最後：圖表收到 5 筆（其中空白點 1 筆），格式錯誤 0 筆" !in lines -> {
                "value 不是有限的數字（bar.value.isFinite() 為 false）時，L08Point 的 value 要設成 null，只傳時間；其他的照常傳，用 Gson().toJson 編碼"
            }

            else -> {
                null
            }
        }
    },
)
