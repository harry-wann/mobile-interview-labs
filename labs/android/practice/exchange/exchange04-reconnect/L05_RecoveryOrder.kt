// L05：斷線重連後，價格倒退回舊的（快照比後續推送晚到，卻把畫面蓋回舊資料）
// 對應複習站：Exchange04Reconnect 第 5 題「App 進背景、回前景，以及重連成功之後，要依什麼順序恢復？」
//
// 情境：重連後要重新訂閱，並對狀態型資料取一份完整的最新資料（snapshot，快照）補洞。每則資料都有序號 seq（BBO 的 seqId）。
//        畫面原本停在 seq 95、價格 9,999,500。重新訂閱之後，快照的請求還在路上，推送先到了：seq 100、101、102（價格各增加 100）；
//        最後快照才回來，它是在 seq 100 的時候產生的（價格 10,000,000）。
//        （訂單簿的快照與增量，會在訂單簿單元用同樣的做法練習；這裡看的是恢復的順序。）
//
// 你要寫：
//   ① Bug 版（L05Bug.Feed）：onPush：序號比現在新（seq > this.seq）就直接換掉 seq 和 price；onSnapshot：整份蓋上去。
//   ② 修正版（L05Fixed.Feed）：快照到之前，推送先暫存；快照到了，先套用快照，再依序套用序號比快照新的暫存推送；快照到了之後的推送才直接套用。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange04-reconnect/L05_RecoveryOrder.kt
package tw.harry.labs.practice.exchange04reconnect

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private sealed interface Arrival {
    data class Push(val seq: Int, val price: BigDecimal) : Arrival
    data class Snapshot(val seq: Int, val price: BigDecimal) : Arrival
}

private interface PriceFeed {
    val seq: Int
    val price: BigDecimal
    fun onPush(seq: Int, price: BigDecimal)
    fun onSnapshot(seq: Int, price: BigDecimal)
}

private fun price(text: String) = BigDecimal(text)

/** 演練流程（兩個版本共用）：重連、重新訂閱之後，訊息依序到達 */
private fun scenario(log: LabLog, feed: PriceFeed) {
    val arrivals = listOf(
        Arrival.Push(100, price("10000000")), Arrival.Push(101, price("10000100")),
        Arrival.Push(102, price("10000200")), Arrival.Snapshot(100, price("10000000")),
    )
    log("重連前：畫面停在 seq ${feed.seq}，價格 ${feed.price.toPlainString()}")
    for (arrival in arrivals) {
        when (arrival) {
            is Arrival.Push -> {
                feed.onPush(arrival.seq, arrival.price)
                log("收到推送 seq ${arrival.seq} → 畫面 seq ${feed.seq}，價格 ${feed.price.toPlainString()}")
            }
            is Arrival.Snapshot -> {
                feed.onSnapshot(arrival.seq, arrival.price)
                log("收到快照 seq ${arrival.seq} → 畫面 seq ${feed.seq}，價格 ${feed.price.toPlainString()}")
            }
        }
    }
    log("最後畫面：seq ${feed.seq}，價格 ${feed.price.toPlainString()}（伺服器最新是 seq 102，價格 10000200）")
}

// MARK: - Bug 版
private object L05Bug {
    class Feed : PriceFeed {
        override var seq = 95
            private set
        override var price = price("9999500")
            private set

        override fun onPush(seq: Int, price: BigDecimal) {
            // TODO ①-1：onPush：序號 seq 比現在的 this.seq 新，就把 this.seq、this.price 換成新的
        }

        override fun onSnapshot(seq: Int, price: BigDecimal) {
            // TODO ①-2：onSnapshot：快照一到，就把 this.seq、this.price 整個換成快照的
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Feed())
}

// MARK: - 修正版
private object L05Fixed {
    class Feed : PriceFeed {
        override var seq = 95
            private set
        override var price = price("9999500")
            private set
        private var snapshotArrived = false
        private val buffered = mutableListOf<Pair<Int, BigDecimal>>()

        override fun onPush(seq: Int, price: BigDecimal) {
            // TODO ②-1：onPush：snapshotArrived 還是 false 時，把 seq to price 先存進 buffered 就 return；
            //          快照到了之後，序號比 this.seq 新的推送才直接套用
        }

        override fun onSnapshot(seq: Int, price: BigDecimal) {
            // TODO ②-2：onSnapshot：先把 this.seq、this.price 換成快照的，把 snapshotArrived 設成 true；
            //          再把 buffered 依 seq 由小到大排序，序號比 this.seq 新的依序套用；最後清空 buffered
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Feed())
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "斷線重連後，價格倒退回舊的",
    question = "第 5 題：App 進背景、回前景，以及重連成功之後，要依什麼順序恢復？",
    goal = "重連後要補資料：快照回來之前，推送先暫存；快照回來後先套用快照，再套用比快照新的暫存推送。畫面不能倒退。先寫出推送直接套用、快照整份蓋上去的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange04-reconnect/L05_RecoveryOrder.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看畫面的 seq 和價格一路怎麼變，最後停在哪裡。",
        "想一想：快照是在 seq 100 的時候產生的，它回來時畫面已經到 seq 102 了，直接蓋上去會怎樣？",
        "寫 TODO ②（修正版），選「修正版」執行，最後畫面要到 seq 102。",
        "進階：恢復的完整順序是：標示資料過期 → 建立連線（私有頻道先重新認證）→ 重新訂閱 → 取快照補洞 → 最後才清除過期標示。",
    ),
    expectBug = "畫面先到 seq 102，快照回來後又被蓋回 seq 100，「最後畫面：seq 100，價格 10000000」，比伺服器的最新資料舊。",
    expectFixed = "快照之前的推送先暫存，快照回來後套用快照，再套用 101、102，「最後畫面：seq 102，價格 10000200」。",
    file = "exchange04-reconnect/L05_RecoveryOrder.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後畫面") } -> "演練沒有跑完：要用 scenario(log, feed) 執行"
            "收到推送 seq 102 → 畫面 seq 102，價格 10000200" !in lines -> "Bug 版的推送要直接套用：序號比現在新（seq > this.seq）就換掉 seq 和價格"
            lines.none { it.startsWith("最後畫面：seq 100，價格 10000000") } -> "Bug 還沒重現：快照一到就把 seq、價格整份蓋上去，畫面會從 seq 102 倒退回 seq 100"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後畫面") } -> "演練沒有跑完：要用 scenario(log, feed) 執行"
            "收到推送 seq 102 → 畫面 seq 102，價格 10000200" in lines -> "快照還沒回來之前，推送不能直接套用到畫面，要先暫存"
            lines.none { it.startsWith("最後畫面：seq 102，價格 10000200") } -> "快照回來後，先套用快照（seq 100），再依序套用暫存裡序號比快照新的推送（101、102）"
            else -> null
        }
    },
)
