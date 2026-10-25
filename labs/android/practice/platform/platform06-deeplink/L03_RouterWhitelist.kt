// L03：行銷連結一點就下單，還能被導到釣魚網站（Router 要用白名單）
// 對應複習站：Platform06DeepLink 第 5 題「怎麼避免外部連結觸發下單這類敏感操作？」
//
// 情境：App 的 Router 把外部連結（推播、簡訊、網頁、別的 App）轉成要開的畫面。官方網域是 www.example.com，
//        只開放兩條路徑：/ticker/{代號}（行情頁）和 /alert/{編號}（價格提醒，要登入）。
//        演練用 11 個連結測試 Router：3 個正常的，8 個是別人精心做的壞連結，每個連結都有「應該得到的結果」。
//        （用 java.net.URI 解析，純 Kotlin，JVM 單元測試也跑得到；真的 App 裡的 Uri.parse 是一樣的做法。）
//
// 你要寫：
//   ① Bug 版（L03Bug.route）：用「盡量處理」的寫法：不檢查 scheme 和 host；有 redirect 參數就 Web；
//      只看路徑第一段，ticker／alert／order／withdraw 各回傳對應的 LinkRoute，其他回 Home。
//   ② 修正版（L03Fixed.route）：白名單。只接受 https 加上 allowedHosts 內的 host；路徑解碼後切段，只接受 /ticker/{代號}、/alert/{編號}
//      （用 symbolRegex、alertIdRegex 限制字元）和根路徑；query 完全不讀；其他一律 Invalid。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform06-deeplink/L03_RouterWhitelist.kt
package tw.harry.labs.practice.platform06deeplink

import java.net.URI
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 連結解析出來的結果（兩個版本共用）。Order、Withdraw、Web 是「不應該由連結直接觸發」的動作，只有 Bug 版會回傳它們 */
private sealed class LinkRoute {
    object Home : LinkRoute()
    data class Ticker(val symbol: String) : LinkRoute()
    data class Alert(val id: String) : LinkRoute()
    data class Login(val resume: String) : LinkRoute()
    data class Order(val symbol: String, val amount: String, val autoSubmit: Boolean) : LinkRoute()
    data class Withdraw(val address: String, val amount: String) : LinkRoute()
    data class Web(val url: String) : LinkRoute()
    object Invalid : LinkRoute()

    fun describe(): String = when (this) {
        Home -> "home"
        is Ticker -> "ticker($symbol)"
        is Alert -> "alert($id)"
        is Login -> "login(resume: $resume)"
        is Order -> "order($symbol, $amount, 自動送出: $autoSubmit)"
        is Withdraw -> "withdraw($address, $amount)"
        is Web -> "web($url)"
        Invalid -> "invalid"
    }
}

private val allowedHosts = setOf("www.example.com")

/** 測試的連結（兩個版本共用）：名稱、連結、是否已登入、應該得到的結果 */
private class LinkCase(val name: String, val url: String, val loggedIn: Boolean, val expected: LinkRoute)

private val cases = listOf(
    LinkCase("正常的行情連結", "https://www.example.com/ticker/btcjpy", false, LinkRoute.Ticker("btcjpy")),
    LinkCase("正常的提醒連結（已登入）", "https://www.example.com/alert/ab12cd34", true, LinkRoute.Alert("ab12cd34")),
    LinkCase("正常的提醒連結（未登入）", "https://www.example.com/alert/ab12cd34", false, LinkRoute.Login("https://www.example.com/alert/ab12cd34")),
    LinkCase("假的網域", "https://evil.example.org/ticker/btcjpy", true, LinkRoute.Invalid),
    LinkCase("不是 https", "http://www.example.com/ticker/btcjpy", true, LinkRoute.Invalid),
    LinkCase("帶金額的下單連結", "https://www.example.com/order?symbol=btcjpy&amount=10&action=buy", true, LinkRoute.Invalid),
    LinkCase("提領連結", "https://www.example.com/withdraw?address=abc&amount=1", true, LinkRoute.Invalid),
    LinkCase("路徑用 %2F 繞過", "https://www.example.com/ticker/btcjpy%2F..%2Fwithdraw", true, LinkRoute.Invalid),
    LinkCase("代號夾帶特殊字元", "https://www.example.com/ticker/btc%3Cscript%3E", true, LinkRoute.Invalid),
    LinkCase("redirect 參數指向別的網站", "https://www.example.com/ticker/btcjpy?redirect=https://evil.example.org/login", true, LinkRoute.Ticker("btcjpy")),
    LinkCase("沒列出的路徑", "https://www.example.com/settings/reset", true, LinkRoute.Invalid),
)

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, route: (String, Boolean) -> LinkRoute) {
    var wrong = 0
    for (case in cases) {
        val result = route(case.url, case.loggedIn)
        if (result == case.expected) {
            log("✓ ${case.name} → ${result.describe()}")
        } else {
            wrong++
            log("✗ ${case.name} → ${result.describe()}（應該是 ${case.expected.describe()}）")
        }
    }
    log("共 ${cases.size} 個連結，處理錯誤 $wrong 個")
}

// MARK: - Bug 版
private object L03Bug {
    fun route(urlString: String, isLoggedIn: Boolean): LinkRoute {
        // TODO ①：用「盡量處理」的寫法，不檢查 scheme 和 host，也不驗證任何參數：
        //        1. URI(urlString)（失敗回 Home）；uri.query 整理成 Map；uri.path 用 "/" 切成 parts
        //        2. query 有 "redirect" 就回傳 Web(它的值)
        //        3. 看 parts.firstOrNull()：ticker → Ticker(parts[1])；alert → 已登入 Alert(parts[1])，未登入 Login(urlString)；
        //           order → Order(symbol, amount, autoSubmit)（query["action"] == "buy" 就自動送出）；withdraw → Withdraw(address, amount)；
        //           其他 → Home
        return LinkRoute.Home
    }

    suspend fun run(log: LabLog) = scenario(log, ::route)
}

// MARK: - 修正版
private object L03Fixed {
    private val symbolRegex = Regex("^[a-z0-9]{3,12}$")
    private val alertIdRegex = Regex("^[A-Za-z0-9]{6,32}$")

    fun route(urlString: String, isLoggedIn: Boolean): LinkRoute {
        // TODO ②：白名單：
        //        1. URI(urlString)（失敗回 Invalid）；uri.scheme 要是 "https"，uri.host（轉小寫）要在 allowedHosts 裡，否則 Invalid
        //        2. uri.path（已解碼，%2F 會變成 /）用 "/" 切成 parts；parts 是空的回 Home
        //        3. 兩段而且 parts[0] == "ticker"、symbolRegex.matches(parts[1]) → Ticker
        //        4. 兩段而且 parts[0] == "alert"、alertIdRegex.matches(parts[1]) → 已登入 Alert，未登入 Login(urlString)
        //        5. 其他一律 Invalid；query 完全不要讀
        return LinkRoute.Invalid
    }

    suspend fun run(log: LabLog) = scenario(log, ::route)
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("共 ") && "處理錯誤" in it }?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l03 = Lab(
    id = "L03",
    title = "行銷連結一點就下單，還能導去釣魚網站",
    question = "第 5 題：怎麼避免外部連結觸發下單這類敏感操作？",
    goal = "Router 把外部連結當成不可信的輸入：只接受白名單內的 host 與路徑，驗證參數，連結只能「開到畫面」，不能直接下單、提領或導向別的網站。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看 11 個連結的結果：哪些壞連結被接受了？「帶金額的下單連結」變成什麼？出現 ✓。",
        "想一想：連結的內容是誰寫的？「盡量處理」的 Router 對沒看過的連結會怎麼做？",
        "寫 TODO ②，選「修正版」執行，11 個連結都要得到應該的結果。",
        "進階：試試把路徑改成 /ticker/BTCJPY（大寫），或在代號後面加 %00，看 Regex 擋不擋得住。Activity 的 intent-filter 要設 exported，extras 也是外部輸入，同樣要驗證。",
    ),
    expectBug = "有 8 個連結處理錯誤：假的網域、http、下單、提領、%2F、特殊字元、redirect、沒列出的路徑；其中下單連結變成 order(…, 自動送出: true)。",
    expectFixed = "「共 11 個連結，處理錯誤 0 個」，壞連結全部是 invalid。",
    file = "platform06-deeplink/L03_RouterWhitelist.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val wrong = wrongCount(lines)
        when {
            wrong == null -> "演練沒有跑完：要用 scenario(log, ::route) 執行"
            lines.none { it.startsWith("✓ 正常的行情連結") } -> "Bug 版也要能處理正常的連結：/ticker/{代號} 回傳 Ticker(代號)"
            wrong < 6 -> "Bug 還沒重現：Router 要用「盡量處理」的寫法，不檢查 scheme 與 host，也不驗證參數（目前只擋錯 $wrong 個，應該 6 個以上）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val wrong = wrongCount(lines)
        when {
            wrong == null -> "演練沒有跑完：要用 scenario(log, ::route) 執行"
            wrong == 0 -> null
            else -> "還有 $wrong 個連結處理錯誤，例如：${lines.firstOrNull { it.startsWith("✗") }}"
        }
    },
)
