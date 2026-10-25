// L04：複製的入金地址一直留在剪貼簿，其他 App 隨時讀得到（剪貼簿要標示敏感、設過期）
// 對應複習站：Advanced08Security 第 6 題「怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？」（剪貼簿）
//
// 情境：入金頁有「複製地址」按鈕。複製的內容留在系統剪貼簿，任何在前景的 App 都能讀。Android 13 起，複製時系統會顯示預覽，
//       用 ClipDescription.EXTRA_IS_SENSITIVE 標記敏感內容，預覽就會隱藏；Android 沒有內建過期，要自己延遲呼叫 clearPrimaryClip。
//       （JVM 單元測試沒有 ClipboardManager，演練用一個假的剪貼簿類別代表：set 對應 setPrimaryClip，clear 對應 clearPrimaryClip，
//        每次 set 都會讓 version 加 1，對應 ClipDescription 的 timestamp。實際的 App 過期時間可以設 60 秒，演練縮短成 0.6 秒。）
// Bug 版：複製之後什麼都不管。地址永遠留在剪貼簿，直到使用者複製別的東西，而且預覽會顯示完整的地址。
// 修正版：複製時標示敏感（EXTRA_IS_SENSITIVE），並在到期後清除。但清除前要先確認剪貼簿還是自己複製的那一份（version 沒變），
//        使用者中途複製了別的東西，不能被清掉。
package tw.harry.labs.solution.advanced08security

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val ADDRESS = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
private const val EXPIRE_MS = 600L

/** 假的剪貼簿（對應 ClipboardManager）。兩個版本共用 */
private class L04Clipboard {
    @Volatile
    var text: String? = null
        private set
    @Volatile
    var sensitive = false
        private set
    private val versionCounter = AtomicLong(0)
    val version get() = versionCounter.get()

    /** setPrimaryClip。sensitive 對應 ClipDescription.EXTRA_IS_SENSITIVE。回傳這次寫入的 version */
    fun set(text: String, sensitive: Boolean = false): Long {
        this.text = text
        this.sensitive = sensitive
        return versionCounter.incrementAndGet()
    }

    /** clearPrimaryClip */
    fun clear() {
        text = null
        sensitive = false
        versionCounter.incrementAndGet()
    }
}

/** 演練流程（兩個版本共用）：copy 是「複製地址」按鈕做的事 */
private suspend fun scenario(log: LabLog, copy: (L04Clipboard, String) -> Unit) {
    val clipboard = L04Clipboard()
    fun current() = clipboard.text ?: "（空的）"

    copy(clipboard, ADDRESS)
    log("複製地址後立刻讀剪貼簿：${current()}（敏感標記：${if (clipboard.sensitive) "有" else "沒有"}）")
    delay(1_000)
    log("過了 1 秒（超過到期時間），剪貼簿裡：${current()}")

    // 另一種情況：複製地址之後，使用者很快又複製了別的東西
    clipboard.clear()
    copy(clipboard, ADDRESS)
    delay(200)
    clipboard.set("hello")
    delay(1_000)
    log("中途複製了別的東西，過了到期時間之後，剪貼簿裡：${current()}")
}

// MARK: - Bug 版
private object L04Bug {
    fun copy(clipboard: L04Clipboard, text: String) {
        clipboard.set(text)                                     // 沒有敏感標記，也沒有到期
    }

    suspend fun run(log: LabLog) = scenario(log, ::copy)
}

// MARK: - 修正版
private object L04Fixed {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun copy(clipboard: L04Clipboard, text: String) {
        val version = clipboard.set(text, sensitive = true)     // 標示敏感：複製預覽會隱藏內容
        scope.launch {
            delay(EXPIRE_MS)
            if (clipboard.version == version) clipboard.clear() // 還是自己複製的那一份才清掉
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::copy)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "複製的地址一直留在剪貼簿",
    question = "第 6 題：怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？（剪貼簿）",
    goal = "敏感內容複製到剪貼簿時，要標示敏感並設定過期；到期只清掉自己複製的那一筆，不能清掉使用者後來複製的東西。",
    steps = listOf(
        "選「Bug 版」執行，看複製後有沒有敏感標記，以及過了 1 秒之後剪貼簿還有沒有地址。",
        "想一想：直接 set 的內容會留多久？複製預覽顯示的是什麼？",
        "選「修正版」執行：標示敏感，並延遲清除；看使用者中途複製別的東西時，到期後剪貼簿還是不是他複製的內容。",
        "進階：iOS 對應的是 UIPasteboard.setItems 的 localOnly 與 expirationDate（系統自己處理過期，不會誤清）。",
    ),
    expectBug = "「敏感標記：沒有」；過了 1 秒（超過到期時間），剪貼簿裡還是完整的地址 bc1qxy2k…，沒有過期。",
    expectFixed = "「敏感標記：有」；過了 1 秒，剪貼簿是「（空的）」；使用者中途複製了別的東西，到期之後剪貼簿仍是 hello（沒有被誤清）。",
    file = "advanced08-security/L04_ClipboardLeak.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("複製地址後立刻讀剪貼簿") } -> "演練沒有跑完：要用 scenario 執行"
            "過了 1 秒（超過到期時間），剪貼簿裡：$ADDRESS" !in lines -> "Bug 還沒重現：copy 要直接 clipboard.set(text)，沒有到期"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "複製地址後立刻讀剪貼簿：$ADDRESS（敏感標記：有）" !in lines -> "複製之後剪貼簿裡應該立刻有地址，而且要標示敏感：clipboard.set(text, sensitive = true)"
            "過了 1 秒（超過到期時間），剪貼簿裡：（空的）" !in lines -> "到期後剪貼簿應該是空的：到期時間後呼叫 clipboard.clear()"
            "中途複製了別的東西，過了到期時間之後，剪貼簿裡：hello" !in lines -> "使用者中途複製的東西不能被清掉：清除前要確認 clipboard.version 還是自己 set 的那一次"
            else -> null
        }
    },
)
