// L04：重新審查中，出金按鈕卻又亮了起來（審查狀態的更新亂序，
// 舊的回應蓋掉新的推播）
// 對應複習站：Exchange11eKYC 第 5 題「審查要數小時到數日，App
// 的狀態要怎麼設計？」
//
// 情境：審查狀態由伺服器管理，App 有兩個管道拿到它：
// 啟動與回前景時主動抓（fetch），以及審查結果的推播。
// 兩個管道的到達順序沒有保證：
//        fetch 的請求先送出，回應卻比後來才發出的推播晚到。
// 每一份狀態都帶著伺服器給的版本號 revision（越大越新），
//        內容是 status 與 canWithdraw（能不能出金，由伺服器決定，App
// 只負責顯示，不自己推論）。
//        演練依到達的順序送進 4 份狀態：
//        ① fetch（rev 3）：通過，可以出金 → 出金按鈕開啟
//        ② 推播（rev 4）：重新審查中（例如要求補件），不可出金 → 出金按鈕關閉
//        ③ 晚到的 fetch 回應（rev 3，其實是①那份舊的）→ 伺服器最新的狀態還是
// rev 4：審查中，按鈕應該維持關閉
//        ④ 推播（rev 5）：通過，可以出金 → 按鈕開啟
//
// 你要寫：
//   ① Bug 版（L04Bug.merge）：先到先蓋——直接回傳 update。
//   ② 修正版（L04Fixed.merge）：沒有目前的狀態就回傳 update；update.revision
// 比 current.revision 大才回傳 update，否則維持 current。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange11-ekyc/L04_StatusOrder.kt
package tw.harry.labs.practice.exchange11ekyc

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private data class L04AccountState(
    val revision: Int,
    // 審查狀態（顯示用）
    val status: String,
    // 能不能出金：伺服器決定
    val canWithdraw: Boolean,
)

/**
 * 演練流程（兩個版本共用）：依到達順序送進 4 份狀態；
 * 每一步和「伺服器當下最新的狀態」比對
 */
private fun scenario(
    log: LabLog,
    merge: (
        current: L04AccountState?,
        update: L04AccountState,
    ) -> L04AccountState,
) {
    val rev3 = L04AccountState(3, "通過", true)
    val rev4 = L04AccountState(4, "審查中", false)
    val rev5 = L04AccountState(5, "通過", true)
    // （描述, 到達的狀態, 伺服器當下最新的狀態）
    val steps = listOf(
        Triple("① fetch 回應 rev 3", rev3, rev3),
        Triple("② 推播 rev 4", rev4, rev4),
        Triple("③ 晚到的 fetch 回應 rev 3", rev3, rev4),
        Triple("④ 推播 rev 5", rev5, rev5),
    )
    var current: L04AccountState? = null
    var wrong = 0
    for ((name, update, truth) in steps) {
        val shown = merge(current, update)
        current = shown
        val button = if (shown.canWithdraw) "開啟" else "關閉"
        if (shown.status == truth.status &&
            shown.canWithdraw == truth.canWithdraw
        ) {
            log(
                "$name → 畫面：${shown.status}（rev ${shown.revision}），出金按鈕：$button ✓",
            )
        } else {
            wrong++
            log(
                "$name → 畫面：${shown.status}（rev ${shown.revision}），出金按鈕：$button ✗（伺服器最新是 ${truth.status}，rev ${truth.revision}）",
            )
        }
    }
    log("4 份更新，畫面和伺服器最新狀態不一致的有 $wrong 份")
}

// MARK: - Bug 版
private object L04Bug {
    fun merge(
        current: L04AccountState?,
        update: L04AccountState,
    ): L04AccountState {
        // TODO ①：直接回傳 update
        return current ?: update
    }

    suspend fun run(log: LabLog) = scenario(log) { current, update ->
        merge(current, update)
    }
}

// MARK: - 修正版
private object L04Fixed {
    fun merge(
        current: L04AccountState?,
        update: L04AccountState,
    ): L04AccountState {
        // TODO ②：current 是 null 就回傳 update；update.revision 大於 current.
        // revision 才回傳 update，否則回傳 current
        return current ?: update
    }

    suspend fun run(log: LabLog) = scenario(log) { current, update ->
        merge(current, update)
    }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("4 份更新")
}?.let { Regex("(\\d+) 份$").find(it)?.groupValues?.get(1)?.toInt() }

val l04 = Lab(
    id = "L04",
    title = "重新審查中，出金按鈕卻又亮了起來",
    question = "第 5 題：審查要數小時到數日，App 的狀態要怎麼設計？",
    goal = "審查狀態由伺服器管理，每一份狀態帶著版本號；App 只接受比目前更新的版本，晚到的舊回應不能蓋掉新的推播。能不能出金一律照伺服器給的 canWithdraw 顯示。",
    steps = listOf(
        "打開 practice/exchange/exchange11-ekyc/L04_StatusOrder.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看第 ③ 步：晚到的舊回應之後，畫面和出金按鈕是什麼？",
        "想一想：fetch 的請求是什麼時候送出的？它的回應為什麼會比後來的推播晚到？",
        "寫 TODO ②（修正版），選「修正版」執行，四份更新的畫面都要和伺服器最新的狀態一致。",
        "進階：就算按鈕被誤開，伺服器收到出金請求時仍然會依帳號狀態拒絕；但畫面顯示成能出金，使用者會以為審查通過了。App 端的旗標不能當作依據。",
    ),
    expectBug = "「③ 晚到的 fetch 回應 rev 3 → 畫面：通過（rev 3），出金按鈕：開啟 ✗（伺服器最新是 審查中，rev 4）」，其他三步是對的：「4 份更新，畫面和伺服器最新狀態不一致的有 1 份」。",
    expectFixed = "③ 的畫面維持「審查中（rev 4），出金按鈕：關閉」，④ 之後才變回通過：「4 份更新，畫面和伺服器最新狀態不一致的有 0 份」。",
    file = "exchange11-ekyc/L04_StatusOrder.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> {
                "演練沒有跑完：要用 scenario(log) { current, update -> … } 執行"
            }

            "② 推播 rev 4 → 畫面：審查中（rev 4），出金按鈕：關閉 ✓" !in lines -> {
                "Bug 版也要接受新到的狀態：收到推播 rev 4，畫面要變成審查中"
            }

            "③ 晚到的 fetch 回應 rev 3 → 畫面：通過（rev 3），出金按鈕：開啟 ✗（伺服器最新是 審查中，rev 4）" !in
                lines -> {
                "Bug 還沒重現：新收到的狀態一律蓋掉舊的，晚到的 rev 3 會把審查中蓋掉"
            }

            n != 1 -> {
                "Bug 版應該只有第 ③ 步不一致（目前 $n 份）"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { current, update -> … } 執行"
            n != 0 -> "還有 $n 份更新的畫面不對：沒有目前狀態就接受；revision 比目前大才接受，否則維持目前的"
            else -> null
        }
    },
)
