// L05：後端新增「已過期」訂單狀態，App 卻顯示「處理中」還讓人取消（sealed）
// 對應複習站：Kotlin01Types 第 5 題「為什麼訂單狀態要用 sealed 來寫？」
//
// 情境：後端新增訂單狀態 EXPIRED（限價單超過有效期限，已作廢）。畫面依狀態顯示文字，並決定「取消」按鈕要不要出現。
//
// 你要寫：
//   ① Bug 版（L05Bug）：狀態是 enum，when 為了「保險」寫了 else。新增的 EXPIRED 默默走進 else，顯示成「處理中」還可以取消。
//   ② 修正版（L05Fixed）：狀態改成 sealed interface，when 不寫 else，每個子型別都要處理；還不認識的狀態在解析時轉成 Unknown。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin01-types/L05_SealedState.kt
package tw.harry.labs.practice.kotlin01types

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 後端這次回傳的狀態：最後兩個是新的 */
private val statusesFromApi = listOf("NEW", "FILLED", "EXPIRED", "EXPIRED_V2")

// MARK: - Bug 版
private object L05Bug {
    enum class Status { NEW, FILLED, EXPIRED }

    fun parse(raw: String): Status = Status.entries.firstOrNull { it.name == raw } ?: Status.NEW

    fun label(status: Status): String = when (status) {
        Status.NEW -> "已送出"
        Status.FILLED -> "全部成交"
        // TODO ①-1：EXPIRED 先不要單獨處理。刪掉下一行，改寫 else -> "處理中"（為了「保險」寫的 else，EXPIRED 會默默走進這裡）
        Status.EXPIRED -> "（還沒寫）"
    }

    fun canCancel(status: Status): Boolean = when (status) {
        Status.FILLED -> false
        Status.NEW -> true
        // TODO ①-2：刪掉下一行，改寫 else -> true（EXPIRED 也會走進這裡，變成可以取消）
        Status.EXPIRED -> false
    }

    fun run(log: LabLog) {
        for (raw in statusesFromApi) {
            val status = parse(raw)
            log("$raw：${label(status)}，${if (canCancel(status)) "可以取消" else "不能取消"}")
        }
    }
}

// MARK: - 修正版
private object L05Fixed {
    sealed interface OrderState {
        data object New : OrderState
        data object Filled : OrderState
        // TODO ②-1：再加兩個子型別：data object Expired : OrderState，以及 data class Unknown(val raw: String) : OrderState
    }

    fun parse(raw: String): OrderState = when (raw) {
        "NEW" -> OrderState.New
        "FILLED" -> OrderState.Filled
        // TODO ②-2：加上 "EXPIRED" -> OrderState.Expired；其他不認識的字串，用 else 轉成 OrderState.Unknown(raw)
        else -> OrderState.New
    }

    // TODO ②-3：label 不要寫 else，每個子型別各寫一個分支：已送出、全部成交、已過期、"未知狀態（${state.raw}）"
    fun label(state: OrderState): String = when (state) {
        OrderState.New -> "已送出"
        OrderState.Filled -> "全部成交"
        else -> "（還沒寫）"
    }

    // TODO ②-4：canCancel 也不要寫 else：只有 New 可以取消，其他子型別都不行
    fun canCancel(state: OrderState): Boolean = when (state) {
        OrderState.New -> true
        else -> false
    }

    fun run(log: LabLog) {
        for (raw in statusesFromApi) {
            val state = parse(raw)
            log("$raw：${label(state)}，${if (canCancel(state)) "可以取消" else "不能取消"}")
        }
    }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "新增訂單狀態，App 默默顯示成「處理中」",
    question = "第 5 題：為什麼訂單狀態要用 sealed 來寫？",
    goal = "後端新增 EXPIRED。看 when 裡的 else 怎麼默默吞掉它，再改成 sealed 讓編譯器強迫你處理。",
    steps = listOf(
        "選「Bug 版」執行，看 EXPIRED 顯示成什麼、能不能取消。",
        "選「修正版」執行，確認 EXPIRED 與未知狀態都被正確處理。",
        "在修正版的 label() 刪掉一個分支（例如 OrderState.Expired），看編譯器怎麼說，再復原。",
    ),
    expectBug = "EXPIRED 顯示「處理中，可以取消」；EXPIRED_V2 被當成「已送出」。",
    expectFixed = "EXPIRED 顯示「已過期，不能取消」；EXPIRED_V2 顯示「未知狀態（EXPIRED_V2），不能取消」。",
    file = "kotlin01-types/L05_SealedState.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "NEW：已送出，可以取消" !in lines || "FILLED：全部成交，不能取消" !in lines -> "NEW 和 FILLED 要正常處理：已送出（可以取消）、全部成交（不能取消）"
            "EXPIRED：處理中，可以取消" !in lines -> "EXPIRED 要默默走進 else：顯示「處理中」，而且可以取消"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "NEW：已送出，可以取消" !in lines || "FILLED：全部成交，不能取消" !in lines -> "NEW 和 FILLED 要正常處理：已送出（可以取消）、全部成交（不能取消）"
            "EXPIRED：已過期，不能取消" !in lines -> "EXPIRED 要顯示「已過期」而且不能取消"
            "EXPIRED_V2：未知狀態（EXPIRED_V2），不能取消" !in lines -> "還不認識的狀態要轉成 Unknown：顯示「未知狀態（…）」而且不能取消"
            else -> null
        }
    },
)
