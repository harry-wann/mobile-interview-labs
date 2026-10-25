package tw.harry.labs.kit

import androidx.compose.runtime.Composable

/** 每個演練都有兩個版本：會真的出錯的 Bug 版，與修正後的修正版 */
enum class Variant(val title: String) {
    BUG("Bug 版"),
    FIXED("修正版"),
}

/** 程式碼來源：practice（你寫的）或 solution（解答） */
enum class Source(val title: String) {
    PRACTICE("練習"),
    SOLUTION("解答"),
}

/**
 * 一個演練。practice 與 solution 各有一份，id 相同。
 *
 * - [run]：按「執行」時呼叫，在主執行緒（Dispatchers.Main）開始。把觀察到的事情寫進 log。
 * - [demo]：需要畫面的演練（重組次數、列表捲動、生命週期…）放在這裡。切換版本時會重建。
 * - [checkBug]／[checkFixed]：自動檢查。拿到這次執行的 log，回傳 null 代表「符合預期」，否則回傳還差什麼。
 *   練習時用來告訴你寫對了沒有；solution 的 JVM 單元測試也靠它確認解答正確。
 * - [bugCrashes]：Bug 版寫對了會讓 App 當掉。畫面會先提醒，自動檢查會略過 Bug 版。
 * - [needsDevice]：[run] 用到 Android 系統（Context、Looper、Dispatchers.Main…），JVM 單元測試跑不了，自動檢查會略過。
 * - [file]：這個演練的檔案，相對於 practice／solution 的區資料夾，例如 "kotlin03-coroutines/L01_ScopeLeak.kt"。
 */
class Lab(
    val id: String,
    val title: String,
    val question: String,
    val goal: String,
    val steps: List<String>,
    val expectBug: String,
    val expectFixed: String,
    val file: String,
    val bugCrashes: Boolean = false,
    val needsDevice: Boolean = false,
    val run: (suspend (Variant, LabLog) -> Unit)? = null,
    val demo: (@Composable (Variant, LabLog) -> Unit)? = null,
    val checkBug: ((List<String>) -> String?)? = null,
    val checkFixed: ((List<String>) -> String?)? = null,
) {
    fun check(variant: Variant) = if (variant == Variant.BUG) checkBug else checkFixed
}

/** 一個單元（對應複習站的一個單元，例如 Kotlin03Coroutines） */
class LabUnit(
    /** 複習站的單元 id，例如 kotlin-coroutines-structured */
    val id: String,
    /** 代號：編號＋單元重點，例如 Kotlin03Coroutines */
    val code: String,
    val title: String,
    val labs: List<Lab>,
)

/** 一區（一個 module），例如 Kotlin & Compose */
class LabSection(
    val id: String,
    val title: String,
    val units: List<LabUnit>,
)
