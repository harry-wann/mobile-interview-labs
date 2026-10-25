// L02：漏收一筆更新，板上留下已經不存在的價位（增量更新沒有檢查序號）
// 對應複習站：Exchange05OrderBook 第 2 題「「完整快照加增量更新」
// 是什麼？為什麼序號跳號就要重新抓完整資料？」
//
// 情境：增量更新（delta）只告訴你「哪些價位變了」，每筆帶遞增的序號 seq。
// 快照在 seq 100，之後依序有 101、102、103、104、105。
//       102 的內容是「賣方 10,010,000 賣光了（數量 0）」，
// 但這一則在網路上漏掉了，App 收到的是 101、103、104、105。
//       （數量 0 代表刪除這一檔，這裡 `E05Depth.apply` 已經處理好。）
//       伺服器 seq 105 的板：最佳買 10000000×0.5，最佳賣 10020000×0.3，買 3
// 檔，賣 2 檔。
//
// 你要寫：
//   ① Bug 版（L02Bug.Screen.onDelta）：不檢查序號，直接 depth.apply，記下 seq，
// 回傳 Applied。
//   ② 修正版（L02Fixed.Screen.onDelta）：seq 小於等於目前的 → Stale；
// 不是目前的 seq + 1 → Gap(seq + 1)（不套用）；否則 apply、記下 seq、回傳
// Applied。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange05-order-book/L02_SeqGap.kt
package tw.harry.labs.practice.exchange05orderbook

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L02Delta(
    val seq: Int,
    val bids: List<E05Level>,
    val asks: List<E05Level>,
)

private sealed interface L02Result {
    data object Applied : L02Result
    data object Stale : L02Result
    data class Gap(val expected: Int) : L02Result
}

private interface L02Screen {
    val depth: E05Depth
    val seq: Int

    /** 用快照取代整份板（快照代表伺服器在 seq 當下的板） */
    fun load(snapshot: E05Depth, seq: Int)
    fun onDelta(delta: L02Delta): L02Result
}

/**
 * 演練流程（兩個版本共用）：伺服器一路更新，App 收到除了 102 以外的增量；
 * 回報跳號時，立刻重新抓一份最新的快照
 */
private fun scenario(log: LabLog, screen: L02Screen) {
    val server = E05Depth()
    server.apply(
        listOf(E05Level("10000000", "0.4"), E05Level("9990000", "0.6")),
        listOf(E05Level("10010000", "0.3"), E05Level("10020000", "0.5")),
    )
    screen.load(server.copy(), 100)
    log("快照 seq 100 → ${screen.depth.summary}")

    val deltas = listOf(
        L02Delta(
            101,
            listOf(E05Level("10000000", "0.5")),
            listOf(E05Level("10010000", "0.1")),
        ),
        // 這一則在網路上漏掉了
        L02Delta(102, emptyList(), listOf(E05Level("10010000", "0"))),
        L02Delta(103, listOf(E05Level("9995000", "0.2")), emptyList()),
        L02Delta(104, emptyList(), listOf(E05Level("10030000", "0.4"))),
        L02Delta(105, emptyList(), listOf(E05Level("10020000", "0.3"))),
    )
    for (delta in deltas) {
        // 伺服器的板每一筆都更新
        server.apply(delta.bids, delta.asks)
        if (delta.seq == 102) {
            log("seq 102 在網路上漏掉了，App 沒有收到")
            continue
        }
        when (val result = screen.onDelta(delta)) {
            L02Result.Applied -> {
                log("收到 seq ${delta.seq}，套用 → ${screen.depth.summary}")
            }

            L02Result.Stale -> {
                log("收到 seq ${delta.seq}，重複或過期，忽略")
            }

            is L02Result.Gap -> {
                log("跳號：期待 ${result.expected}，收到 ${delta.seq}，重新抓快照")
                screen.load(server.copy(), delta.seq)
                log("快照 seq ${delta.seq} → ${screen.depth.summary}")
            }
        }
    }
    log(
        "最後：seq ${screen.seq}，${screen.depth.summary}（伺服器 seq 105：最佳買 10000000×0.5，最佳賣 10020000×0.3，買 3 檔，賣 2 檔）",
    )
}

// MARK: - Bug 版
private object L02Bug {
    class Screen : L02Screen {
        override var depth = E05Depth()
            private set
        override var seq = 0
            private set

        override fun load(snapshot: E05Depth, seq: Int) {
            depth = snapshot
            this.seq = seq
        }

        override fun onDelta(delta: L02Delta): L02Result {
            // TODO ①：不看序號，直接 depth.apply(delta.bids, delta.asks)，把
            // seq 記成 delta.seq
            return L02Result.Applied
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Screen())
}

// MARK: - 修正版
private object L02Fixed {
    class Screen : L02Screen {
        override var depth = E05Depth()
            private set
        override var seq = 0
            private set

        override fun load(snapshot: E05Depth, seq: Int) {
            depth = snapshot
            this.seq = seq
        }

        override fun onDelta(delta: L02Delta): L02Result {
            // TODO ②-1：delta.seq 小於等於目前的 seq：重複或過期，回傳
            // L02Result.Stale
            // TODO ②-2：delta.seq 不是 seq + 1：跳號，板不可信，不要套用，回傳
            // L02Result.Gap(seq + 1)
            // TODO ②-3：接得上才 depth.apply，記下 seq = delta.seq
            return L02Result.Applied
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Screen())
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "漏收一筆更新，板上留下不存在的價位",
    question = "第 2 題：「完整快照加增量更新」是什麼？為什麼序號跳號就要重新抓完整資料？",
    goal = "增量更新套用前要檢查序號是不是上一筆加 1。重複的忽略；跳號就不能硬接，要回報 gap，重新抓快照。先寫出不檢查序號的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange05-order-book/L02_SeqGap.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看漏掉 seq 102 之後，最佳賣價停在哪裡？賣方 10,010,000 在伺服器上早就賣光了。",
        "最後一行和伺服器 seq 105 的板比一比，哪裡不一樣？",
        "寫 TODO ②（修正版），選「修正版」執行，要看到「跳號：期待 102，收到 103，重新抓快照」，最後的板和伺服器一致。",
        "進階：跳號之後到快照回來之前，收到的增量該怎麼辦？（暫存、不直接套用，見 Exchange04 L05）",
    ),
    expectBug = "沒有任何跳號訊息；最後「最佳賣 10010000×0.1，買 3 檔，賣 3 檔」，多了一個伺服器上早就賣光的價位。",
    expectFixed = "「跳號：期待 102，收到 103，重新抓快照」，快照回來後再接 104、105，最後「最佳賣 10020000×0.3，買 3 檔，賣 2 檔」，和伺服器一致。",
    file = "exchange05-order-book/L02_SeqGap.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, screen) 執行"
            }

            lines.any { it.startsWith("跳號") } -> {
                "Bug 版不檢查序號：onDelta 一律套用並回傳 Applied"
            }

            lines.none {
                it.startsWith(
                    "最後：seq 105，最佳買 10000000×0.5，最佳賣 10010000×0.1，買 3 檔，賣 3 檔",
                )
            } -> {
                "Bug 還沒重現：漏掉 102 之後的 103、104、105 都直接套用，賣方 10,010,000×0.1 會留在板上"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, screen) 執行"
            }

            "跳號：期待 102，收到 103，重新抓快照" !in lines -> {
                "收到 103 時，上一筆是 101，要回傳 Gap(seq + 1)，不要套用"
            }

            lines.none {
                it.startsWith(
                    "最後：seq 105，最佳買 10000000×0.5，最佳賣 10020000×0.3，買 3 檔，賣 2 檔",
                )
            } -> {
                "跳號之後要重新抓快照；快照之後的 104、105 序號接得上，要正常套用"
            }

            else -> {
                null
            }
        }
    },
)
