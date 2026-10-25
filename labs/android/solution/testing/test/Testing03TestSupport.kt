package tw.harry.labs.solution

/**
 * Testing03AndroidTesting 的單元測試共用：把一個版本的結果印成 [SELFTEST]
 * 開頭的行，失敗的記進 failures
 */
internal fun t03Report(
    lab: String,
    variant: String,
    reason: String?,
    detail: String,
    failures: MutableList<String>,
) {
    if (reason == null) {
        println("[SELFTEST] Testing03AndroidTesting $lab $variant PASS　$detail")
    } else {
        println("[SELFTEST] Testing03AndroidTesting $lab $variant FAIL：$reason")
        failures += "$lab $variant：$reason"
    }
}
