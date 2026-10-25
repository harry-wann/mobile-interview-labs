// L05：按了取消，畫面馬上顯示「已取消」，其實訂單已經成交了，還彈出錯誤（把取消請求當成取消完成）
// 對應複習站：Exchange08OrderFlow 第 5 題「使用者按取消的瞬間，訂單剛好成交了，怎麼處理？」
//
// 情境：一張限價買單掛在板上。就在使用者按下取消的前一刻，訂單全部成交了（伺服器上已經是 filled）。取消請求送到伺服器，
//        被拒絕，錯誤碼 `order-orderstate-error`（訂單已經是終態）；接著私有 WebSocket 通知訂單全部成交。
//        取消請求只是「送出取消的要求」，結果以伺服器為準；這是正常情況，不是 bug。
// Bug 版：按下取消，畫面馬上變成「已取消」；取消請求被拒絕時，彈出錯誤視窗「取消失敗」，狀態不更新。
//        使用者先看到「已取消」，再看到錯誤，最後才變成「已成交」。
// 修正版：按下取消，畫面顯示「取消中」，等伺服器確認；取消請求被拒絕時，不彈錯誤，改成向伺服器查詢這張單的狀態並更新畫面（已成交）。
package tw.harry.labs.solution.exchange08orderflow

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 取消的畫面。`onOrderFilled`（私有推送：訂單全部成交）已經寫好。 */
private open class L05CancelScreen(
    /** 向伺服器查詢這張單的目前狀態（回傳要顯示的文字） */
    val fetchOrder: () -> String,
) {
    var status = "委託中"
    var errorPopups = 0

    /** 使用者按下取消 */
    open fun onCancelTapped() {}

    /** 取消請求被拒絕，錯誤碼 order-orderstate-error（訂單已經是終態） */
    open fun onCancelRejected() {}

    fun onOrderFilled() {
        status = "已成交"
    }
}

/** 演練流程（兩個版本共用）：訂單先成交，使用者才按取消 */
private fun scenario(log: LabLog, screen: L05CancelScreen) {
    val seen = mutableListOf(screen.status)
    fun note(text: String) {
        seen += screen.status
        log("$text → 畫面「${screen.status}」，錯誤視窗 ${screen.errorPopups} 次")
    }
    log("訂單在伺服器上剛剛全部成交了（filled），使用者還不知道，畫面是「${screen.status}」")
    screen.onCancelTapped()
    note("使用者按下取消")
    screen.onCancelRejected()
    note("取消請求被拒絕（order-orderstate-error）")
    screen.onOrderFilled()
    note("私有推送：訂單全部成交")
    log("最後：畫面「${screen.status}」，錯誤視窗 ${screen.errorPopups} 次，使用者曾經看到「已取消」：${if ("已取消" in seen) "是" else "否"}")
}

// MARK: - Bug 版
private object L05Bug {
    class Screen : L05CancelScreen(fetchOrder = { "已成交" }) {
        override fun onCancelTapped() {
            status = "已取消"                           // 取消請求才剛送出，就顯示成已取消
        }

        override fun onCancelRejected() {
            errorPopups += 1                            // 把「訂單已經成交」當成錯誤彈出來
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Screen())
}

// MARK: - 修正版
private object L05Fixed {
    class Screen : L05CancelScreen(fetchOrder = { "已成交" }) {
        override fun onCancelTapped() {
            status = "取消中"                           // 等伺服器確認，才算取消完成
        }

        override fun onCancelRejected() {
            status = fetchOrder()                       // 訂單已經是終態：向伺服器查詢，更新成真正的狀態，不彈錯誤
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Screen())
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "按了取消顯示已取消，其實訂單早就成交了",
    question = "第 5 題：使用者按取消的瞬間，訂單剛好成交了，怎麼處理？",
    goal = "取消請求只是送出要求：畫面先顯示「取消中」，等伺服器確認；對已成交的單取消，會得到 order-orderstate-error，這是正常情況，要刷新訂單、顯示「已成交」，不彈錯誤。",
    steps = listOf(
        "選「Bug 版」執行，看每一步畫面顯示什麼、彈了幾次錯誤視窗，使用者有沒有看到「已取消」。",
        "想一想：使用者看到「已取消」，以為資金釋放了、可以下別的單，結果訂單其實已經成交，會怎樣？",
        "選「修正版」執行，按下取消顯示「取消中」，取消被拒絕後直接變成「已成交」，錯誤視窗 0 次。",
        "進階：取消期間又收到成交，累計成交量要照常更新（見上一組演練 L03 的規則）；批次取消（`batchcancel`）要逐筆更新。",
    ),
    expectBug = "按下取消就顯示「已取消」，取消被拒絕時彈出 1 次錯誤視窗：「最後：畫面「已成交」，錯誤視窗 1 次，使用者曾經看到「已取消」：是」。",
    expectFixed = "按下取消顯示「取消中」，被拒絕後變成「已成交」，沒有錯誤視窗：「最後：畫面「已成交」，錯誤視窗 0 次，使用者曾經看到「已取消」：否」。",
    file = "exchange08-order-flow/L05_CancelRace.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, screen) 執行"
            "最後：畫面「已成交」，錯誤視窗 1 次，使用者曾經看到「已取消」：是" !in lines ->
                "Bug 還沒重現：onCancelTapped 馬上把 status 設成「已取消」；onCancelRejected 讓 errorPopups 加 1（彈出錯誤）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, screen) 執行"
            "使用者按下取消 → 畫面「取消中」，錯誤視窗 0 次" !in lines -> "onCancelTapped 要把 status 設成「取消中」，等伺服器確認"
            "取消請求被拒絕（order-orderstate-error） → 畫面「已成交」，錯誤視窗 0 次" !in lines ->
                "onCancelRejected 要呼叫 fetchOrder() 更新 status（不用等私有推送，這時就要變成「已成交」），不要彈錯誤"
            "最後：畫面「已成交」，錯誤視窗 0 次，使用者曾經看到「已取消」：否" !in lines -> "整個過程使用者都不應該看到「已取消」，錯誤視窗 0 次"
            else -> null
        }
    },
)
