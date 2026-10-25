package tw.harry.labs.kit

import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自動檢查：依序跑一區裡每個演練的 Bug 版與修正版，用 checkBug／checkFixed 比對 log。
 *
 * - strict = true（解答）：Bug 版要重現問題、修正版要正確，不符合的都算失敗。
 * - strict = false（練習）：只確認骨架能跑完、不會丟例外；還沒寫完的演練印 FAIL，但不算失敗。
 *
 * 回傳失敗清單；空的代表全部通過。用 runBlocking（真實時間）呼叫，有些演練就是要量真實的時間。
 */
suspend fun runLabSelfTest(section: LabSection, strict: Boolean, timeoutMs: Long = 30_000): List<String> {
    val failures = mutableListOf<String>()
    for (unit in section.units) {
        for (lab in unit.labs) {
            val name = "${unit.code} ${lab.id}"
            val run = lab.run
            if (run == null) {
                println("[SELFTEST] $name SKIP（沒有執行按鈕：看畫面，或另有單元測試）")
                continue
            }
            if (lab.needsDevice) {
                println("[SELFTEST] $name SKIP（需要在手機上操作）")
                continue
            }
            for (variant in Variant.entries) {
                val check = lab.check(variant)
                if (check == null) {
                    println("[SELFTEST] $name ${variant.title} SKIP（沒有自動檢查）")
                    continue
                }
                if (variant == Variant.BUG && lab.bugCrashes) {
                    println("[SELFTEST] $name ${variant.title} SKIP（會當掉，要在手機上確認）")
                    continue
                }
                val log = LabLog(echo = false)
                val error = try {
                    withTimeoutOrNull(timeoutMs) { run(variant, log) } ?: log("[SELFTEST] 逾時")
                    null
                } catch (e: Throwable) {
                    e
                }
                val lines = log.texts
                if (error != null) {
                    println("[SELFTEST] $name ${variant.title} ERROR：$error")
                    failures += "$name ${variant.title}：丟出例外 $error"
                    continue
                }
                val reason = check(lines)
                if (reason == null) {
                    println("[SELFTEST] $name ${variant.title} PASS　${lines.lastOrNull() ?: ""}")
                } else {
                    println("[SELFTEST] $name ${variant.title} FAIL：$reason")
                    if (strict) {
                        println("[SELFTEST]   log：${lines.takeLast(8)}")
                        failures += "$name ${variant.title}：$reason"
                    }
                }
            }
        }
    }
    return failures
}
