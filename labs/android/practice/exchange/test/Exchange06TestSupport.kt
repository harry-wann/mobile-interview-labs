package tw.harry.labs.practice

/** Exchange06RealtimeUI 的單元測試共用：把一個版本的結果印成 [SELFTEST] 開頭的行，失敗的記進 failures */
internal fun e06Report(lab: String, variant: String, reason: String?, detail: String, failures: MutableList<String>) {
    if (reason == null) {
        println("[SELFTEST] Exchange06RealtimeUI $lab $variant PASS　$detail")
    } else {
        println("[SELFTEST] Exchange06RealtimeUI $lab $variant FAIL：$reason")
        failures += "$lab $variant：$reason"
    }
}
