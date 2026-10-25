// L02：輸錯一次卡片密碼，App 自動重試了好幾次，卡片被鎖住了（NFC 讀卡失敗要依狀態碼分類處理）
// 對應複習站：Exchange11eKYC 第 3 題「NFC 讀卡失敗時要怎麼處理？」
//
// 情境：用 NFC 讀個人編號卡之類的 IC 卡時，App 送 APDU 指令給卡片（Android 是 IsoDep.transceive），卡片回應的最後兩個位元組是狀態碼（SW1、SW2）。
//        這裡用一張假卡片，狀態碼用的是 ISO 7816-4 的通用定義：9000 成功；63Cx 密碼驗證失敗、還剩 x 次機會；6983 密碼已被鎖定。
//        假卡片的密碼是 1234（演練用，不是真的），剩餘 3 次，錯到 0 次就鎖定。（卡片的實際規格以官方資料為準，這裡只演練「依狀態碼分類」。）
//        App 的做法是：送出密碼，依狀態碼決定下一步（成功／自動重試／顯示密碼錯誤與剩餘次數／顯示已鎖定／顯示讀取失敗），整個流程最多送 5 次。
//        兩個情境：①使用者輸錯一次密碼（輸入 1243）②使用者輸入正確密碼，但卡片在中途移開了一次（第一次送出沒有收到回應，IsoDep 會丟 IOException）。
// Bug 版：不是 9000 就當成「讀取失敗」，一律自動重試。情境 ①：同一個錯誤的密碼被自動送了 5 次，卡片的剩餘次數歸零、被鎖定，
//        使用者明明只輸入了一次。
// 修正版：依狀態碼分類：沒有回應（卡片移開）可以重試；63Cx 停止並顯示「密碼錯誤，還剩 x 次」（x 是 0 就顯示已鎖定）；6983 顯示已鎖定。
package tw.harry.labs.solution.exchange11ekyc

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假卡片 */
private class L02Card(dropFirst: Int = 0) {
    private val correctPin = "1234"
    var triesLeft = 3
        private set
    var locked = false
        private set
    var sendCount = 0
        private set
    private var dropNext = dropFirst                 // 前幾次送出會因為卡片移開而沒有回應

    /** 送出密碼。回傳狀態碼；null 代表沒有收到回應（卡片移開），這種情況不會消耗剩餘次數 */
    fun verify(pin: String): Int? {
        sendCount++
        if (dropNext > 0) {
            dropNext--
            return null
        }
        if (locked) return 0x6983
        if (pin == correctPin) {
            triesLeft = 3
            return 0x9000
        }
        triesLeft--
        if (triesLeft == 0) locked = true
        return 0x63C0 or triesLeft
    }
}

private sealed interface L02Action {
    data object Done : L02Action
    data object Retry : L02Action
    data class ShowWrongPin(val left: Int) : L02Action
    data object ShowLocked : L02Action
    data object ShowFailed : L02Action
}

/** 送出密碼並依分類結果處理，最多送 5 次。回傳畫面上要顯示的訊息 */
private fun submit(pin: String, card: L02Card, classify: (Int?) -> L02Action): String {
    repeat(5) {
        when (val action = classify(card.verify(pin))) {
            L02Action.Done -> return "讀取成功"
            L02Action.Retry -> Unit
            is L02Action.ShowWrongPin -> return "密碼錯誤，還剩 ${action.left} 次"
            L02Action.ShowLocked -> return "卡片已鎖定，請到市區町村的窗口解鎖"
            L02Action.ShowFailed -> return "讀取失敗，請再試一次"
        }
    }
    return "讀取失敗，請再試一次"
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, classify: (Int?) -> L02Action) {
    val card1 = L02Card()
    val message1 = submit("1243", card1, classify)
    log("情境 1（輸錯一次密碼）：卡片收到 ${card1.sendCount} 次密碼，剩餘嘗試次數 ${card1.triesLeft}，卡片鎖定：${if (card1.locked) "是" else "否"}")
    log("情境 1 畫面顯示：$message1")

    val card2 = L02Card(dropFirst = 1)
    val message2 = submit("1234", card2, classify)
    log("情境 2（卡片中途移開一次）：卡片收到 ${card2.sendCount} 次指令，畫面顯示：$message2")
}

// MARK: - Bug 版
private object L02Bug {
    fun classify(sw: Int?): L02Action {
        return if (sw == 0x9000) L02Action.Done else L02Action.Retry      // 不是成功就當成「讀取失敗」自動重試
    }

    suspend fun run(log: LabLog) = scenario(log) { classify(it) }
}

// MARK: - 修正版
private object L02Fixed {
    fun classify(sw: Int?): L02Action {
        if (sw == null) return L02Action.Retry                            // 沒有回應：卡片移開，密碼沒送到，可以重試
        if (sw == 0x9000) return L02Action.Done
        if ((sw and 0xFFF0) == 0x63C0) {                                  // 63Cx：密碼錯誤，x 是剩餘次數
            val left = sw and 0x000F
            return if (left > 0) L02Action.ShowWrongPin(left) else L02Action.ShowLocked
        }
        if (sw == 0x6983) return L02Action.ShowLocked                     // 已鎖定：不要再送
        return L02Action.ShowFailed
    }

    suspend fun run(log: LabLog) = scenario(log) { classify(it) }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "輸錯一次密碼，卡片被自動重試鎖住",
    question = "第 3 題：NFC 讀卡失敗時要怎麼處理？",
    goal = "讀卡失敗要依狀態碼分類：卡片移開可以重試；密碼錯誤要停下來告訴使用者還剩幾次，不能自動重試；已鎖定要給明確指引。",
    steps = listOf(
        "選「Bug 版」執行，看情境 1：使用者輸入一次，卡片收到幾次密碼？剩餘次數和鎖定狀態是什麼？",
        "想一想：為什麼「卡片移開」可以放心重試，「密碼錯誤」不行？兩者的狀態碼差在哪裡？",
        "選「修正版」執行，情境 1 只送一次、顯示「密碼錯誤，還剩 2 次」，情境 2 重試一次後成功。",
        "進階：卡片密碼和 APDU 的內容都不能寫進 log，也不能送進分析工具；密碼只在記憶體裡處理。",
    ),
    expectBug = "「情境 1（輸錯一次密碼）：卡片收到 5 次密碼，剩餘嘗試次數 0，卡片鎖定：是」（同一個錯的密碼被自動送了 5 次），畫面「讀取失敗，請再試一次」；情境 2 重試一次後成功。",
    expectFixed = "「情境 1：卡片收到 1 次密碼，剩餘嘗試次數 2，卡片鎖定：否」，畫面「密碼錯誤，還剩 2 次」；「情境 2：卡片收到 2 次指令，畫面顯示：讀取成功」。",
    file = "exchange11-ekyc/L02_CardPin.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("情境 2") } -> "演練沒有跑完：要用 scenario(log) { sw -> … } 執行"
            "情境 2（卡片中途移開一次）：卡片收到 2 次指令，畫面顯示：讀取成功" !in lines -> "Bug 版的卡片移開要自動重試，第二次送出後讀取成功"
            "情境 1（輸錯一次密碼）：卡片收到 5 次密碼，剩餘嘗試次數 0，卡片鎖定：是" !in lines ->
                "Bug 還沒重現：不是 9000 就一律自動重試，同一個錯的密碼會被送 5 次，卡片被鎖定"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("情境 2") } -> "演練沒有跑完：要用 scenario(log) { sw -> … } 執行"
            "情境 2（卡片中途移開一次）：卡片收到 2 次指令，畫面顯示：讀取成功" !in lines -> "情境 2：沒有回應（null）要重試，重試後會成功"
            "情境 1（輸錯一次密碼）：卡片收到 1 次密碼，剩餘嘗試次數 2，卡片鎖定：否" !in lines ->
                "情境 1：密碼錯誤（63Cx）要停止，不能自動重試，卡片只應該收到 1 次密碼"
            "情境 1 畫面顯示：密碼錯誤，還剩 2 次" !in lines -> "情境 1 的畫面要顯示「密碼錯誤，還剩 2 次」（剩餘次數是狀態碼最後一位）"
            else -> null
        }
    },
)
