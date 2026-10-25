// L02：同一個測試，半夜跑就失敗（測試依賴真實的系統時間）
// 對應複習站：Testing01Strategy 第 3 題「怎麼把程式設計成好測試的樣子？」
//
// 情境：交易所每天日本時間 04:00 到 04:30 維護，這段時間不能下單。
// MaintenanceGuard.canPlaceOrder() 判斷現在能不能下單。
//       有人寫了測試「平日白天應該可以下單」，在公司下午跑都是綠燈。
//       演練用 T01SystemClock 模擬「系統時間」（程式裡直接寫 ZonedDateTime.
// now() 讀到的就是它），
//       並讓同一個測試分別跑在 10:00、04:10、23:50，代表 CI 不同時間點的執行。
//
// 你要寫：
//   ① Bug 版：canPlaceOrder() 在裡面直接讀系統時間：T01Time.
// isInMaintenance(T01SystemClock.now())，在維護時段回傳 false。
//   ② 修正版：MaintenanceGuard 的 clock（java.time.Clock）從建構子注入，
// canPlaceOrder() 用 ZonedDateTime.now(clock) 判斷，不能讀 T01SystemClock。
// 只改有 TODO 的地方。解答：solution/testing/testing01-strategy/L02_RealClock.
// kt
package tw.harry.labs.practice.testing01strategy

import java.time.Clock
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 同一個測試跑在三個不同的系統時間（已寫好） */
private suspend fun runAtDifferentTimes(
    log: LabLog,
    makeSuite: () -> T01Suite,
) {
    for ((label, time) in listOf(
        "10:00" to (10 to 0),
        "04:10" to (4 to 10),
        "23:50" to (23 to 50),
    )) {
        // 模擬：這次測試剛好跑在這個時間點
        T01SystemClock.clock = T01Time.fixedClock(time.first, time.second)
        makeSuite().run("跑在 $label", log)
    }
}

// MARK: - Bug 版
private object L02Bug {
    class MaintenanceGuard {
        fun canPlaceOrder(): Boolean {
            // TODO ①：直接讀系統時間 T01SystemClock.now()，用 T01Time.
            // isInMaintenance 判斷；維護時段不能下單
            return true
        }
    }

    /** 演練流程（不用改） */
    suspend fun run(log: LabLog) {
        runAtDifferentTimes(log) {
            val suite = T01Suite()
            suite.test("平日白天應該可以下單") { t ->
                // 沒有指定時間：跑在哪個時間就用哪個
                t.isTrue(MaintenanceGuard().canPlaceOrder(), "不能下單")
            }
            suite
        }
    }
}

// MARK: - 修正版
private object L02Fixed {
    // 時鐘從外面注入
    class MaintenanceGuard(private val clock: Clock) {
        fun canPlaceOrder(): Boolean {
            // TODO ②：用注入的 clock 取得現在時間（ZonedDateTime.now(clock)）
            // 判斷，不要讀 T01SystemClock；維護時段不能下單
            return true
        }
    }

    /** 演練流程（不用改） */
    suspend fun run(log: LabLog) {
        runAtDifferentTimes(log) {
            val suite = T01Suite()
            val cases = listOf(
                Triple("10:00 可以下單", 10 to 0, true),
                Triple("04:00 維護開始，不能下單", 4 to 0, false),
                Triple("04:29 還在維護，不能下單", 4 to 29, false),
                Triple("04:30 維護結束，可以下單", 4 to 30, true),
            )
            for ((name, time, expected) in cases) {
                suite.test(name) { t ->
                    // 測試自己指定時間
                    val guard =
                        MaintenanceGuard(
                            T01Time.fixedClock(time.first, time.second),
                        )
                    t.equal(guard.canPlaceOrder(), expected)
                }
            }
            suite
        }
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "同一個測試，半夜跑就失敗",
    question = "第 3 題：怎麼把程式設計成好測試的樣子？",
    goal = "讓測試不再依賴真實的系統時間：把時鐘注入進去，測試才能指定時間點，也驗得到維護時段的邊界。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看同一個測試跑在 10:00、04:10、23:50 的結果。",
        "想一想：04:10 失敗時，程式有壞嗎？還是測試的假設（現在一定能下單）錯了？",
        "寫 TODO ②，選「修正版」執行：三個系統時間下結果一樣，而且驗到 04:00、04:29、04:30 的邊界。",
    ),
    expectBug = "跑在 10:00 和 23:50 通過，跑在 04:10 失敗（「不能下單」）：程式沒改，結果卻跟著執行的時間變。",
    expectFixed = "三個系統時間下都是「跑了 4 個案例，通過 4、失敗 0」，邊界 04:00、04:29、04:30 都驗到了。",
    file = "testing01-strategy/L02_RealClock.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val day = T01Summary.parse(lines, "跑在 10:00")
        val night = T01Summary.parse(lines, "跑在 04:10")
        val late = T01Summary.parse(lines, "跑在 23:50")
        when {
            day == null || night == null || late == null -> "找不到三個時間點的摘要：要用 runAtDifferentTimes 跑測試"
            day.failed > 0 || late.failed > 0 -> "10:00 或 23:50 不該失敗：canPlaceOrder() 在維護時段以外要回傳 true"
            night.failed == 0 -> "跑在 04:10 沒有失敗：Bug 還沒重現（canPlaceOrder() 要直接讀 T01SystemClock.now()）"
            else -> null
        }
    },
    checkFixed = { lines ->
        var reason: String? = null
        for (label in listOf("10:00", "04:10", "23:50")) {
            val s = T01Summary.parse(lines, "跑在 $label")
            reason = when {
                s == null -> "找不到「跑在 $label」的摘要"
                s.cases != 4 -> "跑在 $label 只有 ${s.cases} 個案例：測試要驗 4 個時間點"
                s.assertions != 4 -> "跑在 $label 只檢查了 ${s.assertions} 項：每個案例要檢查一次"
                s.failed > 0 -> "跑在 $label 有 ${s.failed} 個案例失敗：要用注入的 clock，不能讀 T01SystemClock"
                else -> null
            }
            if (reason != null) break
        }
        reason
    },
)
