package tw.harry.labs.solution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.solution.kotlin11composestate.L08Bug
import tw.harry.labs.solution.kotlin11composestate.L08Fixed
import tw.harry.labs.solution.kotlin11composestate.l07
import tw.harry.labs.solution.kotlin11composestate.l08

/**
 * Kotlin11ComposeState 的兩個演練，要在電腦上用單元測試驗證（解答：Bug 版要重現問題、修正版要正確）。
 *
 * - L07：App 裡可以按「執行」。collectAsStateWithLifecycle 內部要用 Android 的主執行緒（Dispatchers.Main），
 *   單元測試裡沒有，所以這裡先用 Dispatchers.setMain 換一個，再執行同一段 run。
 * - L08：沒有「執行」按鈕，整個演練就是這個測試。
 *
 * 在 Android Studio 點 l07()、l08() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Kotlin11StateTest {
    @Before
    fun setMain() = Dispatchers.setMain(Dispatchers.Default)

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun l07() {
        val failures = mutableListOf<String>()
        for (variant in Variant.entries) {
            val log = LabLog(echo = false)
            runBlocking { l07.run!!(variant, log) }
            val reason = l07.check(variant)!!(log.texts)
            report(
                "L07",
                variant.title,
                reason,
                log.texts.takeLast(5).joinToString(" / "),
                failures,
            )
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun l08() {
        val failures = mutableListOf<String>()

        val bugLog = LabLog(echo = false)
        runBlocking { L08Bug.run(bugLog) }
        report(
            "L08",
            "Bug 版",
            l08.checkBug!!(bugLog.texts),
            bugLog.texts.last(),
            failures,
        )

        val fixedLog = LabLog(echo = false)
        runBlocking { L08Fixed.run(fixedLog) }
        report(
            "L08",
            "修正版",
            l08.checkFixed!!(fixedLog.texts),
            fixedLog.texts.last(),
            failures,
        )

        assertEquals(emptyList<String>(), failures)
    }

    private fun report(
        lab: String,
        variant: String,
        reason: String?,
        detail: String,
        failures: MutableList<String>,
    ) {
        if (reason == null) {
            println(
                "[SELFTEST] Kotlin11ComposeState $lab $variant PASS　$detail",
            )
        } else {
            println(
                "[SELFTEST] Kotlin11ComposeState $lab $variant FAIL：$reason",
            )
            failures += "$lab $variant：$reason"
        }
    }
}
