import kotlinx.coroutines.runBlocking

// ── 測試 prelude（runner 自動置於使用者程式碼之前）──
// 輸出格式：每個測試一行  @@TEST|<1|0>|<名稱>|<說明>
var __testCount = 0
var __passCount = 0

fun __clean(s: String): String = s.replace("\n", " ⏎ ").replace("|", "¦")

fun __report(name: String, pass: Boolean, detail: String) {
    __testCount++
    if (pass) __passCount++
    println("@@TEST|${if (pass) 1 else 0}|${__clean(name)}|${__clean(detail)}")
    System.out.flush()
}

inline fun check(name: String, cond: () -> Boolean) {
    try {
        val ok = cond()
        __report(name, ok, if (ok) "" else "條件不成立")
    } catch (e: Throwable) {
        __report(name, false, "拋出例外：$e")
    }
}

inline fun <T> checkEqual(name: String, expected: T, actual: () -> T) {
    try {
        val a = actual()
        __report(name, a == expected, if (a == expected) "" else "預期 $expected，實際 $a")
    } catch (e: Throwable) {
        __report(name, false, "拋出例外：$e")
    }
}

inline fun checkClose(name: String, expected: Double, eps: Double, actual: () -> Double) {
    try {
        val a = actual()
        val ok = kotlin.math.abs(a - expected) <= eps
        __report(name, ok, if (ok) "" else "預期 $expected±$eps，實際 $a")
    } catch (e: Throwable) {
        __report(name, false, "拋出例外：$e")
    }
}

fun __testsDone() {
    println("@@DONE|$__passCount|$__testCount")
    System.out.flush()
}
// ── prelude 結束 ──
