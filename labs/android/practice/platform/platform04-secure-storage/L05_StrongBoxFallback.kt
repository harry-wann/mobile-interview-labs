// L05：有些手機沒有 StrongBox，App 一建立金鑰就失敗，使用者用不了（StrongBox 要處理 StrongBoxUnavailableException 並降級）
// 對應複習站：Platform04SecureStorage 第 4 題「Secure Enclave 和 StrongBox 差在哪？」
//
// 情境：App 要建立一把簽章金鑰（用來證明「這是我們註冊過的裝置」）。StrongBox 是獨立的安全晶片（Android 9、API 28 起），但不是每支手機都有。
//        要求 StrongBox 的手機沒有這個硬體時，會丟出 StrongBoxUnavailableException。
//        真的 Keystore 要在手機上才能用，所以演練用一個假的 Keystore（FakeKeystore）代替：跟真的一樣，沒有 StrongBox 的裝置在要求 StrongBox 時丟出 StrongBoxUnavailableException。
//        演練用兩支手機各跑一次：一支有 StrongBox，一支沒有。
//
// 你要寫：
//   ① Bug 版（L05Bug）：一律要求 StrongBox（useStrongBox = true），沒有 StrongBox 的手機建立失敗。
//   ② 修正版（L05Fixed）：先要求 StrongBox，接住 StrongBoxUnavailableException 就降級（useStrongBox = false）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform04-secure-storage/L05_StrongBoxFallback.kt
package tw.harry.labs.practice.platform04securestorage

import android.security.keystore.StrongBoxUnavailableException
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的 Keystore（兩個版本共用）：沒有 StrongBox 的裝置在要求 StrongBox 時丟出 StrongBoxUnavailableException */
private class L05FakeKeystore(private val hasStrongBox: Boolean) {
    /** 建立簽章金鑰，回傳金鑰存在哪裡 */
    fun createSigningKey(alias: String, useStrongBox: Boolean): String {
        if (useStrongBox && !hasStrongBox) throw StrongBoxUnavailableException("這支手機沒有 StrongBox：$alias")
        return if (useStrongBox) "StrongBox" else "TEE"
    }
}

/** 演練流程（兩個版本共用）：兩支手機各建立一次金鑰 */
private fun scenario(log: LabLog, createKey: (L05FakeKeystore) -> String) {
    for ((name, hasStrongBox) in listOf("有 StrongBox 的手機" to true, "沒有 StrongBox 的手機" to false)) {
        val result = try {
            "金鑰建立在 ${createKey(L05FakeKeystore(hasStrongBox))}"
        } catch (e: Exception) {
            "建立金鑰失敗（${e.javaClass.simpleName}），使用者無法使用 App"
        }
        log("$name：$result")
    }
}

// MARK: - Bug 版
private object L05Bug {
    fun createKey(keystore: L05FakeKeystore): String =
        "（還沒寫）"                                                         // TODO ①：回傳 keystore.createSigningKey("device_key", useStrongBox = true)：一律要求 StrongBox

    fun run(log: LabLog) = scenario(log, ::createKey)
}

// MARK: - 修正版
private object L05Fixed {
    fun createKey(keystore: L05FakeKeystore): String =
        // TODO ②：用 try 呼叫 keystore.createSigningKey("device_key", useStrongBox = true) 並回傳；
        //        接住 StrongBoxUnavailableException，改呼叫 useStrongBox = false 並回傳（降級成一般 Keystore）
        "（還沒寫）"

    fun run(log: LabLog) = scenario(log, ::createKey)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "沒有 StrongBox 的手機，建立金鑰就失敗",
    question = "第 4 題：Secure Enclave 和 StrongBox 差在哪？",
    goal = "StrongBox 不是每支手機都有。先要求 StrongBox，丟出 StrongBoxUnavailableException 就降級成一般 Keystore，不能讓沒有 StrongBox 的使用者無法使用。先寫出沒有 StrongBox 就失敗的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform04-secure-storage/L05_StrongBoxFallback.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」按執行，看「沒有 StrongBox 的手機」的結果。",
        "寫 TODO ②（修正版），選「修正版」執行：有 StrongBox 的手機用 StrongBox，沒有的降級成 TEE。",
        "想一想：iOS 的 Secure Enclave 和 StrongBox 還有什麼不一樣？（Secure Enclave 傳統上只支援 P-256、不支援 secp256k1，也不能匯入既有的金鑰；iOS 的 L05 做的就是這件事）",
    ),
    expectBug = "有 StrongBox 的手機：金鑰建立在 StrongBox；沒有 StrongBox 的手機：建立金鑰失敗（StrongBoxUnavailableException），使用者無法使用 App。",
    expectFixed = "有 StrongBox 的手機：金鑰建立在 StrongBox；沒有 StrongBox 的手機：金鑰建立在 TEE。",
    file = "platform04-secure-storage/L05_StrongBoxFallback.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            !lines.contains("有 StrongBox 的手機：金鑰建立在 StrongBox") -> "有 StrongBox 的手機要建立在 StrongBox：createSigningKey(\"device_key\", useStrongBox = true)"
            lines.contains("沒有 StrongBox 的手機：金鑰建立在 TEE") -> "Bug 還沒重現：Bug 版一律要求 StrongBox，沒有 StrongBox 的手機要失敗"
            lines.none { it.startsWith("沒有 StrongBox 的手機：建立金鑰失敗") } -> "演練沒有跑完"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            !lines.contains("有 StrongBox 的手機：金鑰建立在 StrongBox") -> "有 StrongBox 的手機還是要建立在 StrongBox：先 useStrongBox = true"
            !lines.contains("沒有 StrongBox 的手機：金鑰建立在 TEE") -> "沒有 StrongBox 的手機要降級：catch StrongBoxUnavailableException，改用 useStrongBox = false"
            else -> null
        }
    },
)
