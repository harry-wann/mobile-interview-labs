// L03：最佳買價顯示「數量 0」（增量裡數量 0 的價位沒有刪掉）
// 對應複習站：Exchange05OrderBook 第 2 題「「完整快照加增量更新」
// 是什麼？為什麼序號跳號就要重新抓完整資料？」
//
// 情境：增量更新裡，數量 0 的意思是「這一檔沒有人掛單了」，要把那一檔整個移除，
// 不是顯示 0。
//       快照：買方 10,000,000×0.4、9,990,000×0.6、9,980,000×0.2；賣方 10,010,
// 000×0.3、10,020,000×0.5。
//       增量 1：買方 10,000,000 數量 0（撤單了）、賣方 10,010,000 數量
// 0（被吃光了）。增量 2：賣方新增 10,030,000×0.4。
//       伺服器的板：最佳買 9990000×0.6，最佳賣 10020000×0.5，買 2 檔，賣 2 檔。
// Bug 版：把增量的數量直接存進 Map：depth.bids[price] = qty。數量 0
// 的價位被存成「數量 0」，還留在板上。
// 修正版：數量是 0 就把那一檔從 Map 移除。
package tw.harry.labs.solution.exchange05orderbook

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private fun scenario(
    log: LabLog,
    apply: (E05Depth, List<E05Level>, List<E05Level>) -> Unit,
) {
    val depth = E05Depth()
    depth.apply(
        listOf(
            E05Level("10000000", "0.4"),
            E05Level("9990000", "0.6"),
            E05Level("9980000", "0.2"),
        ),
        listOf(E05Level("10010000", "0.3"), E05Level("10020000", "0.5")),
    )
    log("快照 → ${depth.summary}")

    apply(
        depth,
        listOf(E05Level("10000000", "0")),
        listOf(E05Level("10010000", "0")),
    )
    log("增量 1（10,000,000 和 10,010,000 數量 0）→ ${depth.summary}")
    apply(depth, emptyList(), listOf(E05Level("10030000", "0.4")))
    log("增量 2（賣方新增 10,030,000）→ ${depth.summary}")
    log(
        "最後：${depth.summary}（伺服器的板：最佳買 9990000×0.6，最佳賣 10020000×0.5，買 2 檔，賣 2 檔）",
    )
}

// MARK: - Bug 版
private object L03Bug {
    suspend fun run(log: LabLog) = scenario(log) { depth, bids, asks ->
        // 數量 0 也照存：那一檔變成「數量 0」
        for (level in bids) depth.bids[level.price] = level.qty
        for (level in asks) depth.asks[level.price] = level.qty
    }
}

// MARK: - 修正版
private object L03Fixed {
    suspend fun run(log: LabLog) = scenario(log) { depth, bids, asks ->
        // 數量 0：整檔移除
        for (level in bids) {
            if (level.qty.signum() ==
                0
            ) {
                depth.bids.remove(level.price)
            } else {
                depth.bids[level.price] =
                    level.qty
            }
        }
        for (level in asks) {
            if (level.qty.signum() ==
                0
            ) {
                depth.asks.remove(level.price)
            } else {
                depth.asks[level.price] =
                    level.qty
            }
        }
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "最佳買價顯示「數量 0」",
    question = "第 2 題：「完整快照加增量更新」是什麼？為什麼序號跳號就要重新抓完整資料？",
    goal = "增量更新裡數量 0 代表「這一檔沒有掛單了」，要把那一檔整個移除。存成 0 會讓最佳買賣價停在一個沒有人掛單的價位。",
    steps = listOf(
        "選「Bug 版」執行，看增量 1 之後的最佳買價與最佳賣價，數量是多少？",
        "和伺服器的板比一比：買、賣各有幾檔？",
        "選「修正版」執行，增量 1 之後最佳買要變成 9990000×0.6，最佳賣 10020000×0.5。",
        "想一想：如果畫面用 `depth.bestBid` 當成「可以賣出的最高價」，數量 0 的那一檔會讓使用者看到什麼？",
    ),
    expectBug = "增量 1 之後「最佳買 10000000×0，最佳賣 10010000×0，買 3 檔，賣 2 檔」，最佳價位是沒人掛單的幽靈檔位；最後買 3 檔、賣 3 檔。",
    expectFixed = "增量 1 之後「最佳買 9990000×0.6，最佳賣 10020000×0.5，買 2 檔，賣 1 檔」，最後「最佳買 9990000×0.6，最佳賣 10020000×0.5，買 2 檔，賣 2 檔」。",
    file = "exchange05-order-book/L03_ZeroQuantity.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, apply) 執行"
            }

            lines.none {
                it.startsWith("增量 1") &&
                    it.contains("最佳買 10000000×0，最佳賣 10010000×0，買 3 檔，賣 2 檔")
            } -> {
                "Bug 還沒重現：把增量的數量直接存進 Map（depth.bids[level.price] = level.qty），數量 0 的價位會留在板上"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, apply) 執行"
            }

            lines.none {
                it.startsWith("增量 1") &&
                    it.contains("最佳買 9990000×0.6，最佳賣 10020000×0.5，買 2 檔，賣 1 檔")
            } -> {
                "數量是 0 的價位要從 Map 移除（remove），增量 1 之後應該是買 2 檔、賣 1 檔"
            }

            lines.none {
                it.startsWith("最後：最佳買 9990000×0.6，最佳賣 10020000×0.5，買 2 檔，賣 2 檔")
            } -> {
                "增量 2 的新價位 10,030,000 還是要加進去（數量不是 0 就存進 Map）"
            }

            else -> {
                null
            }
        }
    },
)
