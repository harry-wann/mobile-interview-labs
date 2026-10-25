// L04：換新手機、還原備份之後，App 一打開就當掉（Auto Backup 還原了密文，Keystore 的金鑰卻不在）
// 對應複習站：Platform04SecureStorage 第 3 題「Android 的 Keystore 和 Tink 是什麼？Auto Backup 會造成什麼問題？」
//
// 情境：使用者在舊手機登入過，token 用 Tink 的 AEAD 加密後存成檔案。換新手機時，Android 的 Auto Backup 把 App 的檔案（密文）還原到新手機，
//        但 Keystore 裡的金鑰不會跟著備份。新手機上的 App 啟動時，要用「新的、不同的金鑰」解開舊手機的密文。
//        演練用兩把不同的 keyset 模擬舊手機與新手機（正式的 App 是 Keystore 裡的 master key 不同），用 startApp 代表「App 啟動時的第一步」。
//        （防止還原的設定寫在 AndroidManifest 的 android:dataExtractionRules，見複習站；這個演練練的是「還原了也不能當掉」。）
// Bug 版：啟動時直接 aead.decrypt。金鑰不對，Tink 丟出 GeneralSecurityException，沒有人接，App 當掉，使用者永遠打不開。
// 修正版：解密失敗（GeneralSecurityException）時，把本機的資料清掉，回到登入畫面。
package tw.harry.labs.solution.platform04securestorage

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.security.GeneralSecurityException
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val associatedData = "access_token".toByteArray()

/** 本機的檔案（兩個版本共用）：存著密文；被備份還原後，密文還在 */
private class L04LocalFile {
    var cipherText: ByteArray? = null
    fun clear() { cipherText = null }
}

private fun newAead(): Aead {
    AeadConfig.register()
    return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

/** 演練流程（兩個版本共用）：舊手機存密文 → 還原到新手機（金鑰不同）→ 啟動 App */
private fun scenario(log: LabLog, startApp: (file: L04LocalFile, aead: Aead) -> String) {
    val oldPhoneAead = newAead()
    val file = L04LocalFile()
    file.cipherText = oldPhoneAead.encrypt("access-token".toByteArray(), associatedData)      // 舊手機：登入後存的密文
    val newPhoneAead = newAead()                                                              // 新手機：Auto Backup 還原了檔案，但金鑰是新的
    try {
        val screen = startApp(file, newPhoneAead)
        log("新手機啟動 App：顯示「$screen」")
    } catch (e: Throwable) {
        log("新手機啟動 App：當掉了，${e.javaClass.simpleName}（使用者永遠打不開）")
    }
    log("本機的密文檔案：${if (file.cipherText == null) "已清除" else "還在"}")
}

// MARK: - Bug 版
private object L04Bug {
    /** App 啟動的第一步：取出 token，有的話進首頁 */
    fun startApp(file: L04LocalFile, aead: Aead): String {
        val cipherText = file.cipherText ?: return "登入畫面"
        aead.decrypt(cipherText, associatedData)             // 金鑰不對：丟出 GeneralSecurityException，沒有人接
        return "首頁"
    }

    fun run(log: LabLog) = scenario(log, ::startApp)
}

// MARK: - 修正版
private object L04Fixed {
    fun startApp(file: L04LocalFile, aead: Aead): String {
        val cipherText = file.cipherText ?: return "登入畫面"
        return try {
            aead.decrypt(cipherText, associatedData)
            "首頁"
        } catch (e: GeneralSecurityException) {
            file.clear()                                     // 金鑰不在或密文損壞：清除資料，回到登入畫面
            "登入畫面"
        }
    }

    fun run(log: LabLog) = scenario(log, ::startApp)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "換新手機還原備份，App 打不開",
    question = "第 3 題：Android 的 Keystore 和 Tink 是什麼？Auto Backup 會造成什麼問題？",
    goal = "Auto Backup 會還原密文，但 Keystore 金鑰不會跟著搬。解密失敗時要清除本機資料、回到登入畫面，不能讓 App 當掉。",
    steps = listOf(
        "選「Bug 版」按執行，看「新手機啟動 App」發生什麼事。",
        "選「修正版」執行：解密失敗之後清除密文，顯示登入畫面。",
        "想一想：還能從哪裡預防？（android:dataExtractionRules 在 cloud-backup 與 device-transfer 排除加密檔案；targetSdk 31 以上 allowBackup=false 只關雲端備份，裝置之間轉移仍會搬）",
    ),
    expectBug = "新手機啟動 App：當掉了，AEADBadTagException（使用者永遠打不開）；本機的密文檔案：還在。",
    expectFixed = "新手機啟動 App：顯示「登入畫面」；本機的密文檔案：已清除。",
    file = "platform04-secure-storage/L04_BackupRestore.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("新手機啟動 App：") } -> "演練沒有跑完"
            lines.any { it.startsWith("新手機啟動 App：當掉了") } -> null
            lines.contains("新手機啟動 App：顯示「首頁」") -> "金鑰換了卻解密成功？startApp 要用傳進來的 aead 解 file 的密文"
            else -> "Bug 還沒重現：Bug 版要直接 aead.decrypt，不要接例外"
        }
    },
    checkFixed = { lines ->
        when {
            lines.any { it.startsWith("新手機啟動 App：當掉了") } -> "App 當掉了：用 try／catch 接住 GeneralSecurityException"
            !lines.contains("新手機啟動 App：顯示「登入畫面」") -> "解密失敗之後要回到登入畫面"
            !lines.contains("本機的密文檔案：已清除") -> "解密失敗之後要清除本機的密文（file.clear()），不然每次啟動都會失敗"
            else -> null
        }
    },
)
