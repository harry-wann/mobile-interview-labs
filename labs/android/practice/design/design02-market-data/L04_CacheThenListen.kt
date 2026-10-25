// L04：切到詳細頁，畫面顯示的是舊價格，要等下一筆推送才更新（先給畫面快取，再登記監聽，中間的推送漏掉了）
// 對應複習站：Design02MarketData 第 3 題「一條連線、好幾個畫面，要怎麼共用？」（Repository 的快取與推送要接得起來）
//
// 情境：Repository 把最新的價格存在快取裡，WebSocket 每推來一筆就更新快取、並通知已登記的畫面。
//       畫面打開詳細頁時呼叫 observe(symbol, onPrice)：要先拿到快取的價格（馬上有東西可以顯示），再收到之後的推送。
//       快取裡是 10000000 円。訂閱 topic 要等伺服器回應（suspend），就在這段時間裡，伺服器剛好推出一筆新價格 10000500 円。
//       這個交易對不常成交，下一筆推送要很久以後才會來。
//
// 你要寫（Repo.observe）：
//   ① Bug 版：先把快取交給畫面，接著呼叫 socket.subscribe，最後才登記監聽。
//   ② 修正版：先登記監聽，再把快取交給畫面，最後才呼叫 socket.subscribe。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/design/design02-market-data/L04_CacheThenListen.kt
package tw.harry.labs.practice.design02marketdata

import kotlinx.coroutines.yield
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val L04_OLD_PRICE = 10_000_000
private const val L04_NEW_PRICE = 10_000_500

/** 假的 WebSocket */
private class L04FakeSocket {
    var onMessage: ((String, Int) -> Unit)? = null

    /** 向伺服器訂閱：要等伺服器回應。就在這段時間裡，伺服器剛好推出一筆新價格 */
    suspend fun subscribe(topic: String) {
        yield()
        onMessage?.invoke(topic, L04_NEW_PRICE)
    }
}

/** Repository：快取＋監聽。收到推送的處理（receive）兩個版本一樣：先更新快取，再通知已登記的畫面 */
private open class L04Repository {
    val socket = L04FakeSocket()
    val cache = mutableMapOf("btcjpy" to L04_OLD_PRICE)
    val listeners = mutableMapOf<String, MutableList<(Int) -> Unit>>()

    init {
        socket.onMessage = { topic, price -> receive(topic, price) }
    }

    private fun receive(topic: String, price: Int) {
        val symbol = topic.split(".")[1]
        cache[symbol] = price
        listeners[symbol]?.toList()?.forEach { it(price) }
    }

    /** 畫面要看某個交易對：先拿到快取，再收到之後的推送（兩個版本各自覆寫） */
    open suspend fun observe(symbol: String, onPrice: (Int) -> Unit) {}
}

/** 演練流程（兩個版本共用） */
private suspend fun scenario(log: LabLog, repository: L04Repository) {
    var shown: Int? = null
    log("快取裡是 $L04_OLD_PRICE 円")
    repository.observe("btcjpy") { price ->
        shown = price
        log("畫面收到 $price 円")
    }
    log("訂閱完成。快取裡是 ${repository.cache["btcjpy"]} 円；伺服器最新的價格是 $L04_NEW_PRICE 円")
    log("最後：畫面顯示 ${shown ?: "（沒有）"} 円，快取裡是 ${repository.cache["btcjpy"]} 円")
}

// MARK: - Bug 版
private object L04Bug {
    class Repo : L04Repository() {
        override suspend fun observe(symbol: String, onPrice: (Int) -> Unit) {
            // TODO ①：依序做三件事：
            //   1. 如果 cache[symbol] 有值，先呼叫 onPrice 把快取交給畫面
            //   2. socket.subscribe("market.$symbol.bbo")
            //   3. 最後才把 onPrice 加進 listeners[symbol]（listeners.getOrPut(symbol) { mutableListOf() }）
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Repo())
}

// MARK: - 修正版
private object L04Fixed {
    class Repo : L04Repository() {
        override suspend fun observe(symbol: String, onPrice: (Int) -> Unit) {
            // TODO ②：改成這個順序：
            //   1. 先把 onPrice 加進 listeners[symbol]
            //   2. 再把 cache[symbol]（有值的話）交給 onPrice，步驟 1 和 2 中間不能有 suspend
            //   3. 最後才 socket.subscribe("market.$symbol.bbo")
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Repo())
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "切到詳細頁，畫面停在舊價格不更新",
    question = "第 3 題：一條連線、好幾個畫面，要怎麼共用？",
    goal = "畫面要先拿快取、再接推送時，順序是「先登記監聽，再給快取，最後才訂閱」。先給快取、最後才登記，中間（等訂閱的時候）推來的價格會漏掉，畫面停在舊價格。先寫出順序有問題的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/design/design02-market-data/L04_CacheThenListen.kt，寫 TODO ①（Bug 版）。",
        "選「練習」「Bug 版」執行，看畫面收到哪幾個價格、最後顯示什麼；對照快取裡的價格。",
        "想一想：快取明明已經是新價格，畫面為什麼還是舊的？（推送來的時候，畫面的監聽還沒登記。）",
        "寫 TODO ②（修正版），選「修正版」執行：畫面先收到快取的 10000000 円，再收到 10000500 円。",
        "想一想：登記監聽和給快取這兩步為什麼要連著做、中間不能有 suspend？（有 suspend 就可能被別的推送插隊，順序就亂了。）",
    ),
    expectBug = "畫面只收到 10000000 円，快取卻已經是 10000500 円：「最後：畫面顯示 10000000 円，快取裡是 10000500 円」。",
    expectFixed = "畫面先收到 10000000 円（快取），再收到 10000500 円（推送）：「最後：畫面顯示 10000500 円，快取裡是 10000500 円」。",
    file = "design02-market-data/L04_CacheThenListen.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, repository) 執行"
            "最後：畫面顯示 （沒有） 円，快取裡是 10000000 円" in lines -> "observe 還沒寫：要先把快取交給畫面、呼叫 socket.subscribe，最後才登記監聽"
            "最後：畫面顯示 10000000 円，快取裡是 10000500 円" !in lines ->
                "Bug 還沒重現：先把快取交給畫面，接著 socket.subscribe，最後才把 onPrice 加進 listeners，訂閱期間的推送就沒人收"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, repository) 執行"
            "最後：畫面顯示 （沒有） 円，快取裡是 10000000 円" in lines -> "observe 還沒寫：先登記監聽，再給畫面快取，最後 socket.subscribe"
            "最後：畫面顯示 10000500 円，快取裡是 10000500 円" !in lines ->
                "先把 onPrice 加進 listeners[symbol]，再交出快取，最後才 socket.subscribe"
            else -> null
        }
    },
)
