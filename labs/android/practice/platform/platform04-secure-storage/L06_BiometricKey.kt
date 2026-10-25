// L06：只用 BiometricPrompt 的「成功」判斷，金鑰本身沒有綁定使用者驗證
// 對應複習站：Platform04SecureStorage 第 5 題「生物辨識怎麼「綁定」金鑰，而不是只在畫面上擋一下？」
//
// 情境：使用者要確認訂單前，要先通過指紋或臉孔。演練在 Android Keystore 裡建立一把 AES 金鑰，再用 KeyInfo 讀回「這把金鑰要不要求使用者驗證」。
//        要在手機（模擬器）上執行：要用 AndroidKeyStore；而且要求使用者驗證的金鑰，需要裝置設定螢幕鎖（PIN、圖形或密碼），模擬器要先到「設定」裡設定螢幕鎖並登記指紋。
//
// 你要寫：
//   ① Bug 版（L06Bug）：建立 AES 金鑰時不要求使用者驗證。
//   ② 修正版（L06Fixed）：KeyGenParameterSpec 加上 setUserAuthenticationRequired(true)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform04-secure-storage/L06_BiometricKey.kt
package tw.harry.labs.practice.platform04securestorage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 讀回金鑰的屬性：要不要求使用者驗證（兩個版本共用） */
private fun isUserAuthenticationRequired(alias: String): Boolean {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = keyStore.getKey(alias, null) as SecretKey
    val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    return info.isUserAuthenticationRequired
}

/** 演練流程（兩個版本共用）：建立金鑰，再讀回它的屬性 */
private fun scenario(log: LabLog, createKey: (alias: String) -> Unit) {
    val alias = "order_confirm_key"
    try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(alias)
        createKey(alias)
        log("金鑰要求使用者驗證（isUserAuthenticationRequired）：${if (isUserAuthenticationRequired(alias)) "是" else "否"}")
    } catch (e: Exception) {
        log("無法建立金鑰：${e.javaClass.simpleName}，${e.message}（要求使用者驗證的金鑰，需要先在裝置設定螢幕鎖並登記指紋）")
    }
}

// MARK: - Bug 版
private object L06Bug {
    fun createKey(alias: String) {
        // TODO ①：用 KeyGenParameterSpec.Builder(alias, 加密與解密用途) 建立規格：BLOCK_MODE_GCM、ENCRYPTION_PADDING_NONE，不要求使用者驗證，
        //        再用 KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore") 產生金鑰
    }

    fun run(log: LabLog) = scenario(log, ::createKey)
}

// MARK: - 修正版
private object L06Fixed {
    fun createKey(alias: String) {
        // TODO ②：同樣的規格（GCM、NONE padding），另外加上 setUserAuthenticationRequired(true)：每次使用前都要通過驗證，再產生金鑰
    }

    fun run(log: LabLog) = scenario(log, ::createKey)
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "只擋一下，金鑰沒有綁定使用者驗證",
    question = "第 5 題：生物辨識怎麼「綁定」金鑰，而不是只在畫面上擋一下？",
    goal = "建立金鑰時要求使用者驗證（setUserAuthenticationRequired(true)），並搭配 BiometricPrompt 的 CryptoObject，不要只靠 BiometricPrompt 的成功回傳。先寫出金鑰沒有綁定驗證的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform04-secure-storage/L06_BiometricKey.kt，寫 TODO ①（Bug 版）。",
        "先在模擬器的「設定」設好螢幕鎖（PIN）並登記指紋（Extended controls 的 Fingerprint 可以模擬）。",
        "在 App 選「練習」「Bug 版」按執行，看「金鑰要求使用者驗證」是不是「否」。",
        "寫 TODO ②（修正版），選「修正版」執行：加上 setUserAuthenticationRequired(true)，變成「是」。如果出現「無法建立金鑰」，就是還沒設定螢幕鎖。",
    ),
    expectBug = "金鑰要求使用者驗證（isUserAuthenticationRequired）：否。",
    expectFixed = "金鑰要求使用者驗證（isUserAuthenticationRequired）：是。",
    file = "platform04-secure-storage/L06_BiometricKey.kt",
    needsDevice = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.any { it.startsWith("無法建立金鑰") } -> "無法建立金鑰：${lines.first { it.startsWith("無法建立金鑰") }}"
            !lines.contains("金鑰要求使用者驗證（isUserAuthenticationRequired）：否") -> "Bug 還沒重現：Bug 版建立金鑰時不要要求使用者驗證"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.any { it.startsWith("無法建立金鑰") } -> "無法建立金鑰：${lines.first { it.startsWith("無法建立金鑰") }}"
            !lines.contains("金鑰要求使用者驗證（isUserAuthenticationRequired）：是") -> "金鑰還沒要求使用者驗證：KeyGenParameterSpec 要加 .setUserAuthenticationRequired(true)"
            else -> null
        }
    },
)
