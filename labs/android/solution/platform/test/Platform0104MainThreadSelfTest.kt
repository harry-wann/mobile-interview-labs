package tw.harry.labs.solution

import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.LabLog

/**
 * 標了 needsDevice、但其實只需要 Dispatchers.Main 的演練（Platform01–04）：
 * 用單一執行緒的假主執行緒（setMain）在 JVM 上跑，檢查 Bug 版會重現、修正版正確。
 * 真的需要 Context、系統服務的演練（下面的 [deviceOnly]）不在這裡跑，要在模擬器上確認。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Platform0104MainThreadSelfTest {
    /**
     * 模擬 Android 的主執行緒：所有工作排進同一條執行緒；immediate
     * 在主執行緒上時直接執行
     */
    private class FakeMain : MainCoroutineDispatcher() {
        private val executor = Executors.newSingleThreadExecutor {
            Thread(it, "main")
        }
        private val queue: CoroutineDispatcher = executor.asCoroutineDispatcher()
        private val mainThread: Thread = executor.submit<Thread> {
            Thread.currentThread()
        }.get()

        override val immediate: MainCoroutineDispatcher = object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this
            override fun isDispatchNeeded(context: CoroutineContext) =
                Thread.currentThread() !== mainThread
            override fun dispatch(context: CoroutineContext, block: Runnable) =
                queue.dispatch(context, block)
        }

        override fun isDispatchNeeded(context: CoroutineContext) = true
        override fun dispatch(context: CoroutineContext, block: Runnable) =
            queue.dispatch(context, block)
        fun shutdown() = executor.shutdownNow()
    }

    private val deviceOnly =
        setOf("Platform02Threading L03", "Platform04SecureStorage L06")

    @Test
    fun labs() {
        val main = FakeMain()
        Dispatchers.setMain(main)
        val failures = mutableListOf<String>()
        try {
            for (unit in platformSection.units.filter {
                it.code.substring(8, 10).toIntOrNull() in
                    1..4
            }) {
                for (lab in unit.labs) {
                    val run = lab.run ?: continue
                    val name = "${unit.code} ${lab.id}"
                    if (!lab.needsDevice || name in deviceOnly) continue
                    for (variant in Variant.entries) {
                        val check = lab.check(variant) ?: continue
                        if (variant == Variant.BUG && lab.bugCrashes) continue
                        val log = LabLog(echo = false)
                        runBlocking(Dispatchers.Main) {
                            withTimeoutOrNull(30_000) { run(variant, log) }
                                ?: log("[SELFTEST] 逾時")
                        }
                        val reason = check(log.texts)
                        if (reason == null) {
                            println(
                                "[SELFTEST] $name ${variant.title} PASS（模擬主執行緒）　${log.texts.lastOrNull() ?: ""}",
                            )
                        } else {
                            println(
                                "[SELFTEST] $name ${variant.title} FAIL（模擬主執行緒）：$reason",
                            )
                            println("[SELFTEST]   log：${log.texts.takeLast(8)}")
                            failures += "$name ${variant.title}：$reason"
                        }
                    }
                }
            }
        } finally {
            Dispatchers.resetMain()
            main.shutdown()
        }
        assertEquals(emptyList<String>(), failures)
    }
}
