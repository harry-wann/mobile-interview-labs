// L01：把 token 明文存進 DataStore（或 SharedPreferences），
// 備份檔一翻就看到（要用 Tink 加密後再存）
// 對應複習站：Platform04SecureStorage 第 1 題「token 要存在哪裡？為什麼不能放
// UserDefaults 或 SharedPreferences？」
//
// 情境：登入之後，App 要把 access token 存在手機上，下次開啟就不用重新登入。
// 演練檢查兩件事：
//        ① App 自己讀得回 token；② 模擬攻擊者拿到手機備份（或 root 之後能讀
// App 的資料夾）：直接讀存 token 的檔案，看看找不找得到明文 token。
//        Android 的 Keystore 只保管金鑰、不存資料，所以要先用 Tink 的
// AEAD（AES-256-GCM）把 token 加密，再存進檔案。
//        （正式的 App 用 AndroidKeysetManager，讓 Keystore 裡的 master key
// 保護 keyset；JVM 單元測試沒有 Keystore，演練改用記憶體裡的 keyset，
// 加密的做法一樣。）
// Bug 版：token 直接存進 DataStore（一個沒有加密的檔案，SharedPreferences
// 也是一樣）。檔案裡就是明文，攻擊者用任何工具打開就看到。
// 修正版：先用 aead.encrypt 加密（associatedData 綁定用途），把密文轉成 Base64
// 字串再存。檔案裡只有密文；讀回來時用 aead.decrypt。
package tw.harry.labs.solution.platform04securestorage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val tokenKey = stringPreferencesKey("accessToken")

/** 存放 token 的地方（兩個版本各自實作） */
private interface L01TokenStore {
    suspend fun save(token: String)
    suspend fun load(): String?
}

/**
 * 取得 AEAD（兩個版本共用）：正式 App 用 AndroidKeysetManager；
 * 這裡用記憶體裡的 keyset
 */
private fun newAead(): Aead {
    AeadConfig.register()
    val keyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
    return keyset.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

/**
 * 演練流程（兩個版本共用）：存 token → App 自己讀得回來嗎 → 攻擊者直接讀檔案
 */
private suspend fun scenario(
    log: LabLog,
    makeStore: (DataStore<Preferences>) -> L01TokenStore,
) {
    val dir = File(
        System.getProperty("java.io.tmpdir"),
        "labs-l01-${UUID.randomUUID()}",
    ).apply {
        mkdirs()
    }
    val file = File(dir, "tokens.preferences_pb")
    val scope = CoroutineScope(Dispatchers.IO + Job())
    try {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            file
        }
        val store = makeStore(dataStore)
        val token = "eyJ.${UUID.randomUUID()}"
        store.save(token)
        log("App 自己讀得回 token：${if (store.load() == token) "是" else "否"}")
        val leaked =
            file.exists() &&
                String(file.readBytes(), Charsets.ISO_8859_1).contains(token)
        log("攻擊者拿到備份，直接讀存 token 的檔案，看到明文 token：${if (leaked) "是" else "否"}")
    } finally {
        scope.cancel()
        dir.deleteRecursively()
    }
}

// MARK: - Bug 版
private object L01Bug {
    class Store(private val dataStore: DataStore<Preferences>) : L01TokenStore {
        override suspend fun save(token: String) {
            // 明文寫進檔案
            dataStore.edit { it[tokenKey] = token }
        }

        override suspend fun load(): String? = dataStore.data.first()[tokenKey]
    }

    suspend fun run(log: LabLog) = scenario(log) { Store(it) }
}

// MARK: - 修正版
private object L01Fixed {
    class Store(
        private val dataStore: DataStore<Preferences>,
        private val aead: Aead,
    ) : L01TokenStore {
        // 綁定用途：換成別的用途就解不開
        private val associatedData = "access_token".toByteArray()

        override suspend fun save(token: String) {
            val cipherText = aead.encrypt(token.toByteArray(), associatedData)
            // 只存密文
            dataStore.edit {
                it[tokenKey] =
                    Base64.getEncoder().encodeToString(cipherText)
            }
        }

        override suspend fun load(): String? {
            val stored = dataStore.data.first()[tokenKey] ?: return null
            return String(
                aead.decrypt(
                    Base64.getDecoder().decode(stored),
                    associatedData,
                ),
            )
        }
    }

    suspend fun run(log: LabLog) {
        val aead = newAead()
        scenario(log) { Store(it, aead) }
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "token 明文存在檔案裡，一翻就看到",
    question = "第 1 題：token 要存在哪裡？為什麼不能放 UserDefaults 或 SharedPreferences？",
    goal = "token 要先加密再存（Keystore 只保管金鑰，資料要自己加密）。App 自己讀得回來，但攻擊者直接讀檔案，找不到明文的 token。",
    steps = listOf(
        "選「Bug 版」按執行，看「攻擊者直接讀存 token 的檔案，看到明文 token」。",
        "選「修正版」執行：App 一樣讀得回 token，但檔案裡找不到明文。",
        "想一想：為什麼不用 EncryptedSharedPreferences？（已經 deprecated，新專案建議 Tink 加 DataStore）",
    ),
    expectBug = "App 讀得回 token；攻擊者直接讀檔案，看到明文 token：是。",
    expectFixed = "App 讀得回 token；攻擊者直接讀檔案，看到明文 token：否（檔案裡只有 Base64 的密文）。",
    file = "platform04-secure-storage/L01_TokenStorage.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            !lines.contains(
                "App 自己讀得回 token：是",
            ) -> "App 自己要讀得回 token：save 要把 token 存進 DataStore（tokenKey），load 要讀出來"

            !lines.contains(
                "攻擊者拿到備份，直接讀存 token 的檔案，看到明文 token：是",
            ) -> "Bug 還沒重現：Bug 版要把 token 直接存進 DataStore，不加密"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            !lines.contains(
                "App 自己讀得回 token：是",
            ) -> "App 自己要讀得回 token：save 用 aead.encrypt 加密（再轉 Base64）存，load 用 aead.decrypt 解開"

            !lines.contains(
                "攻擊者拿到備份，直接讀存 token 的檔案，看到明文 token：否",
            ) -> "檔案裡還找得到明文 token：先用 aead.encrypt 加密，只存密文"

            else -> null
        }
    },
)
