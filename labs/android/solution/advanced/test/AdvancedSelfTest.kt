package tw.harry.labs.solution

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.harry.labs.kit.runLabSelfTest

/** 自動檢查（解答：每個演練的 Bug 版要重現問題、修正版要正確） */
class AdvancedSelfTest {
    @Test
    fun labs() = runBlocking {
        assertEquals(
            emptyList<String>(),
            runLabSelfTest(advancedSection, strict = true),
        )
    }
}
