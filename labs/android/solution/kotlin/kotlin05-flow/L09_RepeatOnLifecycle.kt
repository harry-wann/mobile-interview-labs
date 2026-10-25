// L09：App 退到背景，行情還一直在收（lifecycleScope.launch { collect } 與
// repeatOnLifecycle）
// 對應複習站：Kotlin05Flow 第 7 題「怎麼讓 Flow
// 的收集跟著畫面的生命週期停啟？」
//
// 情境：行情畫面收集價格（每 50 ms 一筆）。這個演練用 FakeScreen 模擬畫面的
// lifecycle：
//        出現（STARTED）0.3 秒 → 退到背景（STOPPED）0.5 秒 → 回到前景 0.3 秒 →
// 離開（DESTROYED）。
//        要在手機上執行（repeatOnLifecycle 需要 Android 的主執行緒）。
// Bug 版：在 lifecycleScope.launch { prices.collect { } } 裡直接收集。
// 畫面退到背景後仍然持續收集，
//        要等畫面 DESTROYED 才停，背景還在更新畫面、耗電、耗流量。
// 修正版：用 repeatOnLifecycle(Lifecycle.State.STARTED) 包住收集：離開 STARTED
// 就取消，回到前景再重新開始。
//        （Compose 裡對應的是 collectAsStateWithLifecycle()）
package tw.harry.labs.solution.kotlin05flow

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的畫面：自己控制 lifecycle，可以模擬出現、退到背景、回到前景、離開 */
private class FakeScreen : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    fun move(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
}

/** 行情：每 50 ms 一筆價格，上游會一直送下去 */
private fun prices(): Flow<Int> = flow {
    var price = 0
    while (true) {
        emit(++price)
        delay(50)
    }
}

/**
 * 演練流程（兩個版本共用）：[collectIn] 決定「怎麼收集」；收到價格時，
 * 記下畫面是不是在背景
 */
private suspend fun scenario(
    log: LabLog,
    collectIn: (FakeScreen, onPrice: () -> Unit) -> Unit,
) {
    val screen = FakeScreen()
    var inBackground = 0
    screen.move(Lifecycle.Event.ON_CREATE)
    screen.move(Lifecycle.Event.ON_START)
    log("畫面出現（前景）")
    collectIn(screen) {
        if (!screen.lifecycle.currentState.isAtLeast(
                Lifecycle.State.STARTED,
            )
        ) {
            inBackground++
        }
    }
    delay(300)
    screen.move(Lifecycle.Event.ON_STOP)
    log("退到背景")
    delay(500)
    screen.move(Lifecycle.Event.ON_START)
    log("回到前景")
    delay(300)
    screen.move(Lifecycle.Event.ON_STOP)
    screen.move(Lifecycle.Event.ON_DESTROY)
    log("在背景時仍然收到 $inBackground 筆價格")
}

// MARK: - Bug 版
private object L09Bug {
    suspend fun run(log: LabLog) {
        scenario(log) { screen, onPrice ->
            screen.lifecycleScope.launch {
                // 直接收集：背景也不會停，要等 DESTROYED
                prices().collect { onPrice() }
            }
        }
    }
}

// MARK: - 修正版
private object L09Fixed {
    suspend fun run(log: LabLog) {
        scenario(log) { screen, onPrice ->
            screen.lifecycleScope.launch {
                // 離開 STARTED 就取消，回來重新收集
                screen.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    prices().collect { onPrice() }
                }
            }
        }
    }
}

// MARK: - 演練設定
private fun inBackground(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("在背景時仍然收到 ")
}?.removePrefix("在背景時仍然收到 ")?.removeSuffix(" 筆價格")?.toIntOrNull()

val l09 = Lab(
    id = "L09",
    title = "App 退到背景，行情還一直在收",
    question = "第 7 題：怎麼讓 Flow 的收集跟著畫面的生命週期停啟？",
    goal = "畫面退到背景（STOPPED）後，不該再收集行情。看 lifecycleScope.launch { collect } 為什麼停不下來，再改用 repeatOnLifecycle。",
    steps = listOf(
        "選「Bug 版」按執行，看「在背景時仍然收到 N 筆價格」的 N。",
        "選「修正版」按執行，確認背景時是 0 筆，回到前景後又重新開始收。",
        "想一想：Compose 裡的 collectAsState() 和 collectAsStateWithLifecycle() 差在哪？（前者不管 lifecycle，後者預設只在 STARTED 時收集）",
    ),
    expectBug = "「在背景時仍然收到」約 10 筆（背景 0.5 秒、每 50 ms 一筆）：退到背景後還在收。",
    expectFixed = "「在背景時仍然收到 0 筆價格」。",
    file = "kotlin05-flow/L09_RepeatOnLifecycle.kt",
    needsDevice = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L09Bug.run(log)
            Variant.FIXED -> L09Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = inBackground(lines)
        when {
            n == null -> "演練沒有跑完：要在 lifecycleScope.launch 裡收集 prices()，每收到一筆呼叫 onPrice()"
            n == 0 -> "背景時沒有收到價格：Bug 版要在 lifecycleScope.launch 裡直接 collect，不要用 repeatOnLifecycle"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = inBackground(lines)
        when {
            n == null -> "演練沒有跑完：要在 lifecycleScope.launch 裡收集 prices()，每收到一筆呼叫 onPrice()"
            n != 0 -> "背景時還收到 $n 筆價格：用 repeatOnLifecycle(Lifecycle.State.STARTED) { prices().collect { … } } 包住收集"
            else -> null
        }
    },
)
