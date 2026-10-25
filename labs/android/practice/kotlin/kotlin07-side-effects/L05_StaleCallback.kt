// L05：計時器響的時候，呼叫的是舊的 callback（rememberUpdatedState）
// 對應複習站：Kotlin07SideEffects 第 3 題「長時間運作的 effect，怎麼拿到最新的
// callback？」
//
// 情境：IdleTimer 在進入畫面 400 ms 後呼叫 onTimeout。畫面進入 250 ms 時，
// 父層換了一個新的 onTimeout
//        （新的 lambda 抓到的設定版本從 1 變成 2）。計時器響的時候，
// 應該呼叫版本 2，而且不能因此重新計時。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose
// 在電腦上真的跑 Compose 的組合、重組與 effect。
//
// 你要寫：
//   ① Bug 版（L05Bug）：LaunchedEffect(Unit) { delay(400); onTimeout() }。
// effect 只啟動一次，呼叫的是第一次傳進來的舊 onTimeout。
//   ② 修正版（L05Fixed）：用 rememberUpdatedState(onTimeout)，effect
// 裡呼叫最新的 currentOnTimeout()，key 仍然是 Unit（計時不重啟）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin07-side-effects/L05_StaleCallback.kt
package tw.harry.labs.practice.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/**
 * 演練流程（兩個版本共用）：400 ms 的計時器；250 ms 時父層換了新的 onTimeout；
 * 等計時器響
 */
private suspend fun scenario(
    log: LabLog,
    timer: @Composable (onTimeout: () -> Unit) -> Unit,
) {
    var firedVersion = 0
    var firedAtMs = -1L
    val start = System.nanoTime()
    withHeadlessCompose {
        val version = mutableIntStateOf(1)
        setContent {
            val v = version.intValue
            // 每次 v 變了，就是一個新的 onTimeout
            timer {
                firedVersion = v
                firedAtMs = (System.nanoTime() - start) / 1_000_000
            }
        }
        delay(250)
        version.intValue = 2
        log("250 ms：父層換了新的 onTimeout（版本 2）")
        delay(550)
    }
    log("計時器響的時候，呼叫的是版本 $firedVersion 的 onTimeout")
    log("計時器響在進入畫面後 $firedAtMs ms")
}

// MARK: - Bug 版
private object L05Bug {
    @Composable
    fun IdleTimer(onTimeout: () -> Unit) {
        // TODO ①：LaunchedEffect(Unit) { delay(400); onTimeout() }（key 是
        // Unit：只啟動一次，呼叫的是第一次傳進來的 onTimeout）
    }

    suspend fun run(log: LabLog) = scenario(log) { onTimeout ->
        IdleTimer(onTimeout)
    }
}

// MARK: - 修正版
private object L05Fixed {
    @Composable
    fun IdleTimer(onTimeout: () -> Unit) {
        // TODO ②-1：val currentOnTimeout by rememberUpdatedState(onTimeout)
        // （永遠指向最新一次組合傳進來的值）
        // TODO ②-2：LaunchedEffect(Unit) { delay(400); 呼叫 currentOnTimeout()
        // }（key 維持 Unit，計時不會因為 onTimeout 換掉而重啟）
    }

    suspend fun run(log: LabLog) = scenario(log) { onTimeout ->
        IdleTimer(onTimeout)
    }
}

// MARK: - 演練設定
private fun firedVersion(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("計時器響的時候，呼叫的是版本 ") }
        ?.removePrefix(
            "計時器響的時候，呼叫的是版本 ",
        )?.removeSuffix(" 的 onTimeout")?.toIntOrNull()

private fun firedAtMs(lines: List<String>): Long? =
    lines.firstOrNull { it.startsWith("計時器響在進入畫面後 ") }
        ?.removePrefix("計時器響在進入畫面後 ")?.removeSuffix(" ms")?.toLongOrNull()

val l05 = Lab(
    id = "L05",
    title = "計時器響的時候，呼叫的是舊的 callback",
    question = "第 3 題：長時間運作的 effect，怎麼拿到最新的 callback？（rememberUpdatedState）",
    goal = "400 ms 的計時器執行到一半，父層換了新的 onTimeout。看 LaunchedEffect(Unit) 為什麼呼叫舊的，再用 rememberUpdatedState 拿到最新的，而且不重啟計時。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin07-side-effects/L05_StaleCallback.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「計時器響的時候，呼叫的是版本 N 的 onTimeout」的 N。",
        "寫 TODO ②（修正版），選「修正版」執行，確認呼叫的是版本 2，而且計時器沒有被重啟。",
        "想一想：直接寫 LaunchedEffect(onTimeout) 可以拿到新版本，為什麼不行？（onTimeout 一換，計時就重新等 400 ms）",
    ),
    expectBug = "「呼叫的是版本 1 的 onTimeout」：計時器用的是第一次傳進來的舊 callback。",
    expectFixed = "「呼叫的是版本 2 的 onTimeout」，而且「計時器響在進入畫面後」約 400 ms（沒有被重啟；重啟的話會是 650 ms 以後）。",
    file = "kotlin07-side-effects/L05_StaleCallback.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when (firedVersion(lines)) {
            0 -> "計時器沒有響：LaunchedEffect(Unit) 裡要 delay(400)，再呼叫 onTimeout()"
            1 -> null
            else -> "呼叫的不是舊的版本 1：Bug 版要在 LaunchedEffect(Unit) 裡直接呼叫 onTimeout()，不要用 rememberUpdatedState"
        }
    },
    checkFixed = { lines ->
        when {
            firedVersion(
                lines,
            ) == 0 -> "計時器沒有響：LaunchedEffect(Unit) 裡要 delay(400)，再呼叫 currentOnTimeout()"

            firedVersion(
                lines,
            ) != 2 -> "呼叫的還是舊的版本：用 val currentOnTimeout by rememberUpdatedState(onTimeout)，effect 裡呼叫 currentOnTimeout()"

            (
                firedAtMs(
                    lines,
                ) ?: 0L
            ) >= 640 -> "計時器被重啟了（${firedAtMs(
                lines,
            )} ms 才響）：不要把 onTimeout 當 LaunchedEffect 的 key，key 維持 Unit"

            else -> null
        }
    },
)
