// L07：回到前景之後，K 線少了一段（先抓快照，再重新訂閱）
// 對應複習站：Platform01Lifecycle 第 6 題「交易所 App 在進背景、回前景時，
// 要做哪些事？」
//
// 情境：行情每筆資料都有遞增的序號。App 進背景之前收到序號 1 到 5，
// 在背景的這段時間行情走到序號 10（連線已斷，這 5 筆不會補送）。
//        回到前景時重新接上行情。
//
// 你要寫：
//   ① Bug 版（L07Bug）：回前景直接重新訂閱即時行情，不取快照。
//   ② 修正版（L07Fixed）：先取快照，把 client.lastSeq 更新到快照涵蓋的序號，
// 再訂閱。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform01-lifecycle/L07_SnapshotGap.kt
package tw.harry.labs.practice.platform01lifecycle

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的行情伺服器（兩個版本共用） */
private class L07MarketServer(var latestSeq: Int) {
    /** REST：取得最新快照，回傳它涵蓋到哪一個序號 */
    fun snapshot(): Int = latestSeq

    /** 即時串流：從現在開始，下一筆是 latestSeq + 1（不會補送過去的） */
    fun subscribe(): Int = latestSeq + 1
}

/** 客戶端的行情狀態（兩個版本共用） */
private class L07MarketClient {
    // 進背景之前收到序號 5
    var lastSeq = 5

    /** 收到即時行情的第一筆，回傳漏了幾筆 */
    fun missing(firstLiveSeq: Int): Int = firstLiveSeq - lastSeq - 1
}

/**
 * 演練流程（兩個版本共用）：背景期間行情走到序號 10，回前景時用 resume 接上行情
 */
private fun scenario(
    log: LabLog,
    resume: (L07MarketClient, L07MarketServer) -> Int,
) {
    val server = L07MarketServer(latestSeq = 5)
    val client = L07MarketClient()
    // 在背景的期間，行情走到序號 10
    server.latestSeq = 10
    val firstLive = resume(client, server)
    log("回前景後，第一筆即時行情是序號 $firstLive")
    log("回前景後，漏了 ${client.missing(firstLive)} 筆行情")
}

// MARK: - Bug 版
private object L07Bug {
    fun resume(client: L07MarketClient, server: L07MarketServer): Int =
        // TODO ①：直接重新訂閱：回傳 server.subscribe() 的結果（不取快照）
        0

    fun run(log: LabLog) = scenario(log, ::resume)
}

// MARK: - 修正版
private object L07Fixed {
    fun resume(client: L07MarketClient, server: L07MarketServer): Int {
        // TODO ②：先用 server.snapshot() 取快照，把 client.lastSeq
        // 更新成快照涵蓋的序號
        return server.subscribe() // 再接上即時行情
    }

    fun run(log: LabLog) = scenario(log, ::resume)
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "回前景之後，行情少了一段",
    question = "第 6 題：交易所 App 在進背景、回前景時，要做哪些事？",
    goal = "背景期間連線已斷，漏掉的行情不會補送。回前景要先抓快照，再接上即時行情，中間不能有斷層。先寫出漏掉行情的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform01-lifecycle/L07_SnapshotGap.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」按執行，看「回前景後，漏了幾筆行情」。",
        "寫 TODO ②（修正版），選「修正版」執行：先取快照再訂閱，漏 0 筆。",
        "想一想：快照和即時行情的序號怎麼接？（快照涵蓋到序號 10，即時行情從 11 開始）",
    ),
    expectBug = "第一筆即時行情是序號 11，漏了 5 筆（序號 6 到 10）。",
    expectFixed = "快照涵蓋到序號 10，第一筆即時行情是序號 11，漏了 0 筆。",
    file = "platform01-lifecycle/L07_SnapshotGap.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            !lines.contains(
                "回前景後，第一筆即時行情是序號 11",
            ) -> "演練沒有跑完：resume 要回傳 server.subscribe() 的序號"

            lines.contains("回前景後，漏了 0 筆行情") -> "Bug 還沒重現：Bug 版直接重新訂閱，不要先取快照"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            !lines.contains(
                "回前景後，第一筆即時行情是序號 11",
            ) -> "演練沒有跑完：resume 要回傳 server.subscribe() 的序號"

            !lines.contains(
                "回前景後，漏了 0 筆行情",
            ) -> "還是有漏：先呼叫 server.snapshot()，把 client.lastSeq 更新到快照的序號，再訂閱"

            else -> null
        }
    },
)
