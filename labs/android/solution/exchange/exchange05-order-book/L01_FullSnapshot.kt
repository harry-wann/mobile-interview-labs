// L01：賣光的價位還掛在板上（depth 推送是整份最新的板，卻當成增量合併）
// 對應複習站：Exchange05OrderBook 第 1 題「這套交易所 API 文件裡的 depth（板的深度）資料長什麼樣？它是完整快照還是增量更新？」
//
// 情境：depth 頻道每次推送都帶完整的 bids（買方）與 asks（賣方），沒有序號，只有 ts（時間戳）。
//       收到三則推送：ts 1000 → ts 1002（買方 10,000,000 和賣方 10,010,000 都成交光了，所以沒有出現在推送裡）→ ts 1001（網路上慢了一步、晚到的舊資料）。
//       伺服器最新的板是 ts 1002：最佳買 9995000×0.2，最佳賣 10020000×0.5，買賣各 2 檔。
// Bug 版：把每則推送「合併」進本地的板：推送裡有的價位更新數量，沒有出現的價位不動；而且不看 ts，後到的就蓋上去。
//        成交光了的價位變成幽靈檔位留在板上，晚到的舊資料還把數量改回去。
// 修正版：每則推送整份取代本地的板；ts 不比目前的板新的推送直接丟掉。
package tw.harry.labs.solution.exchange05orderbook

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L01Push(val ts: Long, val bids: List<E05Level>, val asks: List<E05Level>)

private interface L01Screen {
    val depth: E05Depth

    /** 回傳 true 代表這則推送有被套用；false 代表被丟掉 */
    fun onPush(push: L01Push): Boolean
}

/** 演練流程（兩個版本共用）：三則推送依序到達 */
private fun scenario(log: LabLog, screen: L01Screen) {
    val pushes = listOf(
        L01Push(1000, listOf(E05Level("10000000", "0.4"), E05Level("9990000", "0.6")),
            listOf(E05Level("10010000", "0.3"), E05Level("10020000", "0.5"))),
        L01Push(1002, listOf(E05Level("9995000", "0.2"), E05Level("9990000", "0.6")),
            listOf(E05Level("10020000", "0.5"), E05Level("10030000", "0.4"))),
        L01Push(1001, listOf(E05Level("10000000", "0.1"), E05Level("9990000", "0.6")),
            listOf(E05Level("10010000", "0.2"), E05Level("10020000", "0.5"))),
    )
    for (push in pushes) {
        val applied = screen.onPush(push)
        log("收到 ts ${push.ts}（${if (applied) "套用" else "過期，丟掉"}）→ ${screen.depth.summary}")
    }
    log("最後：${screen.depth.summary}（伺服器最新是 ts 1002：最佳買 9995000×0.2，最佳賣 10020000×0.5，買 2 檔，賣 2 檔）")
}

// MARK: - Bug 版
private object L01Bug {
    class Screen : L01Screen {
        override val depth = E05Depth()

        override fun onPush(push: L01Push): Boolean {
            depth.apply(push.bids, push.asks)           // 合併：只更新推送裡有的價位，其他不動；後到的就蓋上去
            return true
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Screen())
}

// MARK: - 修正版
private object L01Fixed {
    class Screen : L01Screen {
        override var depth = E05Depth()
            private set
        private var lastTs = 0L

        override fun onPush(push: L01Push): Boolean {
            if (push.ts <= lastTs) return false         // 不比目前的板新：丟掉
            lastTs = push.ts
            depth = E05Depth()                          // 整份取代：先清空，再放進這則推送的內容
            depth.apply(push.bids, push.asks)
            return true
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Screen())
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "賣光的價位還掛在板上",
    question = "第 1 題：這套交易所 API 文件裡的 depth（板的深度）資料長什麼樣？它是完整快照還是增量更新？",
    goal = "depth 每次推送都是整份最新的板，所以要整份取代本地的板，並且用 ts 丟掉比目前更舊的推送，不能當增量合併。",
    steps = listOf(
        "選「Bug 版」執行，看 ts 1002 那一行：買賣各幾檔？最佳賣價是多少？賣方 10,010,000 明明已經成交光了。",
        "再看最後一行：晚到的 ts 1001 把板改成什麼樣子？",
        "選「修正版」執行，最後的板要和伺服器最新的 ts 1002 一模一樣，晚到的 ts 1001 要顯示「過期，丟掉」。",
        "想一想：如果協定是「完整快照加增量更新」（下一題），這樣整份取代還對嗎？",
    ),
    expectBug = "ts 1002 之後買賣各變成 3 檔（成交光了的 10,000,000 和 10,010,000 還在），最後「最佳買 10000000×0.1，最佳賣 10010000×0.2，買 3 檔，賣 3 檔」，連晚到的舊資料都套用了。",
    expectFixed = "ts 1002 之後買賣各 2 檔，ts 1001 顯示「過期，丟掉」，最後「最佳買 9995000×0.2，最佳賣 10020000×0.5，買 2 檔，賣 2 檔」。",
    file = "exchange05-order-book/L01_FullSnapshot.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, screen) 執行"
            lines.none { it.startsWith("收到 ts 1002") && it.contains("買 3 檔，賣 3 檔") } ->
                "Bug 版要把推送合併進 depth（depth.apply），不清空：成交光了的價位會留下來，ts 1002 之後應該是買 3 檔、賣 3 檔"
            lines.none { it.startsWith("最後：最佳買 10000000×0.1，最佳賣 10010000×0.2") } ->
                "Bug 還沒重現：不要看 ts，晚到的 ts 1001 也要照樣套用（回傳 true），最佳買會變成 10000000×0.1"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, screen) 執行"
            lines.none { it.startsWith("收到 ts 1001（過期，丟掉）") } -> "晚到的 ts 1001 比目前的板（ts 1002）舊，要丟掉並回傳 false"
            lines.none { it.startsWith("最後：最佳買 9995000×0.2，最佳賣 10020000×0.5，買 2 檔，賣 2 檔") } ->
                "每則推送要整份取代 depth：先清空（depth = E05Depth()），再放進推送的內容，成交光了的價位才會消失"
            else -> null
        }
    },
)
