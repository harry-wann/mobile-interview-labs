package tw.harry.labs.kit

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/**
 * 「沒有畫面的 Compose」：只用 Compose 的執行環境（runtime）執行 Composable，不畫任何東西。
 *
 * LaunchedEffect、DisposableEffect、SideEffect、remember、State、重組、跳過（skipping）都是 runtime 的功能，
 * 不需要畫面，所以可以在電腦上的單元測試裡真的跑一遍，再把「effect 做了幾次事」寫進 log 檢查。
 * （Text、Button、LazyColumn 這些要畫面的元件不能放在裡面。）
 *
 * ```
 * withHeadlessCompose {
 *     val price = mutableLongStateOf(0)
 *     setContent { Ticker(price.longValue) }     // Composable
 // 改 State，會自動觸發重組
 *     price.longValue = 1
 *     settle()                                   // 等重組做完
 * }
 * ```
 *
 * 注意：Compose 不會記住「捕獲了會被修改的 var」的 lambda（strong skipping 也一樣），
 * 這種 lambda 傳給子 Composable，子 Composable 每次都會重組。要在 lambda 裡計數，請用 AtomicInteger 或物件的屬性。
 */
class HeadlessCompose internal constructor(private val recomposer: Recomposer) {
    private val composition = Composition(UnitApplier, recomposer)

    /** 開始組合。畫面上的東西可以在 [content] 裡用 `if` 控制出現與離開 */
    fun setContent(content: @Composable () -> Unit) =
        composition.setContent(content)

    /** 等目前排隊的重組都做完，以及 effect 啟動的 coroutine 排到第一次執行 */
    suspend fun settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            recomposer.currentState.first { it == Recomposer.State.Idle }
            yield()
        }
    }

    internal fun dispose() = composition.dispose()
}

/** 不畫任何東西的 Applier：Composable 只做 runtime 的事 */
private object UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

/** 有重組要做就立刻做，不等螢幕的下一格 */
private object ImmediateFrameClock : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
        onFrame(System.nanoTime())
}

/**
 * 在 [block] 裡使用 [HeadlessCompose]。離開時會結束 composition（觸發 DisposableEffect 的 onDispose）並停掉所有 effect。
 */
suspend fun <T> withHeadlessCompose(block: suspend HeadlessCompose.() -> T): T =
    coroutineScope {
        val context = coroutineContext + ImmediateFrameClock
        val recomposer = Recomposer(context)
        val host = HeadlessCompose(recomposer)

        // State 一被寫入，就通知 Compose 去重組（App 裡這件事由
        // GlobalSnapshotManager 做，沒有畫面時要自己做）
        val writes = Channel<Unit>(Channel.CONFLATED)
        val writeObserver = Snapshot.registerGlobalWriteObserver {
            writes.trySend(Unit)
        }
        val notifier = launch {
            writes.consumeEach { Snapshot.sendApplyNotifications() }
        }
        val recompose =
            launch(ImmediateFrameClock, start = CoroutineStart.UNDISPATCHED) {
                recomposer.runRecomposeAndApplyChanges()
            }

        try {
            host.block()
        } finally {
            host.dispose()
            writeObserver.dispose()
            writes.close()
            recomposer.cancel()
            recompose.cancelAndJoin()
            notifier.cancelAndJoin()
        }
    }

/**
 * 沒有畫面時 Text 的替身：每次畫面更新完成後，把現在畫面上的文字交給 [onShow]
 */
@Composable
fun HeadlessText(text: String, onShow: (String) -> Unit = {}) {
    SideEffect { onShow(text) }
}
