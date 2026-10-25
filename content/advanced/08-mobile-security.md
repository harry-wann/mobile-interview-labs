---
id: advanced-mobile-security
title: 交易所 App 的安全：威脅模型、金鑰保管、生物辨識、certificate pinning、App 完整性與防截圖
summary: 加密貨幣交易所的 App 是攻擊者最想攻破的目標。這個單元以 OWASP 的行動 App 安全標準 MASVS 為骨架，說明要保護什麼、token 與金鑰放在哪裡、為什麼生物辨識要綁定金鑰、certificate pinning 為什麼要放備援金鑰、怎麼確認 App 是正版，以及防截圖、剪貼簿與出金流程的設計。
priority: P0
day: 3
tags: [security, masvs, keychain, keystore, biometric, pinning, play-integrity, app-attest, root-detection, flag-secure]
---

## 重點講解

這個單元回答七個面試常見問題：

1. 交易所 App 要保護什麼、防誰？MASVS 是什麼？
2. token 與金鑰要放在哪裡？
3. 為什麼生物辨識只看「成功」不夠，要綁定金鑰？
4. certificate pinning 是什麼？為什麼要放備援金鑰？
5. 怎麼確認「這是正版 App、跑在正常的裝置上」？
6. 怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？
7. 「出金到新地址」的安全流程怎麼設計？


### 1. 交易所 App 要保護什麼、防誰？MASVS 是什麼？

**最重要的前提是：手機上的 App 是不可信的環境。** 攻擊者可能擁有手機的完整控制權（root 或越獄後），能修改 App、攔截網路、改寫函式的回傳值。所以限額、授權、風控這些最終判斷都要在 server 做；App 端的防護只是「提高攻擊成本」和「提供風險訊號給 server」，不是保證。這種設計叫 defense in depth（縱深防禦）：不依賴單一防線，任何一層被繞過，還有下一層擋住。

先用一張表整理要保護的東西：

| 要保護的資產 | 主要威脅 | 對策 |
|---|---|---|
| 登入憑證（access token、refresh token，也就是登入後 server 發給 App 的通行證） | 手機上的惡意程式、備份外洩、log 洩漏、MITM（man-in-the-middle，中間人攻擊：有人在網路中間偷看或改封包） | 放進 Keychain 或 Keystore（iOS 與 Android 系統提供的加密儲存空間，第 2 題說明）、access token 壽命短、refresh token 輪替、TLS（Transport Layer Security，HTTPS 背後的加密傳輸協定）加 pinning、不寫進 log |
| 資產與出金 | 帳號被盜、釣魚、overlay（惡意 App 在畫面上蓋一層假畫面）、出金地址被替換、SIM swap（攻擊者把你的門號轉到他的 SIM 卡，收走簡訊驗證碼） | 敏感操作重新驗證（生物辨識加 2FA，也就是 two-factor authentication 雙重驗證）、出金地址白名單與冷卻期、server 端風控與限額 |
| 個資與 eKYC 影像（eKYC 是線上身分驗證，要拍證件與臉） | 手機遺失、截圖錄影、過度蒐集 | 不在手機上留存、加密、只蒐集必要的資料 |
| App 本身 | 重新打包、hook（執行中改寫函式行為）、逆向 | 混淆、完整性檢查，但不依賴 App 端做最終判斷 |

**MASVS 是什麼？** OWASP（Open Worldwide Application Security Project，一個資安社群）整理的行動 App 安全標準，全名 Mobile Application Security Verification Standard。現在的第二版把要求分成八類：

| 類別 | 管什麼 |
|---|---|
| STORAGE | 資料怎麼存在手機上 |
| CRYPTO | 加密演算法與金鑰的用法 |
| AUTH | 登入與驗證 |
| NETWORK | 網路傳輸 |
| PLATFORM | 與系統及其他 App 的互動（剪貼簿、deep link 等） |
| CODE | 程式碼品質與第三方函式庫 |
| RESILIENCE | 抵抗逆向與竄改 |
| PRIVACY | 隱私 |

另外有一份 MASTG（Mobile Application Security Testing Guide），是對應的測試方法。面試時能用這八類當骨架講，就顯得有系統。

> [!TIP]
> 面試時可以這樣說：「我的前提是手機上的 App 不可信，所以限額、授權和風控都在 server，App 端負責提高攻擊成本、提供風險訊號。我會用 OWASP MASVS 的類別當檢查清單：儲存、加密、驗證、網路、平台互動、程式碼、抗逆向和隱私。」

### 2. token 與金鑰要放在哪裡？

**iOS 放 Keychain，Android 用 Android Keystore 保管金鑰，資料用 AES-GCM 加密後再存。** 兩個平台共通的原則：不放在 `UserDefaults`、明文的 SharedPreferences 或一般資料庫，更不放在程式碼或版本控制裡。

**iOS：Keychain。** Keychain 是系統提供的加密儲存空間。重點是 accessibility（什麼時候可以讀取）要選對：

| 設定 | 意思 | 適合 |
|---|---|---|
| `WhenUnlocked` | 手機解鎖時才可讀 | 只在前景使用的憑證 |
| `AfterFirstUnlock` | 開機後解鎖過一次，之後都可讀 | 背景任務（例如推播處理）需要讀取的憑證 |
| `WhenPasscodeSetThisDeviceOnly` | 要有設定螢幕密碼才能存取，而且不能轉到別的裝置 | 最嚴格 |

名稱尾巴有 `ThisDeviceOnly` 的選項，不會隨 iCloud 備份或換手機遷移到其他裝置，避免憑證被帶走。`UserDefaults` 不是安全儲存，它的內容沒有加密。

**Android：Keystore 加 AES-GCM。** Android Keystore 把金鑰存在 TEE（Trusted Execution Environment，手機上與一般系統隔離的安全區域）或 StrongBox（獨立的安全晶片）裡，金鑰本身拿不出來，App 只能請它「幫我加密」或「幫我解密」。實際存 token 的流程是：在 Keystore 產生一把金鑰，用它以 AES-GCM（一種加密演算法，同時能驗證資料有沒有被竄改）加密 token，再把密文放進 DataStore 或檔案。Google 的 Tink 函式庫把這套流程包好，不用自己處理細節。

**登出時要刪除金鑰與資料。** 簽章用的 keystore 密碼、API 金鑰，不能提交進版本控制，要放在 CI（continuous integration，自動建置與測試的流程）的 secret 管理裡，並定期輪替。

> [!TIP]
> 面試時可以這樣說：「iOS 的 token 我放 Keychain，並選 ThisDeviceOnly 的 accessibility，避免被備份帶走；Android 用 Keystore 產生金鑰，搭配 Tink 以 AES-GCM 加密後再存。UserDefaults 和明文的 SharedPreferences 都不是安全儲存。」

### 3. 為什麼生物辨識只看「成功」不夠，要綁定金鑰？

**如果 App 只是呼叫生物辨識 API，然後依回傳的「成功」旗標決定要不要放行，攻擊者在 root 或越獄的手機上，可以直接 hook（改寫）那個函式讓它永遠回傳成功。正確做法是讓作業系統「強制驗證通過，才允許使用一把金鑰」。**

差別在於「誰擋門」。前者的判斷在 App 的程式裡，程式可以被改；後者的判斷在作業系統與安全硬體裡，沒有通過生物辨識，簽章或解密根本做不出來，改寫 App 也沒用。

**Android：** 產生 Keystore 金鑰時設定 `setUserAuthenticationRequired(true)`，使用時用 `BiometricPrompt.CryptoObject` 把 `Cipher` 或 `Signature` 交給 BiometricPrompt，驗證通過後才能用。`setInvalidatedByBiometricEnrollment(true)` 讓金鑰在手機新增指紋或臉部時失效，避免攻擊者偷到手機後加入自己的指紋。驗證強度要用 `BIOMETRIC_STRONG`。

```kotlin
// 在 Keystore 內產生 AES 金鑰；金鑰不能被匯出，使用前必須通過生物辨識
val keyGen = KeyGenerator.getInstance(
    KeyProperties.KEY_ALGORITHM_AES,
    "AndroidKeyStore",
)
keyGen.init(
    KeyGenParameterSpec.Builder(
        "token_key",
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        // 每次使用前都要通過驗證
        .setUserAuthenticationRequired(true)
        // 新增指紋或臉部時，這把金鑰失效
        .setInvalidatedByBiometricEnrollment(true)
        .build()
)
val secretKey = keyGen.generateKey()
val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
    init(Cipher.ENCRYPT_MODE, secretKey)
}
// 驗證時把 cipher 交給 BiometricPrompt，
// 使用者通過驗證後，才能用這個 cipher 加密：
// biometricPrompt.authenticate(
//     promptInfo,
//     BiometricPrompt.CryptoObject(cipher)
// )
```

**iOS：** 把資料存進 Keychain 時，附上 `SecAccessControl`，條件設為 `.biometryCurrentSet`（現在登錄的指紋或臉部組合改變，就失效）；讀取時系統會自己跳出 Face ID 或 Touch ID，驗證通過才給資料。要做簽章的話，可以用 Secure Enclave（手機內的獨立安全晶片）產生 P-256 金鑰（一種橢圓曲線加密的金鑰規格），私鑰永遠不離開晶片。

```swift
import Security
import Foundation

let tokenData = Data("refresh-token".utf8)
// 條件：手機要有設定螢幕密碼、金鑰只在這台裝置有效、且要通過目前登錄的生物辨識
let access = SecAccessControlCreateWithFlags(
    nil,
    kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
    [.biometryCurrentSet],
    nil
)!
let query: [String: Any] = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrAccount as String: "refresh_token",
    kSecValueData as String: tokenData,
    // 之後讀取這筆資料，系統會要求生物辨識
    kSecAttrAccessControl as String: access,
]
// 回傳 errSecSuccess 表示儲存成功
let status = SecItemAdd(query as CFDictionary, nil)
```

交易所的出金、改密碼這類敏感操作，應該搭配這個機制：請求要由這把金鑰簽章，server 驗證簽章。

> [!TIP]
> 面試時可以這樣說：「只看生物辨識回傳的成功旗標不夠，因為在 root 或越獄的裝置上這個旗標可以被改寫。我會把敏感操作綁在一把需要生物辨識才能使用的金鑰上，Android 用 Keystore 加 CryptoObject，iOS 用 SecAccessControl 或 Secure Enclave，這樣沒通過驗證就拿不到簽章。」

### 4. certificate pinning 是什麼？為什麼要放備援金鑰？

**certificate pinning（憑證釘選）是讓 App 只信任「事先指定的 server 公鑰」，即使手機裡被裝了惡意的根憑證，中間人也無法冒充 server。但如果沒有放備援金鑰，換憑證時會讓所有使用者連不上。**

背景：HTTPS 連線時，手機會檢查 server 的憑證是不是由「手機信任的憑證簽發機構（CA，certificate authority）」簽發的。如果攻擊者讓使用者裝了惡意 CA（例如釣魚網站引導安裝描述檔），就能用假憑證做 MITM 攻擊，看到所有請求內容，包括 token。pinning 多加一層檢查：連線的 server 公鑰，必須是 App 內預先放好的那幾把。

**做法重點：**

- **固定公鑰的 hash（SPKI，Subject Public Key Info，憑證裡存放公鑰的欄位），不要固定整張憑證。** 憑證每隔幾個月就會更新，但只要公鑰不變，pin 仍然有效。
- **預先放備援 pin。** 備援金鑰是另外事先產生、離線保管的第二把公鑰。萬一現在這把金鑰要換掉，server 換成備援金鑰，舊版 App 因為已經 pin 了備援，仍然連得上。沒有備援的話，換憑證當天，所有還沒更新的使用者都無法連線，只能強制他們更新 App，這是營運風險。
- 各平台的設定位置：OkHttp 的 `CertificatePinner`、Android 的 Network Security Config 的 `<pin-set>`（可以設 expiration 到期日）、iOS 14 以上的 `NSPinnedDomains`（寫在 Info.plist），或在 `URLSessionDelegate` 裡自己比對。

```kotlin
// OkHttp：同一個網域放兩個 pin，第二個是備援金鑰（hash 為示意）
val pinner = CertificatePinner.Builder()
    // 目前使用的公鑰
    .add(
        "api.example.com",
        "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    )
    // 備援公鑰
    .add(
        "api.example.com",
        "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
    )
    .build()
val client = OkHttpClient.Builder().certificatePinner(pinner).build()
```

```text
<!-- Android 的 Network Security Config
     （res/xml/network_security_config.xml） -->
<network-security-config>
    <domain-config>
        <domain includeSubdomains="true">api.example.com</domain>
        <pin-set expiration="2027-12-31">
            <pin digest="SHA-256">AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</pin>
            <pin digest="SHA-256">BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=</pin>
        </pin-set>
    </domain-config>
</network-security-config>
```

**現實：pinning 不是萬靈丹。** 在 root 或越獄的手機上，攻擊者可以用 Frida（可以在 App 執行中改寫函式行為的工具）之類的工具直接關掉檢查。所以 pinning 是提高成本，不是保證，仍然要搭配 server 端的請求簽章、裝置認證（下一題）與風險評分。另外基本的前提是全程使用 TLS：iOS 的 ATS（App Transport Security，預設只允許加密連線）不要隨便關，Android 設定 `cleartextTrafficPermitted="false"`。

> [!TIP]
> 面試時可以這樣說：「pinning 我會固定公鑰的 hash，而且一定放備援金鑰，這樣憑證輪替時舊版 App 不會連不上。我也知道在 root 或越獄的裝置上它可以被繞過，所以它是提高成本，還要搭配 server 端的簽章和裝置驗證。」

### 5. 怎麼確認「這是正版 App、跑在正常的裝置上」？

**有兩層：App 端偵測環境有沒有異常（root、越獄、被除錯），以及請系統開發商做證明（Android 的 Play Integrity API、iOS 的 App Attest）。重點是：所有檢查的結果都要送到 server 去判斷，不要在 App 裡直接擋。**

**環境偵測：root 與越獄。** root（Android）和 jailbreak（越獄，iOS）是使用者取得手機最高權限的狀態，風險是其他程式可以讀取 App 的資料、修改 App 的行為。偵測方式是檢查特徵，例如 Android 有沒有 `su` 指令、Magisk（常見的 root 工具）、系統是不是用測試簽章；iOS 有沒有 Cydia、可寫入的系統路徑。但這些檢查全部可以被繞過（例如 Magisk 的 DenyList 可以對特定 App 隱藏 root）。所以處理方式是**把結果當作風險訊號回報給 server**，由 server 的風控決定高風險操作（出金、更換綁定裝置）要不要多一道驗證或降低限額，而不是 App 一偵測到就強制關閉。這樣被繞過時不會全面失守，誤判一般使用者（自訂系統的開發者）時，也比較好調整。

**Android：Play Integrity API。** 它取代舊的 SafetyNet，由 Google 證明「這個請求來自 Google Play 上架的正版 App，跑在通過檢查的裝置上」。流程：

1. App 向 Play Integrity 取得 token，並把「這次請求」綁進去：classic 請求用 nonce（一次性的隨機值，例如請求內容的 hash），standard 請求用 request hash，避免 token 被別人拿去重複使用。
2. App 把 token 連同請求送到**自己的 server**。
3. server 呼叫 Google 的 API 解碼 token，檢查裝置判定（`MEETS_DEVICE_INTEGRITY`，要求更高時用 `MEETS_STRONG_INTEGRITY`）、App 是不是商店版本、nonce 是否相符，再決定要不要處理請求。

不要在 App 裡解析判定結果，因為 App 端的判斷可以被竄改。

```kotlin
// 取得 token，然後原封不動送到自己的 server，由 server 向 Google 解碼與判斷
val integrityManager = IntegrityManagerFactory.create(context)
// nonce 綁定這次請求
val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
integrityManager.requestIntegrityToken(request)
    .addOnSuccessListener { response -> sendToServer(response.token()) }
```

**iOS：App Attest。** iOS 14 起，`DCAppAttestService` 可以替「這個 App 在這台裝置上的這個實例」產生一把金鑰，並由 Apple 背書。流程是：server 先給一個一次性的挑戰值，App 產生金鑰並請 Apple 證明，把證明送給 server 驗證 Apple 的憑證鏈；之後每次敏感請求，App 用這把金鑰簽一次（稱為 assertion），server 驗證簽章與計數器。DeviceCheck 的 `DCDevice` 則是每台裝置只提供 2 個位元的雲端狀態，可以記錄「這台裝置做過某件事」。

```swift
import DeviceCheck
import CryptoKit

func attest(serverChallenge: Data) async throws {
    let service = DCAppAttestService.shared
    // 模擬器與部分裝置不支援
    guard service.isSupported else {
        return
    }
    // 在安全硬體內產生金鑰
    let keyId = try await service.generateKey()
    // 把 server 給的一次性挑戰值做 hash
    let hash = Data(SHA256.hash(data: serverChallenge))
    let attestation = try await service.attestKey(keyId, clientDataHash: hash)
    // 把 keyId 與 attestation 送給 server，由 server 驗證 Apple 簽發的憑證鏈
    _ = attestation
}
```

**其他：** 反除錯（偵測有沒有被 debugger 連上）和混淆（R8，也就是 Android 的程式碼縮減與混淆工具，或商用工具）能擋住業餘的攻擊者，但同樣可以被繞過，而且會讓 crash 報告更難讀，要保存對應的 mapping 檔。

> [!TIP]
> 面試時可以這樣說：「root 和越獄偵測都能被繞過，所以我把結果當風險訊號傳給 server，由 server 決定敏感操作要不要多一道驗證。Android 我會用 Play Integrity，把 nonce 綁定這次請求並送到我們自己的 server 驗證；iOS 用 App Attest，server 驗證憑證鏈和計數器。」

### 6. 怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？

**Android 可以直接擋截圖和錄影，iOS 不能擋截圖，只能偵測螢幕錄影、並遮住背景快照。** 兩邊的做法不同，面試時要分開講。

**Android：`FLAG_SECURE`。** 在 Activity 設定這個旗標，整個視窗無法截圖、無法錄影，最近使用的 App 列表（Recents）的縮圖也會變成黑的。Android 14 起還可以用 `Activity.registerScreenCaptureCallback` 偵測使用者截圖。

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 這個視窗的內容無法被截圖或錄影，Recents 縮圖也會變黑
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    setContentView(R.layout.activity_withdraw)
}
```

`FLAG_SECURE` 只保護這個視窗，通知內容、無障礙服務、或用另一支手機拍螢幕，都不在保護範圍內。

**iOS：沒有禁止截圖的 API。** 可以做的是：

- 用 `UIScreen.isCaptured` 和 `UIScreen.capturedDidChangeNotification` 偵測螢幕正在錄影或鏡像到外部螢幕，偵測到就隱藏敏感內容。iOS 17 起也有 `UITraitCollection` 的 `sceneCaptureState`，舊 API 在新版 SDK（開發套件）上可能被標示為 deprecated（不建議使用），以實際使用的版本為準。
- 用 `userDidTakeScreenshotNotification` 得到截圖的通知，但這是截完才通知，擋不住。
- App 進入背景時，用遮罩蓋住畫面。因為系統會在切換到背景時對畫面截一張快照，顯示在 app switcher（多工切換畫面），如果不遮，餘額就會被看到。

```swift
// 偵測螢幕錄影或鏡像：偵測到就用遮罩蓋住敏感內容
// 實際使用時是蓋在畫面最上層的遮罩 view
let privacyCover = UIView()
NotificationCenter.default.addObserver(
    forName: UIScreen.capturedDidChangeNotification,
    object: nil,
    queue: .main
) { _ in
    // isCaptured 為 true：正在被錄影或鏡像
    privacyCover.isHidden = !UIScreen.main.isCaptured
}
```

**剪貼簿：其他 App 讀得到。** iOS 14 起，App 讀取剪貼簿會跳出提示橫幅；iOS 16 起，用程式讀取還會詢問使用者是否允許貼上。Android 13 起，複製後會顯示預覽，敏感內容（例如密碼）可以用 `ClipDescription.EXTRA_IS_SENSITIVE`（API 33 起）標記，讓預覽隱藏起來。

**鍵盤：** 敏感欄位設成 secure text entry（iOS 的 `isSecureTextEntry`），iOS 還可以在 `application(_:shouldAllowExtensionPointIdentifier:)` 拒絕第三方鍵盤。

**log：** release 版本不要輸出 token、地址、餘額、個資。iOS 的 `os_log` 對動態字串預設會遮蔽；Android 用 Timber 時，release 版本不要掛會輸出的 Tree；Crashlytics（Firebase 的 crash 回報服務）的 custom key 也不要放個資。

> [!TIP]
> 面試時可以這樣說：「Android 我會在出金、顯示金鑰的頁面設 FLAG_SECURE。iOS 沒有禁止截圖的 API，所以我用 isCaptured 偵測錄影、在進入背景時用遮罩蓋住餘額，同時假設截圖一定會發生，不把不該截的資料放在畫面上。」

### 7. 「出金到新地址」的安全流程怎麼設計？

**每一步對應一種威脅：分段顯示地址防止被替換、重新驗證防止被盜用的 session、server 判斷與冷卻期給受害者反應時間、通知與稽核紀錄方便事後追查。**

加密貨幣特有的風險是**地址被替換**。有兩種常見手法：一是手機上的惡意程式監看剪貼簿，使用者複製地址後偷偷換成攻擊者的地址（剪貼簿劫持）；二是 address poisoning，攻擊者先送一筆很小的金額，讓一個跟你常用地址「頭尾幾碼一樣」的假地址出現在交易紀錄裡，等你從紀錄複製。出金一旦送出就無法取消，所以在流程上要設計多道關卡：

1. 使用者貼上或掃描新地址，App 把完整地址分段顯示，讓使用者逐段核對（防剪貼簿替換）。
2. 觸發重新驗證：生物辨識（綁定金鑰）加 2FA，並附上這次請求的完整性證明（nonce 綁定）。
3. server 驗證簽章、nonce 與裝置完整性，由風控決定是否放行；新地址進入冷卻期（例如等一段時間才能出金），或要求額外確認。
4. 通知使用者（推播與 email），並提供取消的途徑。
5. 冷卻期結束且使用者確認後，才實際出金，全程留下稽核紀錄。

日本的交易所對出金目的地有審核要求（例如出金目的地址要先經過審核與核准，細節以各交易所的公告為準）。App 的責任是把流程做完整，並帶出足夠的訊號給 server。

> [!TIP]
> 面試時可以這樣說：「出金到新地址，我會設計成多道關卡：地址分段核對、生物辨識加 2FA 重新驗證、附上裝置完整性證明、server 風控判斷、新地址冷卻期、通知與稽核紀錄。每一道對應一種威脅，這樣即使其中一道被繞過，還有其他道擋住。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced08Security**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Advanced08Security**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced08-Security/`，解答在 `labs/ios/Solution/Advanced/Advanced08-Security/`；Android 你寫在 `labs/android/practice/advanced/advanced08-security/`，解答在 `labs/android/solution/advanced/advanced08-security/`（檔名大多一樣，可以左右對照；演練 3 兩個平台的檔名不同，下面各自標明）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **需要自己操作的演練**：Android 的演練 3 沒有自動檢查，要在模擬器或手機上彈出 Dialog、自己截圖確認，步驟寫在 App 的演練說明裡。

這個單元是「怎麼保護」的設計，真正的攻擊與防禦不能也不該在專案裡實作，所以演練全部用**演練裡自己造的假物件**（假伺服器、假平台、假螢幕、假剪貼簿、編出來的地址），不連任何外部服務，也沒有任何攻擊程式；每一題只練一個「防禦邏輯寫錯會怎樣」。

### 演練 1：被改過的 App 繞過限額，照樣出金（第 1 題）

**檔案**：`L01_ClientOnlyLimit.swift`（iOS）、`L01_ClientOnlyLimit.kt`（Android）

**情境**：每日出金限額是 100 萬日圓。正常的 App 在送出前會先檢查金額。攻擊者改寫自己手機上的 App，跳過檢查，直接把 500 萬的請求送給伺服器。演練裡有一個假伺服器，記錄今天累計受理多少。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：伺服器的 `accepts` 直接回傳 `true`（相信 App 已經檢查過了）。
2. **修正版**：伺服器自己檢查：今天累計出金加上這一筆，不超過每日限額才受理。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `被改過的 App（跳過檢查），出金 5000000：伺服器受理`，`伺服器今天累計出金：5500000`，遠超過限額 |
| 修正版 | 被改過的 App 的 500 萬被伺服器拒絕，`伺服器今天累計出金：500000` |

**為什麼**：手機上的 App 是不可信的環境，攻擊者能改寫它的程式、跳過任何檢查。App 端的限額檢查只是為了使用者體驗（提早告訴使用者不行），真正的限額、授權、風控一定要在伺服器判斷（第 1 題的前提）。這也是縱深防禦的出發點：任何一層被繞過，下一層還要擋得住。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L01_ClientOnlyLimit.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L01_ClientOnlyLimit.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced08-security/L01_ClientOnlyLimit.kt#bug
```

```lab-solution
android/solution/advanced/advanced08-security/L01_ClientOnlyLimit.kt#fixed
```

### 演練 2：越獄／root 的手機通過檢查，證明還能換一筆用（第 5 題）

**檔案**：`L02_Attestation.swift`（iOS）、`L02_Attestation.kt`（Android）

**情境**：App 要在出金前讓伺服器知道「這台裝置安全嗎」。演練有三種情況：正常的手機；被越獄（root）且攻擊者 hook 了 App 裡的偵測函式（永遠回傳「沒有越獄」）的手機；以及攻擊者從正常手機的請求裡偷到一份證明，想配另一筆金額更大的出金。「平台」是假的 Apple／Google：它看得到裝置真正的狀態，用只有它和伺服器知道的金鑰替證明簽章（對應 App Attest、Play Integrity）。

**怎麼寫**

1. **Bug 版**：App 自己偵測，送一個欄位 `safe=true`／`safe=false`，伺服器看到 `safe=true` 就受理。
2. **修正版**：App 向平台要一份證明，`nonce` 用這次請求內容的 SHA-256（iOS 用 CryptoKit，Android 用 `MessageDigest`）；伺服器解碼證明，簽章不對就拒絕，並且要求「裝置安全」而且「nonce 等於這次請求的雜湊」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 被越獄且 hook 的手機：受理；拿正常手機的證明配另一筆 9000000 的出金：受理 |
| 修正版 | 正常的手機：受理；被越獄的手機：拒絕（平台簽的證明寫著 `trusted=0`，hook 改不了）；拿別人的證明配另一筆出金：拒絕（nonce 對不上） |

**為什麼**：App 端的偵測函式在攻擊者手上，可以被改成永遠回答「安全」；App 送來的欄位，伺服器無從分辨是真是假（第 5 題：所有檢查的結果都要送到伺服器去判斷）。真正擋得住的是平台簽章的證明：攻擊者控制得了 App，卻偽造不了平台的簽章。證明還要綁定這次請求（nonce），否則偷來的證明可以配任何請求使用。實務上 Android 用 Play Integrity（伺服器呼叫 Google 解碼），iOS 用 App Attest（伺服器驗證憑證鏈與計數器）；這題用 HMAC 簽章代替，重點是「誰簽、簽什麼」。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L02_Attestation.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L02_Attestation.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced08-security/L02_Attestation.kt#bug
```

```lab-solution
android/solution/advanced/advanced08-security/L02_Attestation.kt#fixed
```

### 演練 3：防截圖與錄影：iOS 開始錄影後還顯示餘額，Android 的 Dialog 還截得到（第 6 題）

**檔案**：`L03_CaptureCover.swift`（iOS）、`L03_CaptureProtection.kt`（Android）。兩個平台的機制不同，所以各做各的。

**情境**：iOS 沒有禁止截圖的 API，能做的是偵測錄影（`UIScreen.isCaptured`）就遮住餘額，進入背景時也遮住（系統會在這時截一張快照放在多工畫面）。演練用假的螢幕狀態，並直接送出系統會送的通知（`UIScreen.capturedDidChangeNotification`、`UIApplication.didEnterBackgroundNotification` 等），所以有自動檢查。Android 可以用 `FLAG_SECURE` 讓視窗無法截圖，但它只保護設定它的那個視窗；出金確認用 `AlertDialog` 彈出來，Dialog 是另一個視窗。這題需要在畫面上操作。

**怎麼寫**

1. **Bug 版**：iOS 只在畫面建立時讀一次 `isCaptured`，之後不管；Android 只替 Activity 的視窗加 `FLAG_SECURE`，Dialog 沒加。
2. **修正版**：iOS 監聽 `capturedDidChangeNotification`（每次變化重新讀 `isCaptured`）、`didEnterBackgroundNotification`（遮住）、`willEnterForegroundNotification`（依錄影狀態決定）；Android 在 `dialog.show()` 之前，也替 `dialog.window` 加 `FLAG_SECURE`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：開始錄影、切到背景之後，餘額都還是「顯示中」。Android：log 寫著「Activity 視窗：有 FLAG_SECURE；Dialog 視窗：沒有 FLAG_SECURE」，Dialog 開著時截圖，地址與金額清楚地出現在截圖裡 |
| 修正版 | iOS：依序是顯示中、已遮住（開始錄影）、顯示中（停止錄影）、已遮住（切到背景）、顯示中（回到前景）。Android：log 的 Dialog 視窗也有 `FLAG_SECURE`，Dialog 開著時截圖，對話框內容被擋住（黑色或出現無法截圖的提示） |

**為什麼**：敏感內容的保護是「每個會被看到的地方各自處理」。iOS 的錄影可以在使用者打開畫面之後才開始，只讀一次就永遠不會遮；背景快照是系統自己截的，要在進入背景的當下先遮住。Android 的 `FLAG_SECURE` 是視窗的屬性，Dialog、PopupWindow 是獨立的視窗，要各自設定（Compose 的 `Dialog` 預設會跟著 Activity 的視窗，用傳統 View 系統的 `AlertDialog` 就要自己設）。即使都做了，也要假設截圖一定會發生：用另一支手機拍螢幕、通知內容、無障礙服務，都不在保護範圍內。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L03_CaptureCover.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L03_CaptureCover.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced08-security/L03_CaptureProtection.kt#bug
```

```lab-solution
android/solution/advanced/advanced08-security/L03_CaptureProtection.kt#fixed
```

### 演練 4：複製的入金地址一直留在剪貼簿（第 6 題）

**檔案**：`L04_ClipboardLeak.swift`（iOS）、`L04_ClipboardLeak.kt`（Android）

**情境**：入金頁有「複製地址」按鈕。剪貼簿的內容任何 App 都讀得到。演練縮短過期時間到 0.6 秒，並模擬兩種情況：複製之後什麼都不做；複製之後使用者很快又複製了別的東西（`hello`）。iOS 的演練用真的 `UIPasteboard`；Android 的 JVM 單元測試沒有 `ClipboardManager`，演練用一個假的剪貼簿類別代表（`set` 對應 `setPrimaryClip`，`clear` 對應 `clearPrimaryClip`，每次寫入 `version` 加 1）。

**怎麼寫**

1. **Bug 版**：iOS 直接 `UIPasteboard.general.string = 地址`；Android 直接 `clipboard.set(地址)`。
2. **修正版**：iOS 改用 `setItems`，選項放 `.localOnly: true`（不經由 Universal Clipboard 同步到其他裝置）與 `.expirationDate`（到期由系統清除，只清這一次複製的內容）；Android 複製時標示敏感（對應 `ClipDescription.EXTRA_IS_SENSITIVE`，Android 13 起讓複製預覽隱藏內容），延遲到期後清除，但清除前先確認 `version` 沒變（還是自己複製的那一份）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 過了 1 秒（超過到期時間），剪貼簿裡還是完整的地址；Android 另外顯示「敏感標記：沒有」 |
| 修正版 | 過了 1 秒，剪貼簿是「（空的）」；使用者中途複製了別的東西，到期之後剪貼簿仍是 `hello`，沒有被誤清。Android 另外顯示「敏感標記：有」 |

**為什麼**：地址、金額這類資料複製到剪貼簿，就等於交給所有能讀剪貼簿的 App，甚至同一個 Apple ID 的其他裝置；剪貼簿劫持（偷偷把你複製的地址換成攻擊者的）也從這裡開始。最少要讓它會過期、不同步、預覽不顯示。到期清除時不能「清空整個剪貼簿」，否則會把使用者後來複製的東西（例如密碼）誤刪；iOS 的 `expirationDate` 由系統處理，Android 要自己比對 `version`（真正的 App 可用 `ClipDescription.getTimestamp()`）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L04_ClipboardLeak.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L04_ClipboardLeak.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced08-security/L04_ClipboardLeak.kt#bug
```

```lab-solution
android/solution/advanced/advanced08-security/L04_ClipboardLeak.kt#fixed
```

### 演練 5：頭尾一樣的假地址被當成已儲存的地址（第 7 題）

**檔案**：`L05_AddressPoisoning.swift`（iOS）、`L05_AddressPoisoning.kt`（Android）

**情境**：address poisoning（地址投毒）：攻擊者先對你的錢包送一筆很小的金額，讓一個開頭和結尾幾碼都和你常用地址一樣的假地址出現在交易紀錄裡，等你從紀錄複製。出金流程的規則：已儲存（白名單）的地址可以立刻出金；新地址要先冷卻 24 小時才能出金。演練只有「判斷這個地址是不是已儲存」的邏輯，地址都是編出來的假資料。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：只比對開頭 6 碼和結尾 4 碼是否和已儲存的地址一樣（就像使用者用眼睛核對的方式）。
2. **修正版**：完整比對整個地址（忽略英文字母大小寫）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `出金到從交易紀錄複製的假地址（頭尾相同）：放行（已儲存的地址）`，沒有冷卻就放行，資金送給攻擊者 |
| 修正版 | 假地址：`拒絕（新地址，先加入並冷卻 24 小時）`；加入 1 小時後：`拒絕（新地址冷卻中，還要等 23 小時）`；滿 25 小時才放行 |

**為什麼**：人眼只看得到頭尾幾碼，攻擊者可以算出頭尾都一樣的地址，所以程式比對時不能走一樣的捷徑，要完整比對。沒見過的地址一律走新地址的流程：冷卻期給受害者時間發現不對、取消，這是第 7 題「每一道關卡對應一種威脅」的其中一道。完整的流程還包括分段顯示地址讓使用者逐段核對、生物辨識加 2FA、附上裝置完整性證明（本單元演練 2）、通知與稽核紀錄。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L05_AddressPoisoning.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced08-Security/L05_AddressPoisoning.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced08-security/L05_AddressPoisoning.kt#bug
```

```lab-solution
android/solution/advanced/advanced08-security/L05_AddressPoisoning.kt#fixed
```

第 2 題（token 放 Keychain 或 Keystore、accessibility 的選擇）已經在平台對照 Platform04SecureStorage 的演練 1、演練 2 做過，第 3 題（生物辨識要綁定金鑰）在同一單元的演練 6 做過，這個單元不重複；第 4 題（certificate pinning 與備援金鑰）在平台對照的 Platform05Networking 演練 2、演練 3 做過。Face ID、指紋與硬體 Keystore 在模擬器上沒辦法完整驗證，也是不在這裡重做的原因。

## 常見陷阱

- **以為生物辨識回傳 `true` 就等於驗證成功。** 這個旗標可以被改寫。要把敏感操作綁在需要生物辨識才能使用的金鑰上。
- **把 pinning 當成防禦的全部，卻沒有備援金鑰與輪替流程。** 換憑證時全部使用者連不上，而且 root 的手機上 pinning 可以被繞過。
- **在 App 裡解析 Play Integrity 的判定，或不帶 nonce。** App 端的判斷可以被竄改，沒有 nonce 的 token 可以被重複使用。判斷一定要在 server。
- **把 root／越獄偵測當成強制封鎖。** 被繞過就全無防線，誤判一般使用者還會造成客訴。要當風險訊號，交給 server 決定。
- **把憑證放在網址的查詢參數裡。** 網址會被記進 log、瀏覽紀錄和代理伺服器。
- **以為 `FLAG_SECURE` 就完成了防護。** 通知內容、無障礙服務和外部相機拍螢幕都沒有被保護。
- **把簽章金鑰庫的密碼、API 金鑰提交進版本控制。** 要放在 CI 的 secret 管理並定期輪替。

## 面試官想聽到的

1. 能畫出威脅模型，並強調 App 端不可信，最終判斷在 server。
2. 懂生物辨識要綁定金鑰（Secure Enclave，或 Keystore 加 CryptoObject），並能說明只看成功旗標為什麼不夠。
3. 對 pinning 與 root 偵測有務實的看法：提高成本、當風險訊號、有營運風險（要有備援金鑰）。
4. 知道 Play Integrity（nonce 綁定請求、server 驗證）與 App Attest 的流程。
5. 對交易所特有的風險有概念：出金地址被替換、剪貼簿、SIM swap、釣魚與 overlay。
6. 能說明換成交易所 App 會怎麼做：把這些措施組成縱深防禦，而不是依賴任何單一防線。

> [!FIELD]
> 在一個社交 App 的 Android 端做過 root 偵測、Play Integrity 與網路環境檢查，iOS 端做過越獄偵測，登入憑證放在 Keychain。在一個即時行情 App 的 Android 端，用 Tink 的 AES-256-GCM（一種加密演算法）加密 cookie，主金鑰放在 Android Keystore；iOS 端也把 cookie 放在 Keychain。這些都是講「儲存」和「環境偵測」時可以直接舉的實例。
> 換成交易所 App，我會把這些經驗延伸到交易所的需求：敏感操作綁定生物辨識金鑰、加入 certificate pinning 並放備援金鑰、把環境偵測的結果送到 server 做風險判斷，以及出金地址白名單與冷卻期。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 安全儲存 | Keychain（選 `…ThisDeviceOnly`）、Secure Enclave | Keystore（TEE 或 StrongBox）加 AES-GCM（Tink） |
| 生物辨識綁定金鑰 | `SecAccessControl` 加 `.biometryCurrentSet` | `BiometricPrompt.CryptoObject` 加 `setUserAuthenticationRequired(true)` |
| 完整性證明 | App Attest、DeviceCheck | Play Integrity API（nonce 綁定請求，server 驗證） |
| certificate pinning | `NSPinnedDomains`（iOS 14 以上）、`URLSessionDelegate` | `CertificatePinner`、Network Security Config |
| 截圖與錄影 | 無法阻止截圖；偵測 `isCaptured`；進背景時遮罩 | `FLAG_SECURE`；Android 14 起可偵測截圖 |
| 越獄／root | 檢查越獄特徵，都可被繞過，結果送 server | 檢查 root、Magisk，都可被繞過，結果送 server |
| 剪貼簿 | 貼上時有提示橫幅與權限詢問 | 複製預覽；`EXTRA_IS_SENSITIVE` 隱藏敏感內容 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 行動 App 安全驗證標準 | MASVS（Mobile Application Security Verification Standard） | モバイルアプリのセキュリティ検証標準 |
| 憑證釘選（只信任指定的公鑰） | certificate pinning | 証明書ピンニング |
| 中間人攻擊 | MITM（man-in-the-middle attack） | 中間者攻撃 |
| 越獄／取得 root 權限 | jailbreak／root | 脱獄／root 化 |
| 裝置與 App 完整性證明 | attestation（App Attest、Play Integrity） | 端末・アプリの完全性証明 |
| 安全晶片 | Secure Enclave（iOS）／StrongBox（Android） | セキュアエンクレーブ／ストロングボックス |
| 出金地址白名單 | withdrawal address allowlist | 出金先アドレスのホワイトリスト |
| 雙重驗證 | 2FA（two-factor authentication） | 二要素認証 |
