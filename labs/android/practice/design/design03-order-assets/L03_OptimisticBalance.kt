// L03：下單被拒絕了，可用餘額卻一直少 300,000 円（自己扣餘額的樂觀更新，失敗時沒有回復）
// 對應複習站：Design03OrderAssets 第 6 題「資產的數字要怎麼算才不會出錯？」
//
// 情境：可用餘額 1,000,000 円。使用者在下單頁按下「買入」，要買 300,000 円。送出之後伺服器還沒回覆，畫面要先顯示一些東西；
//       最後伺服器回覆：拒絕（限價超出允許範圍），沒有凍結任何資金，伺服器上的可用餘額還是 1,000,000。
//       （手續費、部分成交、捨入規則，App 很難算得和伺服器一模一樣，所以最終的餘額要以伺服器為準。）
//
// 你要寫：
//   ① Bug 版（L03Bug.Screen）：tapBuy 自己把 available 減掉下單金額；orderRejected 只把 status 設成「已被拒絕」，不把 available 改回來。
//   ② 修正版（L03Fixed.Screen）：tapBuy 不動 available，只把 status 設成「處理中」；orderRejected 把 status 設成「已被拒絕」。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/design/design03-order-assets/L03_OptimisticBalance.kt
package tw.harry.labs.practice.design03orderassets

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface L03WalletScreen {
    val available: Int
    val status: String

    /** 使用者按下買入（伺服器還沒回覆） */
    fun tapBuy(yen: Int)

    /** 伺服器回覆：拒絕 */
    fun orderRejected()
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, screen: L03WalletScreen) {
    fun state() = "可用 ${d03Yen(screen.available)}，狀態「${screen.status.ifEmpty { "無" }}」"
    log("送出前：${state()}")
    screen.tapBuy(300_000)
    val afterTapAvailable = screen.available
    val afterTapStatus = screen.status
    log("按下買入、伺服器還沒回覆：${state()}")
    log("伺服器回覆：拒絕（限價超出允許範圍），沒有凍結任何資金，伺服器上的可用餘額是 1,000,000")
    screen.orderRejected()
    log("收到拒絕之後：${state()}")
    log("最後：按下買入後顯示 ${d03Yen(afterTapAvailable)}（${afterTapStatus.ifEmpty { "無狀態" }}）；收到拒絕後顯示 ${d03Yen(screen.available)}（伺服器 1,000,000）")
}

// MARK: - Bug 版
private object L03Bug {
    class Screen : L03WalletScreen {
        override var available = 1_000_000
            private set
        override var status = ""
            private set

        override fun tapBuy(yen: Int) {
            // TODO ①-1：自己算：馬上把 available 減掉下單金額 yen（樂觀更新）
        }

        override fun orderRejected() {
            // TODO ①-2：只把 status 設成「已被拒絕」，不要動 available（Bug：忘了把餘額改回來）
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Screen())
}

// MARK: - 修正版
private object L03Fixed {
    class Screen : L03WalletScreen {
        override var available = 1_000_000                              // 只來自伺服器，App 不自己改
            private set
        override var status = ""
            private set

        override fun tapBuy(yen: Int) {
            // TODO ②-1：餘額不動（available 只來自伺服器），只把 status 設成「處理中」
        }

        override fun orderRejected() {
            // TODO ②-2：把 status 設成「已被拒絕」。餘額本來就是伺服器的，不用回復
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Screen())
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "下單被拒絕了，可用餘額卻一直少 30 萬",
    question = "第 6 題：資產的數字要怎麼算才不會出錯？",
    goal = "App 不自己用「餘額 − 下單金額」算出新餘額：送出後只顯示「處理中」，餘額一律來自伺服器。這樣下單被拒絕、部分成交、手續費捨入不同時，畫面都不會和伺服器對不上。先寫出自己扣餘額的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/design/design03-order-assets/L03_OptimisticBalance.kt，寫 TODO ①（Bug 版）。",
        "選「練習」「Bug 版」執行，看按下買入之後、收到拒絕之後，可用餘額各是多少。",
        "想一想：伺服器拒絕了，沒有凍結任何資金，為什麼畫面上的可用餘額還是少了 300,000？（誰把它改回來？）",
        "寫 TODO ②（修正版），選「修正版」執行：按下買入後餘額照舊、顯示「處理中」；拒絕之後餘額還是 1,000,000。",
        "想一想：如果真的要做樂觀更新，失敗時要怎麼回復？（要記下更新前的值，每一種失敗路徑——拒絕、逾時、被取消——都要回復；最穩的做法是不自己算。）",
    ),
    expectBug = "按下買入就顯示 700,000，被拒絕之後仍是 700,000：「最後：按下買入後顯示 700,000（無狀態）；收到拒絕後顯示 700,000（伺服器 1,000,000）」。",
    expectFixed = "按下買入顯示 1,000,000（處理中），被拒絕之後仍是 1,000,000：「最後：按下買入後顯示 1,000,000（處理中）；收到拒絕後顯示 1,000,000（伺服器 1,000,000）」。",
    file = "design03-order-assets/L03_OptimisticBalance.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, screen) 執行"
            "最後：按下買入後顯示 700,000（無狀態）；收到拒絕後顯示 700,000（伺服器 1,000,000）" !in lines ->
                "Bug 還沒重現：tapBuy 要自己 available -= yen；orderRejected 只改 status，不把 available 改回來"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, screen) 執行"
            "最後：按下買入後顯示 1,000,000（處理中）；收到拒絕後顯示 1,000,000（伺服器 1,000,000）" !in lines ->
                "tapBuy 不動 available，只把 status 設成「處理中」；orderRejected 把 status 設成「已被拒絕」"
            else -> null
        }
    },
)
