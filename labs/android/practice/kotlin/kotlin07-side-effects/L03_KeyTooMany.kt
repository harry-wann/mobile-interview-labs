// L03：價格一直在跳，「閒置逾時」永遠不會響（LaunchedEffect 的 key 寫太多）
// 對應複習站：Kotlin07SideEffects 第 2 題「LaunchedEffect 解決什麼問題？key 是什麼意思？」
//
// 情境：行情畫面進來 400 ms 後，要響一次逾時提醒（正式版是 5 秒，這裡縮短）。畫面同時顯示價格，價格每 60 ms 更新一次，共 12 次（約 720 ms）。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合、重組與 effect。
//
// 你要寫：
//   ① Bug 版（L03Bug）：LaunchedEffect(price) { alarm.start(); delay(400); alarm.ring() }。price 一變 key 就變，計時被取消重來。
//   ② 修正版（L03Fixed）：key 改成 Unit，計時和價格無關。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin07-side-effects/L03_KeyTooMany.kt
package tw.harry.labs.practice.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 假的提醒：記下計時開始了幾次、響了幾次 */
private class L03Alarm {
    var starts = 0
        private set
    var rings = 0
        private set

    fun start() {
        starts++
    }

    fun ring() {
        rings++
    }
}

/** 演練流程（兩個版本共用）：價格每 60 ms 更新一次，共 12 次（約 720 ms），看這段時間逾時提醒響了幾次 */
private suspend fun scenario(log: LabLog, screen: @Composable (price: Long, alarm: L03Alarm) -> Unit) {
    val alarm = L03Alarm()
    withHeadlessCompose {
        val price = mutableLongStateOf(100_000L)
        setContent { screen(price.longValue, alarm) }
        repeat(12) {
            delay(60)
            price.longValue++
        }
        log("價格更新 12 次期間，計時開始了 ${alarm.starts} 次")
        log("價格更新 12 次期間，逾時提醒響了 ${alarm.rings} 次")
    }
}

// MARK: - Bug 版
private object L03Bug {
    @Composable
    fun PriceScreen(price: Long, alarm: L03Alarm) {
        // TODO ①：用 LaunchedEffect(price) { … }：先呼叫 alarm.start()，delay(400)，再呼叫 alarm.ring()（price 當 key：價格一變計時就重來）
        HeadlessText("價格 $price")
    }

    suspend fun run(log: LabLog) = scenario(log) { price, alarm -> PriceScreen(price, alarm) }
}

// MARK: - 修正版
private object L03Fixed {
    @Composable
    fun PriceScreen(price: Long, alarm: L03Alarm) {
        // TODO ②：同樣的計時，key 換成 Unit（計時只和「進入畫面」有關）
        HeadlessText("價格 $price")
    }

    suspend fun run(log: LabLog) = scenario(log) { price, alarm -> PriceScreen(price, alarm) }
}

// MARK: - 演練設定
private fun count(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.removeSuffix(" 次")?.toIntOrNull()

private const val STARTS = "價格更新 12 次期間，計時開始了 "
private const val RINGS = "價格更新 12 次期間，逾時提醒響了 "

val l03 = Lab(
    id = "L03",
    title = "價格一直跳，逾時提醒永遠不響",
    question = "第 2 題：LaunchedEffect 解決什麼問題？key 是什麼意思？",
    goal = "進入畫面 400 ms 後要響一次逾時提醒，但價格每 60 ms 更新一次。看把 price 當 key 為什麼讓計時一直重來，再把 key 拿掉。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin07-side-effects/L03_KeyTooMany.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「計時開始了 N 次」和「逾時提醒響了 M 次」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認計時只開始 1 次、提醒響了 1 次。",
        "想一想：key 寫太多和寫太少（上一個演練）哪一個比較容易發現？（寫太少會用到舊的值；寫太多 effect 會一直重啟，兩個都要靠測試或 log 才看得出來）",
    ),
    expectBug = "「計時開始了 12 次左右」、「逾時提醒響了 0 次」：計時每 60 ms 就被取消重來。",
    expectFixed = "「計時開始了 1 次」、「逾時提醒響了 1 次」：約在進入畫面 400 ms 後響。",
    file = "kotlin07-side-effects/L03_KeyTooMany.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val starts = count(lines, STARTS) ?: 0
        val rings = count(lines, RINGS) ?: 0
        when {
            starts == 0 -> "計時沒有開始：LaunchedEffect 裡要先呼叫 alarm.start()，再 delay(400)，最後 alarm.ring()"
            rings > 0 -> "提醒響了 $rings 次：Bug 版要用 LaunchedEffect(price)，price 一變計時就被取消重來"
            starts == 1 -> "計時只開始了 1 次，沒有被價格打斷：Bug 版的 key 要寫 price"
            else -> null
        }
    },
    checkFixed = { lines ->
        val starts = count(lines, STARTS) ?: 0
        val rings = count(lines, RINGS) ?: 0
        when {
            starts == 0 -> "計時沒有開始：LaunchedEffect 裡要先呼叫 alarm.start()，再 delay(400)，最後 alarm.ring()"
            starts > 1 -> "計時被重啟了 ${starts - 1} 次：key 不要放 price，改成 Unit"
            rings != 1 -> "提醒響了 $rings 次，應該是 1 次"
            else -> null
        }
    },
)
