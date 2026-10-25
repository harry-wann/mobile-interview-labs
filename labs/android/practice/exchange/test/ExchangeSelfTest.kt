package tw.harry.labs.practice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.harry.labs.kit.runLabSelfTest

/** 自動檢查（練習：確認骨架都能跑完、不會當掉；還沒寫完的演練會印 FAIL，這是正常的） */
class ExchangeSelfTest {
    @Test
    fun labs() = runBlocking {
        assertEquals(emptyList<String>(), runLabSelfTest(exchangeSection, strict = false))
    }
}
