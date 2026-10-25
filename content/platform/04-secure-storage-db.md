---
id: platform-secure-storage-db
title: 安全儲存與本機資料庫：Keychain、Keystore、Tink、生物辨識綁定，以及資料庫選型與 migration
summary: 交易所 App 的 token、金鑰要放在哪裡、用什麼保護、備份與重新安裝會出什麼狀況，是資安題的核心。本機資料庫則要說得出怎麼選、資料庫結構改版時怎麼不讓使用者的資料遺失。
priority: P0
day: 2
tags: [keychain, secure-enclave, keystore, strongbox, tink, room, coredata, realm, migration]
---

## 重點講解

先講一個貫穿整個單元的情境：使用者登入交易所 App 之後，App 會拿到兩種 token。access token 的有效期很短，用來呼叫 API；refresh token 的有效期較長，用來換新的 access token。為了讓使用者下次開啟 App 不必重新登入，這兩個 token 要存在手機上。放哪裡？如果手機被備份、被越獄或 root、或被別人拿到，token 會不會被偷走？另外，App 還要把訂單、聊天室這類資料存在本機資料庫；改版時資料庫的結構變了，使用者手機裡的舊資料怎麼辦？

這個單元回答七個面試常見問題：

1. token 要存在哪裡？為什麼不能放 `UserDefaults` 或 `SharedPreferences`？
2. iOS 的 Keychain 怎麼用？accessibility 怎麼選？
3. Android 的 Keystore 和 Tink 是什麼？Auto Backup 會造成什麼問題？
4. Secure Enclave 和 StrongBox 差在哪？
5. 生物辨識怎麼「綁定」金鑰，而不是只在畫面上擋一下？
6. 本機資料庫（Core Data、SwiftData、Room、Realm）怎麼選？
7. 資料庫結構改版時，migration 要怎麼做？

### 1. token 要存在哪裡？為什麼不能放 `UserDefaults` 或 `SharedPreferences`？

**token、金鑰這類秘密，要放在系統提供的安全儲存：iOS 用 Keychain，Android 用 Keystore 保管金鑰、自己加密資料後再存。** `UserDefaults`、`SharedPreferences` 只是把資料明文寫成一個檔案，不能放秘密。

`UserDefaults` 會把資料寫成 plist 檔，`SharedPreferences` 會寫成 XML 檔，兩者都是沒有加密的普通檔案。手機被越獄（iOS）或 root（Android）、被備份工具讀取、或在除錯時，這些檔案都可以直接讀出來，token 一旦外流，別人就能冒充使用者操作帳戶。

```swift
// 錯誤：token 以明文寫進 plist 檔，備份或越獄後可以直接讀出來
UserDefaults.standard.set(accessToken, forKey: "accessToken")
```

```kotlin
// 錯誤：token 以明文寫進 XML 檔
prefs.edit().putString("accessToken", accessToken).apply()
```

兩個平台的做法有一個關鍵差別：

| | iOS | Android |
|---|---|---|
| 存放秘密 | Keychain 直接存放秘密本身（token、密碼、金鑰），由系統加密保管 | Keystore **只保管金鑰**，不存你的資料；資料要由 App 用這把金鑰加密後，自己存成檔案（例如 DataStore） |
| 提供的 API | `SecItemAdd`、`SecItemCopyMatching` 等 | `KeyStore`、`KeyGenParameterSpec`；實務上多半搭配 Tink |
| 更高一層的保護 | Secure Enclave：專用晶片，私鑰不離開晶片 | StrongBox：獨立的安全晶片（不是每支手機都有） |
| 一般設定值 | `UserDefaults`（語言、主題等非敏感資料） | `SharedPreferences`／DataStore（非敏感資料） |

不管哪個平台，還有幾個基本原則：不要把 token 放在 URL 參數或 log 裡；金鑰與憑證不要寫死在程式碼或 `BuildConfig` 裡（反編譯就看得到）；登出時要清除 token、快取與資料庫。

> [!TIP]
> 面試時可以這樣說：「token 不能放 UserDefaults 或 SharedPreferences，它們是明文檔案，備份或越獄後可以直接讀出來。iOS 放 Keychain；Android 的 Keystore 只保管金鑰，要用 Tink 之類的函式庫加密資料後再存。」

### 2. iOS 的 Keychain 怎麼用？accessibility 怎麼選？

**Keychain 是 iOS 專門存放小型秘密的加密儲存區，用 accessibility 屬性決定「裝置處於什麼狀態時才能讀取」。** token 常用的設定是 `AfterFirstUnlockThisDeviceOnly`。

Keychain 的 API（`SecItemAdd`、`SecItemCopyMatching`、`SecItemUpdate`、`SecItemDelete`）比較底層，實務上常用 KeychainAccess 之類的套件包裝。選擇 accessibility 時，要看兩件事：這份資料需不需要在背景讀取、以及要不要跟著備份或同步。

| accessibility 值 | 何時可以讀 | 適合 |
|---|---|---|
| `WhenUnlocked`（沒有指定時的預設） | 裝置解鎖時 | 只在前景使用的高敏感資料 |
| `AfterFirstUnlock` | 開機後第一次解鎖，到下次重新開機之前 | **背景需要讀取的 token**（處理推播、背景更新、App Extension） |
| `WhenPasscodeSetThisDeviceOnly` | 解鎖，而且裝置有設定密碼；移除密碼時項目會被刪除 | 最敏感、要求裝置一定有密碼的資料 |
| 名稱結尾有 `ThisDeviceOnly` | 同上，但只能留在這支手機：會進加密備份，但只能還原到同一台裝置，不會搬到新手機，也不會同步到其他裝置 | 不應該離開這支手機的秘密 |

為什麼背景要讀的 token 要選 `AfterFirstUnlock`？因為背景處理推播的時候，手機可能是鎖定的。如果用 `WhenUnlocked`，鎖定時讀不到，背景流程就會失敗。實務上登入 token 常選 `AfterFirstUnlockThisDeviceOnly`：開機後解鎖一次就能讀，又不會被搬到新手機或同步到別的裝置。（`kSecAttrAccessibleAlways` 已經被棄用，不要用。）

另外三個重點：

- **`kSecAttrSynchronizable` 會透過 iCloud 鑰匙圈同步到其他裝置**，交易所的 session 或金鑰不應該開。
- **App 被刪除後，Keychain 的內容通常還在**，重新安裝後還讀得到。`UserDefaults` 則會隨 App 一起被刪除。所以要利用這個差異，在首次啟動時清掉上一次安裝殘留的 token，避免「重新安裝後直接登入」。
- 沒有指定 accessibility 時，預設是 `WhenUnlocked`，容易讓背景流程讀不到，要主動指定。

```swift
import Foundation
import Security

func saveToken(_ token: Data, account: String) -> OSStatus {
    let base: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "jp.example.app.auth",
        kSecAttrAccount as String: account
    ]
    // 先刪掉舊的，避免重複
    SecItemDelete(base as CFDictionary)
    var add = base
    add[kSecValueData as String] = token
    // 開機後解鎖一次就能讀（背景可用），不進備份、不同步
    add[kSecAttrAccessible as String] =
        kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    return SecItemAdd(add as CFDictionary, nil)
}

// 首次啟動時呼叫：
// UserDefaults 會隨 App 刪除，Keychain 不會，用它判斷是不是重新安裝
func clearKeychainIfFreshInstall() {
    let key = "hasLaunchedBefore"
    if !UserDefaults.standard.bool(forKey: key) {
        deleteAllTokens()
        UserDefaults.standard.set(true, forKey: key)
    }
}
```

> [!TIP]
> 面試時可以這樣說：「token 我會存 Keychain，accessibility 選 AfterFirstUnlockThisDeviceOnly，因為背景處理推播時手機可能是鎖著的，又不該進備份或同步。我不會開 kSecAttrSynchronizable。Keychain 在 App 刪除後還在，所以首次啟動要清掉上次安裝殘留的 token。」

### 3. Android 的 Keystore 和 Tink 是什麼？Auto Backup 會造成什麼問題？

**Android Keystore 保管金鑰，金鑰不可匯出，只能在系統內做加解密；Tink 是 Google 的加密函式庫，用它搭配 Keystore，就能安全地加密 token 再存檔。** 但 Auto Backup 會還原密文而不還原金鑰，要特別處理。

Keystore 的金鑰「進得去、拿不出來」：你只能請系統用這把金鑰加密或解密，拿不到金鑰本身，所以即使 App 的檔案被複製走，沒有 Keystore 裡的金鑰也解不開。但 Keystore 不替你存資料，直接用它要自己處理加密模式、IV（初始向量，每次加密都要用不同的隨機值）等細節，容易寫錯。

**Tink** 把這些細節包起來，提供安全的高階 API。最常用的是 **AEAD**（Authenticated Encryption with Associated Data：有驗證功能的加密）：用 AES-256-GCM 加密，同時能偵測密文有沒有被竄改。搭配 `AndroidKeysetManager` 的運作方式是：Tink 把一組加密用的金鑰（keyset）存在 SharedPreferences，這個 keyset 本身再由 Keystore 裡的 master key 加密，所以 keyset 檔案外流也沒用。`associatedData` 是額外綁定的資料（例如 cookie 的名稱），加密和解密時要給一樣的值，才能還原，避免有人把 A 用途的密文搬去 B 用途。

```kotlin
// 註冊 Tink 的 AEAD 實作
AeadConfig.register()

val keysetHandle = AndroidKeysetManager.Builder()
    // keyset 存在這個 SharedPreferences
    .withSharedPref(context, "keyset", "tink_prefs")
    // 第一次建立時，產生 AES-256-GCM 的金鑰
    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
    // 用 Keystore 的 master key 加密 keyset
    .withMasterKeyUri("android-keystore://app_master_key")
    .build()
    .keysetHandle
val aead = keysetHandle.getPrimitive(
    RegistryConfiguration.get(),
    Aead::class.java,
)

// 綁定用途
val associatedData = "refresh_token".toByteArray()
// 加密後的 bytes 再存進 DataStore 或檔案
val cipherText = aead.encrypt(plainText, associatedData)
// associatedData 不一樣就會丟出例外
val plain = aead.decrypt(cipherText, associatedData)
```

Jetpack 的 `EncryptedSharedPreferences` 已經被標為 deprecated（不建議再使用），新專案建議直接用 Tink 加 DataStore。Tink 新版以 `RegistryConfiguration` 取得 primitive，舊的 `getPrimitive(Class)` 寫法也已標為 deprecated。

**Auto Backup 的陷阱。** Android 會把 App 的 SharedPreferences、檔案備份到使用者的 Google 雲端，換新手機時還原。但 Keystore 裡的金鑰**不會**跟著備份。結果是：新手機上還原出來的是密文，金鑰卻不在，解密失敗，App 一啟動就 crash 或登入失敗。對策有三個：

1. `targetSdk` 31（Android 12）以上，用 `android:dataExtractionRules` 同時在 `<cloud-backup>` 和 `<device-transfer>` 排除加密檔案；舊版用 `android:fullBackupContent`。注意：`targetSdk` 31 以上時，`android:allowBackup="false"` 只會關掉雲端備份，換手機時的裝置之間轉移仍然會把檔案搬過去。
2. 解密失敗時要能處理：清除本機資料，要求重新登入，不要讓 App crash。
3. 不要把 token 放在明文 SharedPreferences、一般資料庫或 log 裡。

```xml
<!-- res/xml/data_extraction_rules.xml：不要把加密檔案備份到雲端或搬到新手機 -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="tink_prefs.xml" />
        <exclude domain="file" path="datastore/" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="tink_prefs.xml" />
        <exclude domain="file" path="datastore/" />
    </device-transfer>
</data-extraction-rules>
```

```kotlin
val plain = try {
    aead.decrypt(cipherText, associatedData)
} catch (e: GeneralSecurityException) {
    // 金鑰不在或密文損壞：清除資料，回到登入畫面
    clearLocalDataAndRequireLogin()
    null
}
```

另外，Android 解除安裝 App 時，Keystore 裡的金鑰會一起被刪除；這和 iOS 的 Keychain 相反。

> [!TIP]
> 面試時可以這樣說：「Android 的 Keystore 只保管金鑰，所以我用 Tink 的 AEAD，AES-256-GCM 加密 token 再存 DataStore，keyset 由 Keystore 的 master key 保護。要注意 Auto Backup 會還原密文但不會還原 Keystore 金鑰，所以要排除備份，並在解密失敗時清除資料、要求重新登入。」

### 4. Secure Enclave 和 StrongBox 差在哪？

**兩者都是「硬體保護的金鑰」：私鑰產生在專用的安全硬體裡，永遠不離開，App 只能請硬體幫忙簽章。差別在支援的演算法和裝置範圍。**

| | iOS：Secure Enclave | Android：TEE／StrongBox |
|---|---|---|
| 是什麼 | 蘋果裝置內的專用安全處理器 | 預設是 TEE（Trusted Execution Environment，主處理器裡與一般系統隔離的安全區域）；StrongBox（Android 9，API 28 起）是獨立的安全晶片 |
| 私鑰能不能取出 | 不能，只能請它簽章 | 不能 |
| 能不能匯入既有的金鑰 | **不能**，只能在裡面產生 | 可以匯入，但在硬體裡產生，才能確保金鑰從來沒有在外面出現過 |
| 支援的演算法 | 傳統上簽章與金鑰協商只支援 P-256，**不支援交易所與區塊鏈常用的 secp256k1** | StrongBox 支援的演算法比 TEE 少，速度也比較慢 |
| 不是每支裝置都有 | 較新的 iPhone 都有；模擬器沒有 | StrongBox 不是每支手機都有，要處理 `StrongBoxUnavailableException`，失敗時降級回 TEE |

這帶來一個實務結論：**Secure Enclave 不能直接拿來簽比特幣或以太坊的交易**，因為這些區塊鏈用 secp256k1，Secure Enclave 不支援，而且也不能把別處產生的私鑰匯進去。它適合的用途是：產生一把「綁定這支手機」的 P-256 金鑰，用來證明「這是我們註冊過的裝置」，或對伺服器送來的挑戰碼（challenge）簽章。

```swift
import CryptoKit

// iOS：在 Secure Enclave 內產生 P-256 簽章金鑰，私鑰不會出來
// 模擬器或舊裝置沒有，要有降級方案
guard SecureEnclave.isAvailable else { return }
let key = try SecureEnclave.P256.Signing.PrivateKey()
// 簽章在晶片內完成
let signature = try key.signature(for: challenge)
// 公鑰傳給伺服器，之後用它驗證簽章
let publicKey = key.publicKey
```

```kotlin
// Android：優先用 StrongBox，沒有的話降級成一般 Keystore（TEE）
fun createSigningKey(alias: String, useStrongBox: Boolean) {
    val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        // 即 P-256
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
    if (useStrongBox) {
        builder.setIsStrongBoxBacked(true)
    }
    KeyPairGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        .apply { initialize(builder.build()) }
        .generateKeyPair()
}

fun createSigningKeySafely(alias: String) {
    try {
        createSigningKey(alias, useStrongBox = true)
    } catch (e: StrongBoxUnavailableException) {
        // 這支手機沒有 StrongBox
        createSigningKey(alias, useStrongBox = false)
    }
}
```

> [!TIP]
> 面試時可以這樣說：「Secure Enclave 和 StrongBox 都是讓私鑰留在硬體裡，只能請晶片簽章。Secure Enclave 傳統上只支援 P-256，不支援 secp256k1，也不能匯入金鑰，所以不能直接簽區塊鏈交易；StrongBox 不是每支 Android 手機都有，要處理 StrongBoxUnavailableException 並降級到一般 Keystore。」

### 5. 生物辨識怎麼「綁定」金鑰，而不是只在畫面上擋一下？

**只呼叫 `LAContext.evaluatePolicy` 或 `BiometricPrompt` 得到「通過」，只是 App 自己判斷的閘門，被攻破的環境可以繞過；真正的綁定，是讓金鑰或 Keychain 項目本身要求生物辨識，由系統在讀取時強制驗證。**

想像一種寫法：「`evaluatePolicy` 回傳成功，就從 Keychain 取出 token 顯示」。問題是，取出 token 這件事並沒有要求生物辨識，攻擊者只要在被修改過的環境裡跳過那個判斷，直接呼叫讀取 token 的程式，就拿得到。正確做法是讓**資料本身綁定生物辨識**，沒有通過驗證，系統根本不給你資料。

**iOS：用 `SecAccessControl` 綁定 Keychain 項目。** `.biometryCurrentSet` 的意思是「只認目前登記的這組生物特徵」，如果使用者之後新增了指紋或臉孔，這個項目就會失效。

```swift
import LocalAuthentication
import Security

func saveWithBiometry(_ secret: Data, account: String) -> OSStatus {
    var error: Unmanaged<CFError>?
    guard let access = SecAccessControlCreateWithFlags(
        nil,
        // 一定要有裝置密碼，且不進備份
        kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
        // 要求生物辨識；新增指紋或臉孔後會失效
        .biometryCurrentSet,
        &error
    ) else { return errSecParam }

    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrAccount as String: account,
        kSecValueData as String: secret,
        kSecAttrAccessControl as String: access
    ]
    return SecItemAdd(query as CFDictionary, nil)
}

func readWithBiometry(account: String) -> Data? {
    let context = LAContext()
    // 系統驗證畫面上顯示的說明
    context.localizedReason = "確認身分以查看資產"
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrAccount as String: account,
        kSecReturnData as String: true,
        kSecUseAuthenticationContext as String: context
    ]
    var item: CFTypeRef?
    // 這一行會跳出 Face ID／Touch ID；沒通過，系統就不回傳資料
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    return status == errSecSuccess ? (item as? Data) : nil
}
```

這個讀取會等使用者操作生物辨識，所以不要在主執行緒呼叫。另外，使用 Face ID 要在 Info.plist 加上 `NSFaceIDUsageDescription`。

**Android：金鑰要求使用者驗證，並用 `BiometricPrompt` 搭配 `CryptoObject`。** 建立金鑰時設定 `setUserAuthenticationRequired(true)`，這把金鑰每次使用前都要通過驗證。然後把用這把金鑰初始化好的 `Cipher` 包成 `CryptoObject` 交給 `BiometricPrompt`：驗證通過，這個 `Cipher` 才能真的使用。

```kotlin
// 建立一把「要求使用者驗證」的金鑰（只需要建立一次）
val spec = KeyGenParameterSpec.Builder(
    "order_confirm_key",
    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    // API 30 起可用 setUserAuthenticationParameters 指定驗證方式與有效時間
    .setUserAuthenticationRequired(true)
    .build()
KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    .apply { init(spec) }
    .generateKey()

// 使用時：把 Cipher 交給 BiometricPrompt，通過驗證才能用
val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
val key = keyStore.getKey("order_confirm_key", null) as SecretKey
val cipher = Cipher.getInstance("AES/GCM/NoPadding")
try {
    cipher.init(Cipher.ENCRYPT_MODE, key)
} catch (e: KeyPermanentlyInvalidatedException) {
    // 使用者新增了指紋或臉孔：金鑰已經失效，要刪掉重建，並要求重新登入
}
biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
```

兩個平台的行為一致：**新增生物特徵之後，原本綁定的金鑰預設會失效**（iOS 是 `.biometryCurrentSet`，Android 是 `KeyPermanentlyInvalidatedException`）。這是刻意的安全設計：如果有人用你的手機新增了自己的指紋，就不能用來開啟你原本綁定的資料。所以 App 必須準備好「重新登入、重新綁定」的流程。

> [!TIP]
> 面試時可以這樣說：「只靠 evaluatePolicy 或 BiometricPrompt 的成功回傳，是 App 自己判斷的閘門，可以被繞過。我會讓資料本身綁定生物辨識：iOS 把 Keychain 項目綁在 SecAccessControl 的 biometryCurrentSet，Android 金鑰設 setUserAuthenticationRequired，搭配 BiometricPrompt 的 CryptoObject。新增生物特徵後金鑰會失效，要有重新登入的流程。」

### 6. 本機資料庫（Core Data、SwiftData、Room、Realm）怎麼選？

**選型看四件事：App 支援的最低系統版本、需不需要跨平台共用、是否需要資料變動時自動更新畫面、以及長期維護的風險。** 交易所 App 的本機資料多半是快取，不是資料的來源。

| | iOS | Android |
|---|---|---|
| 系統內建／官方 | **Core Data**：Apple 的物件持久化框架，底層預設是 SQLite。**SwiftData**：iOS 17 起提供的新框架，用 Swift 巨集宣告模型，底層也是 Core Data | **Room**：Google 官方的 SQLite 封裝，查詢語法在編譯時就會檢查，搭配 Kotlin Flow 在資料變動時自動重新發出結果 |
| 第三方 | **GRDB**：直接包裝 SQLite 的 Swift 函式庫 | **SQLDelight**：從 SQL 檔案產生 Kotlin API，支援 Kotlin Multiplatform（KMP） |
| 兩個平台都有 | **Realm**：物件資料庫，有自己的儲存引擎，模型是類別，查詢到的物件會即時反映資料的變動 | **Realm**：與 iOS 相同的產品與概念 |

各選項的重點：

- **Core Data／SwiftData**：Apple 官方支援，與 SwiftUI 整合好。SwiftData 需要 iOS 17，如果 App 最低支援 iOS 15（既有的 App 常得支援到這麼舊的版本），就不能用。
- **Room**：Android 官方推薦，穩定，與 Flow、Paging 整合良好。
- **Realm**：兩個平台用同一套概念，模型簡單；但它的物件**只能在建立它的執行緒使用**（thread-confined），跨執行緒要先複製成獨立的資料。MongoDB 在 2024 年宣布 Atlas Device Sync 與 Realm SDK 進入棄用方向，新專案不建議把它當長期選項（細節以官方最新公告為準）。
- **SQLDelight／GRDB**：想直接寫 SQL、或要與 Kotlin Multiplatform 共用資料層時考慮。

因為 Realm 物件不能跨執行緒，常見做法是把資料庫存取包起來，對外只傳「複製出來、獨立的資料」：

```swift
// iOS：把 Realm 存取包在 actor 裡，對外只回傳獨立的 struct，不傳 Realm 物件
actor OrderStore {
    func orders() throws -> [OrderModel] {
        let realm = try Realm()
        // 轉成獨立的 struct
        return realm.objects(OrderObject.self).map { OrderModel($0) }
    }
}
```

```kotlin
// Android：Room 的 DAO 回傳 Flow，資料變動時畫面自動更新
@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    // 金額用字串或整數存，不用 Double，避免精度誤差
    val price: String,
    val status: String
)

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders ORDER BY id DESC")
    fun observeAll(): Flow<List<OrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(order: OrderEntity)
}
```

資料庫的內容也要想清楚：**交易所的帳務真相在伺服器，本機只是快取**。快取要有版本與失效策略，登出時要清除。如果資料庫裡有敏感資料，要加密：iOS 可以用檔案保護屬性（Data Protection），兩個平台都可以用 SQLCipher 或 Realm 內建的加密；**加密用的金鑰要放在 Keychain／Keystore，不能寫死在程式碼裡**。

> [!TIP]
> 面試時可以這樣說：「選型我會看最低支援版本、需不需要跨平台、要不要資料變動時自動更新畫面。iOS 用 Core Data，SwiftData 要 iOS 17；Android 用 Room 搭配 Flow。Realm 兩平台都能用，但物件是 thread-confined，要把存取包起來、對外只傳複製出來的資料，而且它的長期支援有疑慮。本機資料庫只是快取，真相在伺服器。」

### 7. 資料庫結構改版時，migration 要怎麼做？

**migration（資料庫遷移）是 App 更新後，把使用者手機上舊版結構的資料，轉換成新版結構。最重要的原則是：永遠要有明確的 migration 路徑，並且測試過，不要靠「刪掉重建」。**

情境：1.0 版的 `orders` 資料表沒有 `memo` 欄位，1.1 版新增了。使用者更新 App 後，手機裡還有 1.0 版的資料庫檔案。如果沒有 migration，App 打開資料庫時發現結構對不上，輕則 crash，重則（被設定成「找不到 migration 就刪除重建」時）使用者的本機資料全部消失。

| | 做法 | 危險做法 |
|---|---|---|
| Core Data | lightweight migration：系統自動推斷新舊模型的對應，適合新增或刪除屬性、重新命名（需設定 renaming identifier）；型別轉換或資料重組要自己做 mapping model，或分段 migration | 偵測到不相容就刪除 store 重建 |
| Room | `@Database(autoMigrations = [AutoMigration(from, to)], exportSchema = true)` 處理簡單變更；複雜的寫手動 `Migration`；用 `MigrationTestHelper` 測試 | `fallbackToDestructiveMigration()`：找不到 migration 時**清空整個資料庫** |
| Realm | 改 schema 時，提高 `schemaVersion`，並在 `migrationBlock` 寫轉換邏輯 | 沒有提高 `schemaVersion` 就改模型，會在開啟時丟出錯誤 |
| SQLDelight | 寫 `.sqm` 檔記錄每個版本的變更，並用 `verifyMigrations` 檢查 | — |

Room 的 `AutoMigration` 需要 `exportSchema = true`，這會在編譯時把每個版本的結構輸出成 JSON 檔，Room 比對兩個版本的 JSON 就能自動產生 migration，這些 JSON 檔也是測試 migration 的依據。重新命名或刪除欄位等 Room 無法自動判斷的變更，要提供 `AutoMigrationSpec`。

```kotlin
// 簡單變更：自動 migration
@Database(
    entities = [OrderEntity::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 2, to = 3)],
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase()

// 複雜變更：手寫 Migration
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE orders ADD COLUMN memo TEXT NOT NULL DEFAULT ''",
        )
    }
}

Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .addMigrations(MIGRATION_1_2)
    // 不要加 .fallbackToDestructiveMigration()：
    // 找不到 migration 時會刪光使用者的資料
    .build()
```

```swift
// Realm：改 schema 就要提高 schemaVersion，並寫 migration
let config = Realm.Configuration(
    schemaVersion: 18,
    migrationBlock: { migration, oldSchemaVersion in
        if oldSchemaVersion < 18 {
            migration.enumerateObjects(
                ofType: ChatRoomObject.className()
            ) { _, newObject in
                // 新欄位給預設值
                newObject?["isPinned"] = false
            }
        }
    }
)
```

`fallbackToDestructiveMigration()` 並不是絕對不能用：如果資料庫裡只是可以從伺服器重新抓回來的快取，刪掉重建可以接受；但只要有使用者設定、草稿、尚未同步的資料，就是資料遺失。

最後別忘了**測試**：用 `MigrationTestHelper`（Room）建立舊版本的資料庫、寫入資料，再執行 migration，確認資料正確。這樣才能確保真實使用者從舊版更新上來時不會出事。

> [!TIP]
> 面試時可以這樣說：「migration 我一定會準備明確的路徑，Room 簡單變更用 AutoMigration，複雜的手寫 Migration，並且用 MigrationTestHelper 測試。我不會在正式版加 fallbackToDestructiveMigration，因為找不到 migration 時會清空使用者資料。Realm 改 schema 要提高 schemaVersion 並寫 migration block。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R；Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶。App 首頁進入 **Platform04SecureStorage**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Platform/Platform04-SecureStorage/`，解答在 `labs/ios/Solution/Platform/Platform04-SecureStorage/`；Android 你寫在 `labs/android/practice/platform/platform04-secure-storage/`，解答在 `labs/android/solution/platform/platform04-secure-storage/`（Practice／Solution 同名，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **這個單元的演練怎麼做出來的**：iOS 的 Keychain、Core Data 都是真的（在模擬器上跑）；Android 的 Tink、DataStore 也是真的（在 JVM 單元測試裡跑，Keystore 要用的演練 L06 要在手機上執行）。Android 的 Room 需要編譯器外掛，沒有放進專案，所以第 7 題的 Android 版用一個幾十行的「迷你資料庫」代替，規則和 Room 一樣。第 2、3 題只有一個平台，第 6 題只有 iOS，其他題兩個平台都有。

### 演練 1：token 明文存在檔案裡，一翻就看到（第 1 題）

**檔案**：iOS `L01_TokenStorage.swift`、Android `L01_TokenStorage.kt`

**情境**：登入之後，App 把 access token 存在手機上。演練檢查：App 自己讀得回 token；攻擊者（拿到手機備份，或越獄、root 之後能讀 App 的資料夾）看不看得到明文 token。iOS 翻 `UserDefaults`，再掃描 App 資料夾所有檔案；Android 直接讀 DataStore 的檔案。

**怎麼寫**

1. **Bug 版**：iOS 用 `defaults.set(token, forKey:)` 存進 `UserDefaults`；Android 直接 `dataStore.edit { it[tokenKey] = token }`。都是沒有加密的明文。
2. **修正版**：iOS 存進 Keychain（`SecItemAdd` 存、`SecItemCopyMatching` 加 `kSecReturnData` 讀，accessibility 用 `AfterFirstUnlockThisDeviceOnly`）。Android 先用 Tink 的 AEAD 加密（`aead.encrypt(token.toByteArray(), associatedData)`），轉成 Base64 再存；讀回來時 `aead.decrypt`。Android 的 Keystore 只保管金鑰，所以資料要自己加密；正式的 App 用 `AndroidKeysetManager`，演練改用記憶體裡的 keyset（JVM 單元測試沒有 Keystore），加密的做法一樣。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | App 讀得回 token；iOS：攻擊者翻 UserDefaults 看到明文 token（稍等幾秒，掃描資料夾通常會找到 `Library/Preferences` 底下的 plist）；Android：攻擊者讀檔案看到明文 token |
| 修正版 | App 讀得回 token；iOS：翻 UserDefaults 是「否」，掃描資料夾找到 0 個檔案；Android：讀檔案看到明文 token：否（檔案裡只有 Base64 的密文） |

**為什麼**：`UserDefaults` 與 `SharedPreferences`／DataStore 都是沒有加密的普通檔案，備份、越獄或 root、除錯時可以直接讀出來，token 一外流別人就能冒充使用者。iOS 的 Keychain 由系統加密保管，不在 App 的資料夾裡；Android 的 Keystore 保管金鑰，資料用金鑰加密後再存。Keychain 也不是絕對安全，所以 token 要短效、伺服器端可以註銷，敏感操作要再次驗證。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L01_TokenStorage.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L01_TokenStorage.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform04-secure-storage/L01_TokenStorage.kt#bug
```

```lab-solution
android/solution/platform/platform04-secure-storage/L01_TokenStorage.kt#fixed
```

### 演練 2：鎖定時讀不到 token，還同步到別的裝置（第 2 題，只有 iOS）

**檔案**：iOS `L02_KeychainAccessible.swift`

**情境**：登入後把 refresh token 存進 Keychain。要求：App 在背景處理推播時（手機可能鎖定）也要讀得到；不能進 iCloud 鑰匙圈同步到其他裝置。演練存好之後，用 `SecItemCopyMatching` 加 `kSecReturnAttributes` 讀回這個項目被設成什麼。

**怎麼寫**

1. **Bug 版**：項目沒有指定 `kSecAttrAccessible`（預設是 `WhenUnlocked`），而且加上 `kSecAttrSynchronizable: true`。
2. **修正版**：`kSecAttrAccessible` 指定 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，不開同步。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | accessibility：WhenUnlocked；手機鎖定時讀不到；會同步到其他裝置：是 |
| 修正版 | accessibility：AfterFirstUnlockThisDeviceOnly；手機鎖定時讀得到；會同步到其他裝置：否 |

**為什麼**：背景處理推播時手機可能是鎖著的。`WhenUnlocked` 鎖定時讀不到，背景流程就會失敗；`AfterFirstUnlock` 開機後解鎖一次就能讀。結尾是 `ThisDeviceOnly` 的設定不會搬到新手機、不會同步到其他裝置。`kSecAttrSynchronizable` 會透過 iCloud 鑰匙圈同步，交易所的 session 或金鑰不應該開。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L02_KeychainAccessible.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L02_KeychainAccessible.swift#fixed
```

### 演練 3：重新安裝後，不用登入就進去了（第 2 題，只有 iOS）

**檔案**：iOS `L03_ReinstallLeftover.swift`

**情境**：使用者登入後刪除 App，隔天又重新安裝。`UserDefaults` 會跟著 App 一起被刪除，但 Keychain 的內容通常還在。演練用一個專屬的 `UserDefaults` 代表「這一次安裝的資料」，Keychain 裡先放一份「上一次安裝留下的 token」，依序模擬：重新安裝後第一次啟動 → 使用者登入 → 第二次啟動。

**怎麼寫**

1. **Bug 版**：啟動時只看 Keychain 有沒有 token，有就當作已登入。
2. **修正版**：啟動時檢查 `UserDefaults` 有沒有 `hasLaunchedBefore`。沒有就是重新安裝後的第一次啟動：先把 Keychain 裡殘留的 token 刪掉，再把旗標設成 `true`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 重新安裝後第一次啟動：直接登入了（用的是上一次安裝留下的 token）；登入之後第二次啟動：維持登入 |
| 修正版 | 重新安裝後第一次啟動：要重新登入；登入之後第二次啟動：維持登入 |

**為什麼**：`UserDefaults` 隨 App 刪除，Keychain 不會，兩者的差異剛好可以用來判斷是不是重新安裝。沒有這個處理，重新安裝後會「直接登入」，把上一次安裝的 session 帶進來。Android 剛好相反：解除安裝時 Keystore 的金鑰會一起被刪除。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L03_ReinstallLeftover.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L03_ReinstallLeftover.swift#fixed
```

### 演練 4：換新手機還原備份，App 打不開（第 3 題，只有 Android）

**檔案**：Android `L04_BackupRestore.kt`

**情境**：使用者在舊手機登入過，token 用 Tink 的 AEAD 加密後存成檔案。換新手機時，Auto Backup 把 App 的檔案（密文）還原到新手機，但 Keystore 裡的金鑰不會跟著備份。演練用兩把不同的 keyset 模擬舊手機與新手機（正式的 App 是 Keystore 裡的 master key 不同），`startApp` 代表 App 啟動時的第一步。

**怎麼寫**

1. **Bug 版**：啟動時直接呼叫 `aead.decrypt(cipherText, associatedData)`，沒有人接例外。
2. **修正版**：用 `try`／`catch (e: GeneralSecurityException)` 接住：把本機的密文檔案清掉（`file.clear()`），回到登入畫面。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 新手機啟動 App：當掉了，`AEADBadTagException`（使用者永遠打不開）；本機的密文檔案：還在 |
| 修正版 | 新手機啟動 App：顯示「登入畫面」；本機的密文檔案：已清除 |

**怎麼預防**：在 `AndroidManifest.xml` 用 `android:dataExtractionRules`（`targetSdk` 31 以上），同時在 `<cloud-backup>` 與 `<device-transfer>` 排除加密檔案（見重點講解的 XML）。`targetSdk` 31 以上時，`allowBackup="false"` 只會關掉雲端備份，換手機時的裝置之間轉移仍然會把檔案搬過去。

**為什麼**：Auto Backup 還原的是密文，Keystore 裡的金鑰不會跟著搬，所以新手機上解不開。這個演練練的是最後一道防線：就算備份被還原了，解密失敗也要能清除資料、回到登入，不能讓 App 當掉。

**Android 解答**

```lab-solution
android/solution/platform/platform04-secure-storage/L04_BackupRestore.kt#bug
```

```lab-solution
android/solution/platform/platform04-secure-storage/L04_BackupRestore.kt#fixed
```

### 演練 5：Secure Enclave 與 StrongBox 的限制（第 4 題）

**檔案**：iOS `L05_SecureEnclaveImport.swift`、Android `L05_StrongBoxFallback.kt`

**情境**：iOS：App 原本把一把 P-256 簽章金鑰存在軟體裡，想「搬進」Secure Enclave。Android：App 要建立一把簽章金鑰，並要求 StrongBox（獨立的安全晶片）；真的 Keystore 要在手機上才能用，所以用一個假的 Keystore，行為和真的一樣：沒有 StrongBox 的裝置在要求 StrongBox 時丟出 `StrongBoxUnavailableException`，演練用兩支手機各跑一次。

**怎麼寫**

1. **Bug 版**：iOS 用 `try! SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: existing.rawRepresentation)` 匯入既有金鑰，App 當掉（從 Xcode 執行才看得到當掉的位置）。Android 建立金鑰時一律 `useStrongBox = true`。
2. **修正版**：iOS 先檢查 `SecureEnclave.isAvailable`，可以的話用 `SecureEnclave.P256.Signing.PrivateKey()` 在裡面產生金鑰、簽章、用公鑰驗證，不行的話降級成軟體金鑰。Android 先要求 StrongBox，接住 `StrongBoxUnavailableException`，改用 `useStrongBox = false`（一般 Keystore）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：log 印到「要把既有的私鑰搬進 Secure Enclave……」之後 App 當掉，主控台 `CryptoTokenKit`：corrupted objectID detected。Android：有 StrongBox 的手機金鑰建立在 StrongBox；沒有的手機建立金鑰失敗（`StrongBoxUnavailableException`） |
| 修正版 | iOS：簽章金鑰在 Secure Enclave 裡產生（實測 iOS 26.3 的模擬器也有 Secure Enclave），簽章演算法 P-256，伺服器驗證簽章成功。Android：有 StrongBox 的手機用 StrongBox，沒有的降級成 TEE |

**為什麼**：Secure Enclave 和 StrongBox 都是讓私鑰留在硬體裡，只能請晶片簽章。Secure Enclave 的金鑰只能在裡面產生，不能匯入既有的金鑰；它傳統上只支援 P-256，不支援區塊鏈常用的 secp256k1，所以不能直接拿來簽比特幣或以太坊的交易，適合用來產生「綁定這支手機」的金鑰、證明這是註冊過的裝置。StrongBox 不是每支 Android 手機都有，要處理 `StrongBoxUnavailableException` 並降級。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L05_SecureEnclaveImport.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L05_SecureEnclaveImport.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform04-secure-storage/L05_StrongBoxFallback.kt#bug
```

```lab-solution
android/solution/platform/platform04-secure-storage/L05_StrongBoxFallback.kt#fixed
```

### 演練 6：只擋一下，金鑰沒有綁定生物辨識（第 5 題）

**檔案**：iOS `L06_BiometricBinding.swift`、Android `L06_BiometricKey.kt`

**情境**：使用者要查看資產或確認訂單前，要先通過 Face ID、指紋。演練把 token（iOS）或金鑰（Android）存好之後，讀回它的屬性，看它有沒有被設成「要求生物辨識」。模擬器沒有登記 Face ID，系統不會真的跳驗證，所以檢查的是「項目被設成什麼」；真的會跳 Face ID 要在真機上看。Android 的演練要在手機（模擬器）上執行，而且要先到「設定」設好螢幕鎖並登記指紋（模擬器的 Extended controls 可以模擬指紋）。

**怎麼寫**

1. **Bug 版**：iOS 把 token 當一般項目存進 Keychain，不附 `kSecAttrAccessControl`（讀取前只靠 `LAContext.evaluatePolicy` 擋一下）；Android 建立 AES 金鑰時不要求使用者驗證。
2. **修正版**：iOS 用 `SecAccessControlCreateWithFlags`（`kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`、`.biometryCurrentSet`）建立存取控制，再用 `kSecAttrAccessControl` 附在項目上；Android 的 `KeyGenParameterSpec` 加上 `.setUserAuthenticationRequired(true)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：項目有 SecAccessControl：沒有；項目本身要求生物辨識：否。Android：金鑰要求使用者驗證：否 |
| 修正版 | iOS：項目有 SecAccessControl：有；項目本身要求生物辨識：是。Android：金鑰要求使用者驗證：是 |

**為什麼**：只靠 `evaluatePolicy` 或 `BiometricPrompt` 回傳「通過」，只是 App 自己的 if 判斷，被攻破的環境可以跳過它直接讀資料。真正的綁定，是讓 Keychain 項目或金鑰本身要求驗證，沒通過驗證，系統根本不給資料。Android 使用時把用這把金鑰初始化好的 `Cipher` 包成 `BiometricPrompt.CryptoObject` 交給 `BiometricPrompt`。兩個平台新增生物特徵之後，原本綁定的金鑰預設都會失效（iOS `.biometryCurrentSet`、Android `KeyPermanentlyInvalidatedException`），App 要準備好重新登入、重新綁定的流程。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L06_BiometricBinding.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L06_BiometricBinding.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform04-secure-storage/L06_BiometricKey.kt#bug
```

```lab-solution
android/solution/platform/platform04-secure-storage/L06_BiometricKey.kt#fixed
```

### 演練 7：背景改成 Filled，畫面還是 New（第 6 題，只有 iOS）

**檔案**：iOS `L07_ContextStale.swift`

**情境**：訂單列表從 Core Data 載入。成交回報到達時，在背景把訂單狀態改成 Filled 並存檔。Core Data 的每個 `NSManagedObjectContext` 有自己的物件快取，物件只屬於建立它的 context；Realm 的物件也只能在建立它的執行緒使用，道理一樣。演練用放在記憶體裡的 Core Data（一個 Order 實體）。Android 的 Room 與 Realm 沒有放進這個專案，所以這題沒有 Android 版。

**怎麼寫**

1. **Bug 版**：畫面一直拿著 `viewContext` 取出來的 `NSManagedObject`；背景另外用 `newBackgroundContext()` 改狀態並存檔。
2. **修正版**：資料庫存取包在 `actor OrderStore` 裡（自己擁有一個 background context），讀和寫都經過它，對外只回傳獨立的 struct（`OrderModel`）。畫面想看最新狀態，就再向它要一份新的 struct。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面載入時狀態 New；背景改成 Filled 並存檔之後，畫面重新讀取的狀態還是 New |
| 修正版 | 畫面載入時狀態 New；背景改成 Filled 並存檔之後，畫面重新讀取的狀態是 Filled |

**為什麼**：`viewContext` 取出的物件是它自己的快取，另一個 context 存檔之後不會自動更新。也不能把物件交給別的執行緒用（Core Data 要在 context 的 queue 上存取物件；Realm 則是物件只能在建立它的執行緒使用）。實務做法是把資料庫存取包起來，對外只傳複製出來的、獨立的資料。另一個修法是 `viewContext.automaticallyMergesChangesFromParent = true`，畫面可以跟上，但物件仍然只能在 `viewContext` 的執行緒使用。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L07_ContextStale.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L07_ContextStale.swift#fixed
```

### 演練 8：App 更新後，本機訂單全部消失（第 7 題）

**檔案**：iOS `L08_Migration.swift`、Android `L08_Migration.kt`

**情境**：1.0 版的 orders 沒有 `memo` 欄位，使用者手機裡已經存了 2 張訂單；1.1 版新增了 `memo`。使用者更新 App 之後，手機裡還是 1.0 版結構的資料庫檔案。iOS 先用 1.0 版的 model 寫入 2 張訂單（真的 Core Data、sqlite 檔），再用 1.1 版的 model 開啟同一個檔案。Android 用迷你資料庫（`L08MiniDbBuilder`）代替 Room：`addMigrations`、`fallbackToDestructiveMigration`、`build` 和 `RoomDatabase.Builder` 同名、規則一樣，找不到 migration 又沒有開 fallback 時，丟出和 Room 一樣的錯誤。

**怎麼寫**

1. **Bug 版**：iOS 用 `addPersistentStore` 開啟，不給 migration 選項，打不開（Core Data 的錯誤碼 134100）就刪掉資料庫檔案、重新建立。Android 看到「找不到 migration」的錯誤，就加上 `fallbackToDestructiveMigration()`。
2. **修正版**：iOS 開啟時加上 `NSMigratePersistentStoresAutomaticallyOption` 與 `NSInferMappingModelAutomaticallyOption`（lightweight migration：系統自己推斷新舊模型怎麼對應）；Android 寫一個 `Migration(1, 2)`，對每一列補上 `memo` 預設值 `""`（Room 裡寫 `ALTER TABLE orders ADD COLUMN memo TEXT NOT NULL DEFAULT ''`），用 `addMigrations(MIGRATION_1_2)` 加進去。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 更新前本機有 2 張訂單；更新後本機剩 0 張訂單（iOS 的主控台會印出 Core Data 的 134100 錯誤） |
| 修正版 | 更新前本機有 2 張訂單；更新後本機剩 2 張訂單（Android 的舊資料還補上了 `memo`） |

**為什麼**：資料庫結構改版時，使用者手機裡還是舊版的檔案。沒有 migration 路徑就打不開；「打不開就刪掉重建」或 `fallbackToDestructiveMigration()` 雖然讓 App 不會當掉，卻清空了使用者的資料。只有可以從伺服器重新抓回來的快取，才可以用刪掉重建；只要有使用者設定、草稿、尚未同步的資料，就是資料遺失。要用 `MigrationTestHelper`（Room）之類的工具，測試過真實的舊版本資料庫升上來。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L08_Migration.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform04-SecureStorage/L08_Migration.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform04-secure-storage/L08_Migration.kt#bug
```

```lab-solution
android/solution/platform/platform04-secure-storage/L08_Migration.kt#fixed
```

## 常見陷阱

- **把 token 放在 `UserDefaults` 或明文 `SharedPreferences`。** 它們是沒有加密的檔案，備份或越獄、root 後可以直接讀出來。
- **Keychain 不指定 accessibility，或開 `kSecAttrSynchronizable`。** 預設的 `WhenUnlocked` 讓背景流程讀不到；開同步會讓 session 經 iCloud 鑰匙圈到其他裝置。
- **只靠 `evaluatePolicy` 或 `BiometricPrompt` 的成功回傳當作保護。** 它只是 App 自己的判斷，要讓 Keychain 項目或金鑰本身綁定生物辨識。
- **重新安裝後，沒有清掉上一次殘留的 Keychain token。** Keychain 在 App 刪除後通常還在，會造成重新安裝後直接登入。
- **Android Auto Backup 還原了加密檔案，Keystore 金鑰卻不在。** 啟動時解密失敗而 crash。要排除備份，並處理解密失敗。
- **上線時留著 `fallbackToDestructiveMigration()`。** 找不到 migration 時整個資料庫被清空。
- **資料庫加密金鑰寫死在程式碼或 `BuildConfig`。** 反編譯就看得到，要放 Keychain／Keystore。
- **把 StrongBox 當成必備。** 不是每支 Android 手機都有，沒有降級路徑會讓部分使用者無法使用。
- **在主執行緒呼叫會跳出生物辨識的 Keychain 讀取。** 會卡住畫面。

## 面試官想聽到的

1. 能依資料的敏感度與使用情境，選擇 Keychain accessibility，並說明 `AfterFirstUnlock` 與背景流程的關係。
2. 清楚 Secure Enclave 與 StrongBox 的能力與限制：Secure Enclave 傳統上只支援 P-256、不支援 secp256k1、不能匯入金鑰；StrongBox 不是每台都有；金鑰都不可匯出。
3. 知道 Android Auto Backup 與 Keystore 的不一致，以及 iOS 重新安裝後 Keychain 仍在。
4. 知道生物辨識要「綁定金鑰」，而不是只在畫面上擋一下，並且知道新增生物特徵後金鑰會失效。
5. 資料庫選型與 migration 策略：測試過的 migration 路徑、不使用破壞性 fallback，加密金鑰放 Keychain／Keystore。

> [!WARNING]
> 面試官常追問：「token 存了 Keychain，手機被越獄了還安全嗎？」不要回答「安全」。要說明 Keychain 與 Keystore 是降低風險，不是絕對安全；所以 token 要短效、伺服器端可以註銷、敏感操作（提領、下單）要再次驗證，而不是靠本機儲存單獨守住。

> [!FIELD]
> 在一個社交 App 裡，雙平台都用 Realm 當本機資料庫。iOS 有兩個 Realm 檔案，各自包在一個 actor 裡，對外只回傳獨立的資料模型，不讓畫面碰到 Realm 物件；每次改 schema 都會提高 `schemaVersion` 並寫 migration。Android 的 Realm 存取用單一執行緒的 dispatcher 包住，用 `copyFromRealm` 取出獨立資料。聊天訊息在 iOS 以 client 產生的 UUID 當主鍵，同時作為去重用的識別碼（idempotency key），伺服器回應時就能取代本機的暫時資料。
> 在一個即時行情 App 裡，iOS 用 Keychain（KeychainAccess 套件）保存登入狀態與 FCM token；Android 用 Tink（AES-256-GCM）搭配 Android Keystore 的 master key 加密 cookie，存放在 DataStore。另一個 Kotlin Multiplatform 專案則用 SQLDelight 當跨平台共用的資料層。
> 換成交易所 App，我會用 Keychain 的 `AfterFirstUnlockThisDeviceOnly`（iOS）與 Keystore 加 Tink（Android）保存 token，關掉不必要的雲端同步與備份還原，登出時清除 token、快取與資料庫。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 存放秘密 | Keychain（`SecItemAdd`／`SecItemCopyMatching`）直接存放秘密 | Keystore 只保管金鑰；資料用 Tink 加密後存 DataStore |
| 硬體保護 | Secure Enclave：P-256，不能匯入，不支援 secp256k1 | 預設 TEE；StrongBox（API 28 起）不是每台都有，要處理 `StrongBoxUnavailableException` |
| 存取條件 | `kSecAttrAccessible`、`SecAccessControl`（生物辨識） | `setUserAuthenticationRequired`、`BiometricPrompt` 搭配 `CryptoObject` |
| 生物特徵變更 | `.biometryCurrentSet` 的項目失效 | `KeyPermanentlyInvalidatedException` |
| App 被刪除 | Keychain 內容通常保留 | Keystore 金鑰一併刪除 |
| 備份與還原 | `ThisDeviceOnly` 只能還原到同一台裝置、不同步 | Auto Backup 還原密文但不還原金鑰，要排除備份 |
| 加密函式庫 | CryptoKit（`AES.GCM`、`SecureEnclave.P256`） | Tink（AEAD、`AndroidKeysetManager`）；`EncryptedSharedPreferences` 已 deprecated |
| 本機資料庫 | Core Data、SwiftData（iOS 17 起）、Realm、GRDB | Room、SQLDelight、Realm |
| migration | lightweight migration、mapping model；Realm 的 `schemaVersion` | `AutoMigration`、`Migration`、`MigrationTestHelper`；`fallbackToDestructiveMigration()` 會清空資料 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 鑰匙圈 | Keychain | キーチェーン |
| Secure Enclave（蘋果裝置內的專用安全處理器） | Secure Enclave | セキュアエンクレーブ |
| Keystore（Android 的金鑰保管系統） | Android Keystore | キーストア |
| 有驗證功能的加密 | AEAD（Authenticated Encryption with Associated Data） | 認証付き暗号 |
| 資料庫遷移 | migration | マイグレーション |
| 生物辨識 | biometrics | 生体認証 |
| 自動備份 | Auto Backup | 自動バックアップ |
