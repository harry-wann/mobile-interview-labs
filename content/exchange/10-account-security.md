---
id: exchange-account-security
title: 帳戶安全：兩步驟驗證的 6 位數驗證碼（TOTP）、生物辨識、自動鎖定與新裝置登入
summary: 交易所 App 的帳戶安全，不是「多加一個 6 位數驗證碼」就結束。這個單元說明驗證碼怎麼算出來、伺服器怎麼驗證、生物辨識為什麼要綁在金鑰上、閒置鎖定要做到什麼程度、新裝置登入和出金這類高風險操作要怎麼多驗證一次。
priority: P1
day: 4
tags: [totp, 2fa, biometric, auto-lock, device-binding, session, step-up]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 兩步驟驗證的 6 位數驗證碼（TOTP）是怎麼算出來的？
2. 伺服器驗證 TOTP 時要注意什麼？
3. 生物辨識只看「成功」為什麼不夠安全？正確做法是什麼？
4. 自動鎖定（auto-lock）要做到什麼程度，才不只是「蓋一個畫面」？
5. 怎麼認出新裝置？登入狀態（session）要怎麼管理？
6. 出金這類高風險操作，為什麼要再驗證一次？

### 1. 兩步驟驗證的 6 位數驗證碼（TOTP）是怎麼算出來的？

**結論：驗證碼是用「雙方事先共有的秘密」加上「現在是第幾個 30 秒」算出來的。手機不需要網路，伺服器用同樣的輸入算一次，兩邊的數字一樣就通過。**

先把名詞講清楚：

- **兩步驟驗證**（英文 2FA，two-factor authentication）：登入時除了密碼，還要再提供第二個證明，例如手機上的驗證碼。
- **TOTP** 是 Time-based One-Time Password 的縮寫，「以時間為基礎的一次性密碼」，Google Authenticator 這類驗證器 App 用的就是它。規格寫在 RFC 6238。RFC 是 Request for Comments 的縮寫，指網路標準的文件，後面的數字是編號。
- **HOTP** 是 HMAC-based One-Time Password 的縮寫（RFC 4226）。TOTP 就是 HOTP 把「計數器」換成「現在的時間」。
- **HMAC** 是 Hash-based Message Authentication Code 的縮寫：給它一把秘密金鑰和一段資料，會算出一串固定長度的「指紋」。沒有金鑰的人，算不出相同的指紋。TOTP 預設用的雜湊演算法是 SHA-1（Secure Hash Algorithm 1），HMAC-SHA-1 的結果是 20 個位元組（byte）。

計算步驟如下，RFC 6238 附錄 B 的測試資料可以一步一步對照（秘密是文字 `12345678901234567890`，時間是 Unix 時間 59 秒；Unix 時間是從 1970-01-01 00:00:00 UTC 起算的秒數，UTC 是世界協調時間）：

1. **算 counter**：`counter = floor(Unix 時間 ÷ 30)`，也就是現在是第幾個 30 秒。59 ÷ 30 取整數是 `1`。
2. **把 counter 轉成 8 個位元組**，最高位的放最前面（big-endian）。
3. **算 HMAC-SHA-1**：用秘密當金鑰，對這 8 個位元組算 HMAC。這個例子的結果是十六進位 `75a48a19d4cbe100644e8ac1397eea747a2d33ab`。
4. **動態截斷**（dynamic truncation，RFC 4226 的用語）：取最後一個位元組 `0xab` 的低 4 個位元，得到 `0xb` = 11，當作 offset；從第 11 個位元組開始取連續 4 個位元組 `c1 39 7e ea`；把最高位元清成 0（做 `& 0x7f`），得到 `0x41397eea` = 1094287082。清掉最高位元，是為了讓結果在「整數有正負號」的語言（例如 Java、Kotlin 的 `Int`）裡不會變成負數。
5. **取後 6 位**：對 `1,000,000` 取餘數得到 `287082`，不足 6 位時左邊補零。RFC 的測試資料用 8 位數，同一個整數取後 8 位就是 `94287082`。

```swift
import CryptoKit
import Foundation

// 算出某個 counter 對應的 6 位數驗證碼（HOTP）
func hotp(secret: [UInt8], counter: UInt64) -> String {
    // 1. counter 轉成 8 個位元組，最高位放最前面（big-endian）
    var message = [UInt8](repeating: 0, count: 8)
    var c = counter
    for i in stride(from: 7, through: 0, by: -1) {
        message[i] = UInt8(c & 0xff)
        c >>= 8
    }
    // 2. 用秘密當金鑰，算 HMAC-SHA-1，得到 20 個位元組（SHA-1 在 CryptoKit
    // 裡叫 Insecure.SHA1）
    let mac = Array(
        HMAC<Insecure.SHA1>.authenticationCode(
            for: message,
            using: SymmetricKey(data: secret)
        )
    )
    // 3. 動態截斷：最後一個位元組的低 4 位元是 offset，從 offset 取 4
    // 個位元組，最高位元清成 0
    let offset = Int(mac[mac.count - 1] & 0x0f)
    let binary = (UInt32(mac[offset] & 0x7f) << 24)
               | (UInt32(mac[offset + 1]) << 16)
               | (UInt32(mac[offset + 2]) << 8)
               |  UInt32(mac[offset + 3])
    // 4. 取後 6 位，不足補零
    return String(format: "%06d", Int(binary % 1_000_000))
}

// TOTP：counter 就是「現在是第幾個 30 秒」
func totp(secret: [UInt8], unixSeconds: UInt64) -> String {
    hotp(secret: secret, counter: unixSeconds / 30)
}

let secret = Array("12345678901234567890".utf8)
// 輸出：287082
print(totp(secret: secret, unixSeconds: 59))
// 輸出：755224（counter 是 0，還沒換到下一個 30 秒）
print(totp(secret: secret, unixSeconds: 29))
// 輸出：081804（開頭的 0 不能掉）
print(totp(secret: secret, unixSeconds: 1111111109))
```

Kotlin 的版本幾乎一樣，只是 8 個位元組用 `ByteBuffer`（預設就是 big-endian），HMAC 用 `javax.crypto.Mac`：

```kotlin
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun hotp(secret: ByteArray, counter: Long): String {
    // 8 個位元組，big-endian
    val message = ByteBuffer.allocate(8).putLong(counter).array()
    val mac = Mac.getInstance("HmacSHA1").apply {
        init(SecretKeySpec(secret, "HmacSHA1"))
    }
    // 20 個位元組
    val h = mac.doFinal(message)
    // 最後一個位元組的低 4 位元
    val offset = h[h.size - 1].toInt() and 0x0f
    // Kotlin 的 Byte 有正負號，所以每個位元組都要先 and 0xff，再組成整數
    val binary = ((h[offset].toInt() and 0x7f) shl 24) or
        ((h[offset + 1].toInt() and 0xff) shl 16) or
        ((h[offset + 2].toInt() and 0xff) shl 8) or
        (h[offset + 3].toInt() and 0xff)
    // 補零到 6 位
    return (binary % 1_000_000).toString().padStart(6, '0')
}

fun totp(
    secret: ByteArray,
    unixSeconds: Long,
): String = hotp(secret, unixSeconds / 30)
// totp("12345678901234567890".toByteArray(), 59) 的輸出：287082
```

RFC 6238 附錄 B 的 SHA-1 測試資料（秘密都是 `12345678901234567890`），實作完可以用它驗證自己的程式：

| Unix 時間 | counter | 8 位數 | 6 位數 |
|---|---|---|---|
| 59 | 1 | `94287082` | `287082` |
| 1111111109 | 37037036 | `07081804` | `081804` |
| 1111111111 | 37037037 | `14050471` | `050471` |
| 1234567890 | 41152263 | `89005924` | `005924` |
| 2000000000 | 66666666 | `69279037` | `279037` |
| 20000000000 | 666666666 | `65353130` | `353130` |

RFC 也定義了 SHA-256 和 SHA-512 的版本（秘密要分別換成 32 個、64 個位元組的長度）。時間 59 秒的 8 位數結果分別是 `46119246` 和 `90693936`。實務上 SHA-1 最通用，改用其他演算法之前要先確認使用者常用的驗證器 App 支援。

**秘密怎麼交給手機？** 伺服器產生一組隨機秘密，用 Base32 編碼後放進一個網址格式的字串，顯示成 QR code（二維條碼），使用者用驗證器 App 掃描。Base32（RFC 4648）是只用 `A–Z` 和 `2–7` 這 32 個字元表示二進位資料的編碼，不分大小寫，結尾可能有 `=` 補齊：

```text
otpauth://totp/Example:user@example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&algorithm=SHA1&digits=6&period=30
```

上面的 `secret` 解碼後，就是 RFC 測試用的 `12345678901234567890`。

> [!TIP]
> 面試時可以這樣說：「TOTP 是把現在的時間除以 30 秒得到 counter，用雙方共有的秘密對 counter 算 HMAC-SHA-1，再做動態截斷，取後 6 位。手機不用連網，伺服器用同樣的秘密和時間算一次比對。我會用 RFC 6238 附錄 B 的測試資料驗證實作，特別注意開頭是 0 的碼要補零。」

### 2. 伺服器驗證 TOTP 時要注意什麼？

**結論：伺服器要做四件事：容許少量的時間誤差、同一個 counter 的驗證碼只能用一次、限制嘗試次數、用固定時間的方式比較。App 端負責好輸入、不要重複送出。**

- **容許時間誤差，但視窗不能大。** 手機和伺服器的時鐘不會完全一致，使用者輸入也要幾秒，所以伺服器通常不只算「現在」那一個 counter，也算前一個和後一個（視窗 ±1，共三個有效的碼）。6 位數只有 100 萬種可能，視窗越大，亂猜就越容易猜中；有人為了減少客訴把視窗放到 ±10，等於同時有 21 個碼有效。時鐘誤差大的使用者應該去校正手機時間，而不是放寬視窗。
- **同一個 counter 的驗證碼只能用一次。** 如果被人在旁邊看到、或被假網站騙走剛輸入的碼，攻擊者可以在有效期內再用一次。這種「把攔截到的合法請求原封不動再送一次」的攻擊叫重放攻擊（replay attack）。做法是記住這位使用者最後一次成功的 counter，之後只接受比它大的 counter。
- **限制嘗試次數。** 失敗幾次就暫時鎖定，並通知使用者。不限制的話，100 萬種可能是可以暴力試完的。
- **用固定時間比較**（constant-time comparison）。一般的字串比較，遇到第一個不同的字元就會結束，花的時間會洩漏「前面幾位是對的」。固定時間比較是不管哪裡不同，都花一樣的時間，避免這種計時側通道（timing side channel）。

```swift
// 伺服器端的概念示範（沿用前面的 hotp 函式）
// 回傳驗證成功的 counter；失敗回傳 nil。lastUsedCounter 是這位使用者上次成功的
// counter
func verifyTOTP(
    code: String,
    secret: [UInt8],
    now: Int64,
    lastUsedCounter: Int64?
) -> Int64? {
    let current = now / 30
    // 視窗 ±1
    for counter in (current - 1)...(current + 1) where counter >= 0 {
        // 用過的 counter 不能再用
        if let last = lastUsedCounter, counter <= last {
            continue
        }
        // 正式版這裡要改成固定時間比較
        if hotp(secret: secret, counter: UInt64(counter)) == code {
            // 呼叫端要把這個值存起來
            return counter
        }
    }
    return nil
}
```

**兩個因素不要放在同一個地方。** 兩步驟驗證的意思是「你知道的（密碼）」加上「你持有的（手機）」。如果交易所 App 自己在同一支手機上產生驗證碼，手機被入侵時，密碼和驗證碼會一起失守，兩個因素就變成一個。所以 TOTP 通常交給獨立的驗證器 App。

**不同驗證方式的強度**（一般認知）：

| 方式 | 弱點 |
|---|---|
| 簡訊（SMS，Short Message Service）驗證碼 | 最弱。SIM 卡轉移攻擊（SIM 是手機裡的用戶識別卡；攻擊者說服電信商，把你的門號轉到他的 SIM 卡上）就能收到簡訊，也容易被釣魚 |
| TOTP | 比簡訊好，但使用者在假網站輸入時，攻擊者可以即時把碼轉送到真網站（即時釣魚） |
| FIDO2／passkey | 最強。FIDO2 是 Fast IDentity Online 2 的縮寫，一組登入標準；登入用的金鑰綁定網站網域，假網站拿不到。iOS 16 起支援 passkey |

**App 端的輸入體驗**：驗證碼輸入框要設定成「一次性驗證碼」的 autofill 類型（iOS 用 `textContentType = .oneTimeCode`，Android 用 `autofillHints`），系統才會在鍵盤上方建議簡訊裡的碼。如果輸入滿 6 位就自動送出，要避免使用者同時按按鈕造成重複送出。

> [!TIP]
> 面試時可以這樣說：「伺服器端我會容許前後各一個 time step、記住最後成功的 counter 防止重放、限制嘗試次數並通知使用者，比較時用固定時間比較。視窗不會放大，時鐘誤差大的使用者請他校時。TOTP 也不會由同一個 App 產生，避免兩個因素變成一個。」

### 3. 生物辨識只看「成功」為什麼不夠安全？正確做法是什麼？

**結論：只呼叫 LocalAuthentication 框架的 `LAContext.evaluatePolicy`（iOS）或 `BiometricPrompt`（Android）、拿到「成功」就放行，只是把 App 裡的一個 `if` 打開，在被攻破的手機上可以被改成永遠成功。正確做法是把生物辨識綁在一把金鑰上：沒通過生物辨識，系統就不讓你用這把私鑰簽名，伺服器驗證簽名才相信。**

為什麼說只看布林值（true／false）不安全？越獄（iOS）或 root（Android）過的手機，攻擊者可以用 hook 工具（在執行時期攔截函式呼叫、直接改傳回值的工具）把 `evaluatePolicy` 的結果改成 `true`，App 裡的 `if success { 送出出金請求 }` 就被繞過了，生物辨識完全沒有保護任何東西。

綁金鑰的做法要用到非對稱金鑰：一把私鑰（只放在手機的安全硬體裡，無法匯出）、一把公鑰（可以公開），私鑰簽名、公鑰驗證。兩個平台放私鑰的地方：

- **iOS**：Secure Enclave，手機裡獨立於主處理器的安全晶片區，私鑰產生後不會離開它。Keychain 是 iOS 內建的加密保管庫，用來存放密碼、金鑰等敏感資料；設定「必須通過生物辨識才能使用」這類存取條件，用的是 `SecAccessControl`。
- **Android**：Keystore，Android 對應 Keychain 的機制。金鑰可以放在 TEE（Trusted Execution Environment，處理器裡和一般系統隔離的執行環境），部分手機還有 StrongBox（獨立的安全晶片）。

完整流程：

1. **註冊**：App 在手機上產生金鑰對，設定「使用私鑰前必須通過生物辨識」，把**公鑰**傳給伺服器登記。
2. **使用**：伺服器產生一個 **challenge**（挑戰碼：一次性、有時效的隨機字串）傳給 App。
3. App 跳出生物辨識。通過後，系統才允許用私鑰對 challenge 簽名。
4. App 把簽名傳給伺服器，伺服器用登記過的公鑰驗證。

這樣即使 App 被重新打包，沒有通過生物辨識就簽不出名；challenge 每次不同，攔截到的簽名也不能拿去重放。

```swift
// iOS：建立一把存在 Secure Enclave、使用前必須通過生物辨識的簽名金鑰
let access = SecAccessControlCreateWithFlags(
    nil,
    // 只在這支手機、而且有設定密碼時可用
    kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
    // 使用私鑰前要生物辨識；新增指紋或臉部後金鑰失效
    [.privateKeyUsage, .biometryCurrentSet],
    nil
)!
let key = try SecureEnclave.P256.Signing.PrivateKey(accessControl: access)
// 註冊時把公鑰傳給伺服器
let publicKeyForServer = key.publicKey.derRepresentation

// 使用時：呼叫 signature 會自動跳出生物辨識，通過才簽得出來
// challenge 是伺服器傳來的 Data
let signature = try key.signature(for: challenge)
```

```kotlin
// Android：建立一把使用前必須通過驗證的簽名金鑰（橢圓曲線 secp256r1，和 iOS 的
// P-256 是同一種）
val spec = KeyGenParameterSpec.Builder("auth_key", KeyProperties.PURPOSE_SIGN)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setDigests(KeyProperties.DIGEST_SHA256)
    // 每次使用前都要通過生物辨識
    .setUserAuthenticationRequired(true)
    .build()
val generator = KeyPairGenerator.getInstance(
    KeyProperties.KEY_ALGORITHM_EC,
    "AndroidKeyStore",
)
generator.initialize(spec)
// keyPair.public 傳給伺服器登記
val keyPair = generator.generateKeyPair()

// 使用時：把 Signature 交給 BiometricPrompt，通過驗證後才能簽名
val signature = Signature.getInstance("SHA256withECDSA").apply {
    initSign(keyPair.private)
}

biometricPrompt.authenticate(
    promptInfo,
    BiometricPrompt.CryptoObject(signature),
)
// 在 onAuthenticationSucceeded 裡：result.cryptoObject?.signature 才能
// update(challenge) 並 sign()
```

> [!WARNING]
> 面試官常追問：使用者新增指紋或臉部之後會怎樣？`.biometryCurrentSet`（iOS）和 Android 的預設行為，都會讓金鑰失效（Android 會丟出 `KeyPermanentlyInvalidatedException`）。這是刻意的，避免別人把自己的指紋加進去就能使用。所以要有「重新註冊」流程，而且重新註冊本身要用密碼加 2FA 驗證，不能變成繞過 2FA 的後門。

幾個補充：

- iOS 的 `.userPresence` 接受生物辨識或手機密碼；`.biometryAny` 在新增生物特徵後仍然有效；`.biometryCurrentSet` 最嚴格。
- Android 搭配 `CryptoObject` 時，建議用 `BIOMETRIC_STRONG` 這個最高的強度等級。
- 生物辨識只證明「現在是手機的主人在操作」，使用者的身分是註冊時就建立的。所以要有備援（密碼或 PIN，Personal Identification Number，個人識別碼），並限制嘗試次數。

> [!TIP]
> 面試時可以這樣說：「只看 evaluatePolicy 的布林值，只是 App 畫面層的檢查，在被攻破的手機上可以被 hook。我會把生物辨識綁在 Secure Enclave 或 Keystore 的私鑰上，註冊時把公鑰給伺服器，之後敏感操作由伺服器送 challenge，通過生物辨識後用私鑰簽名，伺服器驗證。新增生物特徵會讓金鑰失效，所以要設計重新註冊的流程。」

### 4. 自動鎖定（auto-lock）要做到什麼程度，才不只是「蓋一個畫面」？

**結論：自動鎖定是「App 閒置一段時間、或進背景太久再回來時，要重新驗證」。要做好有四件事：用不會被改系統時間繞過的時鐘計時、切換 App 時遮住畫面、鎖定時清掉記憶體裡的敏感資料、剪貼簿裡的資料要設定過期。**

**一、計時要用單調時鐘。** 單調時鐘（monotonic clock）是「只會往前走、不受使用者修改系統時間影響」的時鐘。如果用 `Date()` 或 `System.currentTimeMillis()` 計算「離開 App 多久」，使用者把系統時間往回調，就能繞過鎖定。

- Android：`SystemClock.elapsedRealtime()`，包含裝置睡眠的時間。
- iOS：`ContinuousClock`（iOS 16 起）或 `mach_continuous_time()`，兩者都包含裝置睡眠的時間。如果 App 的最低支援版本是 iOS 15，那麼 iOS 15 要用 `mach_continuous_time()`。`ProcessInfo.systemUptime` 在裝置睡眠時不計時，不適合拿來算離開多久。

```swift
// iOS 16 以上：進背景時記下時間點，回前景時比較
final class AutoLock {
    // 閒置多久要鎖，由產品決定
    private let timeout: Duration = .seconds(60)
    private var backgroundedAt: ContinuousClock.Instant?

    func appDidEnterBackground() { backgroundedAt = ContinuousClock.now }

    func shouldLockOnForeground() -> Bool {
        // 沒有記錄就當作要鎖
        guard let start = backgroundedAt else { return true }
        return ContinuousClock.now - start >= timeout
    }
}
```

```kotlin
class AutoLock(private val timeoutMs: Long = 60_000) {
    private var backgroundedAt: Long? = null

    fun onBackground() { backgroundedAt = SystemClock.elapsedRealtime() }

    fun shouldLockOnForeground(): Boolean {
        // 沒有記錄就當作要鎖
        val start = backgroundedAt ?: return true
        return SystemClock.elapsedRealtime() - start >= timeoutMs
    }
}
```

**二、切換 App 時遮住畫面。** 使用者按 Home 鍵或上滑開啟多工畫面（app switcher）時，系統會顯示每個 App 最後畫面的縮圖，餘額、地址就會被旁人看到。

- iOS：在 `sceneWillResignActive` 時蓋上一個遮罩畫面，回到前景（`sceneDidBecomeActive`）再移除。
- Android：對視窗設定 `FLAG_SECURE`，最近工作的縮圖會是空白，而且同一個視窗也不能被截圖或錄影（使用者自己也截不了）。Android 13（API level 33；API 是 Application Programming Interface 的縮寫，指程式提供給別人呼叫的介面，API level 是 Android 用來標示系統版本的編號）起還有 `Activity.setRecentsScreenshotEnabled(false)`，只關閉最近工作的縮圖，不影響截圖。

**三、鎖定時清掉記憶體裡的敏感資料。** 只蓋一層畫面的話，token（登入後伺服器發給 App 的憑證）、餘額快取仍然在記憶體裡，用記憶體傾印（memory dump）或除錯器還是拿得到。鎖定時要清掉，解鎖時再從 Keychain／Keystore 取回。

**四、剪貼簿。** 使用者複製出金地址、驗證碼之後，其他 App 也能讀到剪貼簿。iOS 可以在 `UIPasteboard` 設定過期時間和只限本機：

```swift
// 複製地址，60 秒後自動從剪貼簿消失，而且不同步到使用者的其他 Apple 裝置
UIPasteboard.general.setItems(
    [["public.utf8-plain-text": address]],
    options: [.expirationDate: Date().addingTimeInterval(60), .localOnly: true]
)
```

Android 13（API 33）起，可以在要放進剪貼簿的 `ClipDescription` 上標記 `ClipDescription.EXTRA_IS_SENSITIVE`，系統複製時跳出的剪貼簿預覽就不會顯示內容。

> [!TIP]
> 面試時可以這樣說：「自動鎖定我不會只蓋一層畫面。計時用單調時鐘，避免使用者改系統時間繞過；切到多工畫面時遮罩，Android 用 FLAG_SECURE；鎖定時把記憶體裡的 token 和餘額清掉，解鎖後再從 Keychain 或 Keystore 取回；複製地址後設定剪貼簿過期。」

### 5. 怎麼認出新裝置？登入狀態（session）要怎麼管理？

**結論：伺服器要維護「已授權裝置清單」，新裝置登入要通知使用者並多驗證一次。裝置是否可信，由伺服器根據 Apple 或 Google 的證明來判斷，不能只靠 App 自己偵測。登入狀態用「短效的 access token 加上可輪替的 refresh token」。**

**裝置識別碼放哪裡？** 通常是 App 第一次啟動時產生一個隨機 UUID（Universally Unique Identifier，通用唯一識別碼），存在 iOS 的 Keychain（`ThisDeviceOnly` 表示不會隨備份或 iCloud 鑰匙圈搬到別的裝置）。iOS 的 Keychain 項目在刪除 App 後通常還在，這是目前的行為，Apple 沒有保證。Android 的 Keystore 金鑰在 App 被解除安裝時就會被刪掉。**不要用廣告識別碼**（iOS 的 IDFA 是 Identifier for Advertisers 的縮寫，Android 的叫 Advertising ID）：使用者可以重設或關閉，不能當作安全依據。

```swift
// iOS：用 Keychain 保存「第一次啟動時產生的隨機 UUID」，當作這支手機的識別碼
func loadOrCreateDeviceID() -> String {
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.example.exchange.device",
        kSecAttrAccount as String: "device-id",
        kSecReturnData as String: true,
    ]
    var item: CFTypeRef?
    if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
       let data = item as? Data, let id = String(data: data, encoding: .utf8) {
        // 已經有了，沿用
        return id
    }
    // 第一次：產生新的並存起來
    let id = UUID().uuidString
    let add: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.example.exchange.device",
        kSecAttrAccount as String: "device-id",
        kSecValueData as String: Data(id.utf8),
        kSecAttrAccessible as String:
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    SecItemAdd(add as CFDictionary, nil)
    return id
}
```

這個識別碼只是「讓伺服器認得這支手機」，不是證明。要證明「這是未被改過的 App、在真實的裝置上」，用：

- **App Attest**（iOS 14 起，`DCAppAttestService`）和 **Play Integrity API**（Android）：由 Apple、Google 對 App 和裝置背書，產生的證明**送到伺服器驗證**。
- 越獄、root 偵測：在 App 裡檢查手機是否被破解。它可以被繞過，只能提高攻擊門檻，不是保證。

新裝置登入的流程：伺服器發現識別碼不在清單裡，就通知使用者（email 或推播），並要求額外驗證（email 確認、或已授權的舊裝置同意）。使用者也要能在 App 裡看到「目前登入的裝置」並移除。

**登入狀態怎麼管理？**

- **access token**（存取權杖）：每次呼叫伺服器的 API 都帶著，有效期很短（例如幾分鐘），被偷了損害有限。
- **refresh token**（更新權杖）：只用來換新的 access token，有效期較長。每次換新時，伺服器同時發一個新的 refresh token，舊的作廢，這叫輪替（rotation）；如果已作廢的舊 token 又被拿來用，代表可能被偷了，伺服器把整組登入狀態作廢。refresh token 還要和裝置綁定。
- **失效由伺服器控制**：使用者登出、改密碼、移除裝置時，伺服器讓對應的 token 失效。
- **兩種逾時並用**：閒置逾時（一段時間沒操作就要重新登入），以及絕對逾時（不管有沒有操作，登入超過一定時間就要重新登入）。
- **token 只放 Keychain／Keystore 的加密空間**，不放 `UserDefaults`（沒有加密的設定檔）、不寫進 log（程式執行時留下的紀錄）、不放在網址的參數裡（網址會被代理伺服器和 log 記錄）。

> [!TIP]
> 面試時可以這樣說：「新裝置的判斷在伺服器，伺服器有授權裝置清單，新裝置登入會通知使用者並多驗證一次。完整性我用 App Attest 和 Play Integrity，送到伺服器驗證；越獄、root 偵測只當輔助。登入狀態用短效 access token 搭配輪替的 refresh token，改密碼或移除裝置時由伺服器讓它們失效。」

### 6. 出金這類高風險操作，為什麼要再驗證一次？（step-up）

**結論：登入只證明「登入那一刻是本人」。出金、新增出金地址、修改密碼或 email、建立 API 金鑰、關閉 2FA 這些操作，要在做的當下再驗證一次，而且判斷一定在伺服器；client 的檢查只是為了讓流程順暢。**

這種「已經登入，但做高風險操作時要多驗證一次」的做法，業界叫 step-up authentication。為什麼需要？手機被借走、或登入狀態（token）被偷，攻擊者進得了 App，但不應該能直接把資產轉走。

示意流程（不是某一家交易所的實際 API）：

1. App 送出出金請求。
2. 伺服器發現這個操作需要 step-up，回應「需要再驗證」。
3. App 要求使用者輸入 TOTP，或做生物辨識並用私鑰簽 challenge。
4. App 帶著證明重送請求。
5. 伺服器驗證證明，並依風險（金額、是不是新地址、是不是新裝置）決定要不要放行，也可以加一封 email 確認信。

兩個設計重點：

- **證明要綁定這一筆操作。** 讓 challenge 包含出金的金額和地址，使用者簽的就是「這一筆」，攻擊者拿到簽名也不能換成別的收款地址。
- **新增出金地址後，可以設一段冷卻時間，期間限制出金額度**，讓攻擊者就算改了地址也不能馬上轉走。這是常見的設計，各家交易所的實際做法不同。多數業者的出金地址也都有審查流程，細節依各家規定。

> [!TIP]
> 面試時可以這樣說：「出金、新增地址、改密碼和 email、關閉 2FA 我都會做 step-up：當下再用 TOTP 或生物辨識簽章驗證一次，challenge 綁定金額和地址。判斷一定在伺服器，client 的檢查只是讓流程順暢。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange10AccountSecurity**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange10AccountSecurity**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange10-AccountSecurity/`，解答在 `labs/ios/Solution/Exchange/Exchange10-AccountSecurity/`；Android 你寫在 `labs/android/practice/exchange/exchange10-account-security/`，解答在 `labs/android/solution/exchange/exchange10-account-security/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的等**：驗證碼的秘密是 RFC 6238 的測試資料，伺服器、時鐘、裝置、金鑰都是程式裡的假物件（用 HMAC 代替裝置私鑰的簽章），時間用參數傳進去，每次執行的結果都一樣。

### 演練 1：驗證碼有時少一位、有時對不上（第 1 題）

**檔案**：`L01_TotpDigits.swift`（iOS）、`L01_TotpDigits.kt`（Android）

**情境**：演練拿 RFC 6238 附錄 B 的 6 組測試資料（秘密是文字 `12345678901234567890`），時間分別是 59、1111111109、1111111111、1234567890、2000000000、20000000000 秒，答案是 `287082`、`081804`、`050471`、`005924`、`279037`、`353130`。算 counter 與 HMAC-SHA-1 已經寫好（在 `Exchange10Support`），這題只寫「20 個位元組的 HMAC → 6 位數驗證碼」的動態截斷。

**怎麼寫**

1. **Bug 版**：取最後一個位元組的低 4 位元當 offset，從 offset 取 4 個位元組組成整數，**沒有**清掉最高位元（沒有 `& 0x7f`）；對 1,000,000 取餘數後直接轉成文字，**沒有**補零。
2. **修正版**：第一個位元組先 `& 0x7f` 再組成整數；取後 6 位之後補零到 6 位（iOS 用 `String(format: "%06d", …)`，Android 用 `padStart(6, '0')`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 時間 1111111109 秒算出 `81804`（少了開頭的 0）；最高位元是 1 的時間（59、1111111111、1234567890、20000000000 秒）算出完全不同的數字（iOS 例如 59 秒算出 `770730`，Android 的 `Int` 變成負數）；只有 2000000000 秒剛好對：「共 6 組 RFC 6238 測試資料，算錯的有 5 組」 |
| 修正版 | 6 組都和 RFC 的答案一致，「共 6 組 RFC 6238 測試資料，算錯的有 0 組」 |

**為什麼**：動態截斷取出的 4 個位元組，最高位元如果是 1，在有正負號的整數（Kotlin 和 Java 的 `Int`）裡就是負數，取餘數會得到負的結果；RFC 規定要把最高位元清成 0，讓結果永遠是非負整數。補零則是因為驗證碼是「6 個字元」，不是「一個數字」：`081804` 轉成整數再轉回文字就是 `81804`，比對不上。開發時隨便拿一個時間測，有 1/10 的機率碰到開頭的 0、一半的機率碰到最高位元是 1，所以一定要用有固定答案的測試資料，不要只在手機上和驗證器 App 對一組。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L01_TotpDigits.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L01_TotpDigits.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange10-account-security/L01_TotpDigits.kt#bug
```

```lab-solution
android/solution/exchange/exchange10-account-security/L01_TotpDigits.kt#fixed
```

### 演練 2：換碼瞬間輸入被拒絕，舊碼卻能重複使用（第 2 題）

**檔案**：`L02_TotpWindow.swift`（iOS）、`L02_TotpWindow.kt`（Android）

**情境**：伺服器收到使用者輸入的驗證碼，要判斷對不對。演練有 4 個情境，每個情境各自一台全新的伺服器，秘密是 RFC 的測試資料（第 59 秒的碼是 `287082`，第 100 秒的碼是 `969429`）：① 使用者在第 59 秒看到 `287082`，第 61 秒才送出（跨過了 30 秒的邊界），應該受理；② 同一個碼在第 40 秒送出被受理，第 55 秒（還在同一個 30 秒裡）又被送出一次（旁人重放），第 2 次應該拒絕；③ 第 59 秒的碼到第 119 秒才送出（差了 2 個 time step），應該拒絕；④ 使用者手機的時鐘快了 30 秒，第 61 秒收到第 100 秒的碼，應該受理。

**怎麼寫**

1. **Bug 版**：`makeVerifier` 回傳的函式只比對 `counter = now / 30`（現在這個 30 秒）的碼，沒有視窗，也不記住用過的 counter。
2. **修正版**：用 `lastUsed` 記住上次成功的 counter；比對 `current - 1`、`current`、`current + 1` 三個 counter，小於等於 `lastUsed` 的跳過；比對成功就更新 `lastUsed` 並受理。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 情境 1 拒絕、情境 2 的第 2 次受理（重放成功）、情境 4 拒絕，情境 3 剛好拒絕：「4 個情境，判斷錯的有 3 個」 |
| 修正版 | 情境 1 受理、情境 2 第 1 次受理第 2 次拒絕、情境 3 拒絕、情境 4 受理：「4 個情境，判斷錯的有 0 個」 |

**為什麼**：手機和伺服器的時鐘不會完全一致，使用者輸入也要幾秒，所以只比對「現在這個 30 秒」，剛好在邊界或時鐘有一點誤差的使用者就會被拒絕。但視窗不能亂放大：6 位數只有 100 萬種可能，視窗放到 ±10 等於 21 個碼同時有效，和 ±1（3 個碼）相比，亂猜的成功率放大 7 倍。另一件事是重放：驗證碼在它有效的 30 秒（加上視窗）內可以被重複送出，旁人在旁邊看到、或假網站騙走剛輸入的碼，都能再用一次。所以伺服器要記住這位使用者最後一次成功的 counter，之後只接受比它大的。另外還要限制嘗試次數、用固定時間比較碼（本演練沒有練）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L02_TotpWindow.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L02_TotpWindow.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange10-account-security/L02_TotpWindow.kt#bug
```

```lab-solution
android/solution/exchange/exchange10-account-security/L02_TotpWindow.kt#fixed
```

### 演練 3：沒設定生物辨識的手機，鎖定畫面直接放行（第 3 題）

**檔案**：`L03_BiometricFallback.swift`（iOS）、`L03_BiometricFallback.kt`（Android）

**情境**：鎖定畫面要求生物辨識才能解鎖。系統會回報 7 種結果：辨識成功、第 1 次失敗、第 3 次失敗、使用者按了取消、被系統鎖定（嘗試太多次）、金鑰失效（使用者新增了別人的指紋或臉部）、這支手機沒有設定生物辨識。每一種都要決定下一步：放行、再試一次、維持鎖定、改用密碼加 2FA（兩步驟驗證），或要求密碼加 2FA 之後重新註冊金鑰。規則：只有「成功」可以放行；失敗累積 3 次、被鎖定、不能用，都改用密碼加 2FA；金鑰失效要重新註冊，而且重新註冊本身也要密碼加 2FA。「生物辨識要綁在金鑰上、不能只看布林值」這個核心觀念，Platform04 的演練 6 已經練過，這題練的是失敗與例外之後的流程。

**怎麼寫**

1. **Bug 版**：成功放行、失敗就再試一次、取消維持鎖定，其他的結果一律放行（`default`／`else`，想著「不要卡住使用者」）。
2. **修正版**：每一種結果都明確對應一個決定：失敗累積 3 次改用密碼加 2FA，鎖定與不能用改用密碼加 2FA，金鑰失效要重新註冊；不寫 `default`／`else`，讓編譯器在新增結果時提醒你。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 被系統鎖定、金鑰失效、沒設定生物辨識都被放行，第 3 次失敗還在「再試一次」：「7 種結果，判斷錯的有 4 種，其中不該放行卻放行的有 3 種」 |
| 修正版 | 每一種都符合規則：「7 種結果，判斷錯的有 0 種，其中不該放行卻放行的有 0 種」 |

**為什麼**：「其他錯誤一律放行」是最容易寫出來的降級流程，因為產品經理最怕使用者被卡住。但它讓鎖定畫面對三種人失效：沒設定生物辨識的人（拿到手機的人什麼都不用做）、被系統鎖定的人（失敗太多次反而放行）、金鑰失效的人（攻擊者把自己的指紋加進借來的手機，金鑰失效後直接放行）。降級的原則是「改走更強或同等強度的驗證」，不是「略過驗證」。重新註冊金鑰本身也要用密碼加 2FA，否則它就變成繞過 2FA 的後門。Android 的 `BiometricPrompt` 要分清楚 `onAuthenticationFailed`（沒認出來，可以再試）和 `onAuthenticationError`（錯誤碼，例如鎖定、沒有設定）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L03_BiometricFallback.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L03_BiometricFallback.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange10-account-security/L03_BiometricFallback.kt#bug
```

```lab-solution
android/solution/exchange/exchange10-account-security/L03_BiometricFallback.kt#fixed
```

### 演練 4：把系統時間往回調，就繞過了自動鎖定（第 4 題）

**檔案**：`L04_AutoLockClock.swift`（iOS）、`L04_AutoLockClock.kt`（Android）

**情境**：App 進背景時記下時間，回前景時比較：離開超過 60 秒就要重新驗證。演練用一台假裝置模擬兩種時鐘：系統時間（使用者可以在設定裡改，網路校時也會讓它跳動）、單調時鐘（只會往前走、不受修改系統時間影響，含睡眠）。五個情境：A 離開 30 秒（不鎖）、B 離開 5 分鐘（鎖）、C 離開 5 分鐘但期間使用者把系統時間往回調 1 小時（鎖）、D 離開 10 秒但期間系統時間往前跳了 1 天（不鎖）、E 沒有進背景的記錄（鎖，這個情境已經寫好）。Exchange04 的演練 6 練過「行情的新鮮度也要用單調時鐘」，這題是安全功能：計時被繞過，就等於沒有鎖。

**怎麼寫**

1. **Bug 版**：進背景時用 `device.wallMs`（系統時間）記錄，回前景時用「現在的 `wallMs` 減掉記下的值」是否大於等於 60 秒判斷。
2. **修正版**：改用單調時鐘 `device.continuousMs`（iOS）／`device.elapsedMs`（Android）。真實的 App 對應 `ContinuousClock`（iOS 16 起，iOS 15 用 `mach_continuous_time()`）與 `SystemClock.elapsedRealtime()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「C 離開 5 分鐘，系統時間往回調 1 小時：不鎖 ✗」「D 離開 10 秒，系統時間往前跳 1 天：鎖定 ✗」，A、B、E 是對的：「5 個情境，判斷錯的有 2 個」 |
| 修正版 | 五個情境都是 ✓，「5 個情境，判斷錯的有 0 個」 |

**為什麼**：系統時間是使用者可以改的：往回調 1 小時之後，「現在減掉進背景的時間」變成負數，永遠小於 60 秒，自動鎖定形同虛設；網路校時讓時間往前跳，則會把只離開 10 秒的人誤鎖。單調時鐘只看「裝置真的走了多久」，改系統時間影響不到它。要注意兩個陷阱：iOS 的 `ProcessInfo.systemUptime` 與 Android 的 `Handler.postDelayed`（用 `uptimeMillis`）在裝置睡眠時不計時，睡眠 5 分鐘再醒來會以為只過了幾秒，所以計算「離開多久」要選含睡眠的單調時鐘。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L04_AutoLockClock.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L04_AutoLockClock.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange10-account-security/L04_AutoLockClock.kt#bug
```

```lab-solution
android/solution/exchange/exchange10-account-security/L04_AutoLockClock.kt#fixed
```

### 演練 5：用了一陣子就被強制登出（第 5 題）

**檔案**：`L05_RefreshRotation.swift`（iOS）、`L05_RefreshRotation.kt`（Android）

**情境**：登入狀態用「短效的 access token」加「可輪替的 refresh token」。伺服器每次換新時發新的 refresh token，舊的作廢；如果已作廢的舊 refresh token 又被拿來用，代表可能被偷了，伺服器把整組登入狀態作廢。演練用一台假伺服器：登入 → access token 過期 → 呼叫 API 得到 401 → 用 refresh token 換新 → 存起來 → 再呼叫 API；之後 access token 又過期一次，再重複一次。這題不處理「同時很多請求一起換新」的競爭，只看換新成功之後「存什麼」。

**怎麼寫**

1. **Bug 版**：`store` 只更新 access token，refresh token 還是登入時拿到的那一個。
2. **修正版**：`store` 把新的 access token 和新的 refresh token 一起存起來。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「第 1 次換新成功，呼叫 API：成功」，「第 2 次換新：伺服器判定舊的 refresh token 被重複使用，整組登入作廢」，「共換新 2 輪，成功 1 次；使用者被強制登出：是」 |
| 修正版 | 兩次換新都成功，每次換新後呼叫 API 都成功：「共換新 2 輪，成功 2 次；使用者被強制登出：否」 |

**為什麼**：這個 bug 很有隱蔽性：第一次換新完全正常（舊的 refresh token 還沒作廢），第二次才出事，所以開發時 access token 沒過期兩次、測試就看不到；上線後使用者「用了一陣子就被登出」，卻怎麼也重現不了。伺服器的反應是對的：已作廢的 refresh token 又出現，可能代表有人偷了舊 token，把整組登入作廢是保護使用者。解法很單純，但要有一個「連續換新三次以上」的測試。新裝置的判斷（授權裝置清單、通知使用者、App Attest／Play Integrity）都在伺服器，不在這個演練裡。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L05_RefreshRotation.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L05_RefreshRotation.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange10-account-security/L05_RefreshRotation.kt#bug
```

```lab-solution
android/solution/exchange/exchange10-account-security/L05_RefreshRotation.kt#fixed
```

### 演練 6：核准的是一筆出金，簽名卻能換地址使用（第 6 題）

**檔案**：`L06_StepUpBinding.swift`（iOS）、`L06_StepUpBinding.kt`（Android）

**情境**：出金前要 step-up：伺服器送一個一次性的 challenge（nonce），使用者用生物辨識核准，裝置用金鑰對「簽名內容」簽名，App 把出金請求（nonce、金額、地址、簽名）送給伺服器，伺服器重新組出簽名內容、驗證簽名、檢查 nonce 沒用過。演練用 HMAC 代替裝置私鑰的簽章。四個情境（同一台伺服器）：① 使用者核准 0.1 BTC 到地址 A，原樣送出，應該受理；② 手機被植入惡意程式，把地址換成攻擊者的地址 B 再送出，應該拒絕；③ 同樣情況把金額 0.1 改成 5，應該拒絕；④ 情境 ① 的請求被原封不動再送一次，應該拒絕。Platform06 的演練 5 練過簽名的「重放」，這題是另一件事：簽名內容沒有綁定金額與地址。

**怎麼寫**

1. **Bug 版**：`signedMessage` 只回傳 `nonce`：簽名證明了「使用者核准過一次出金」，沒有證明核准的是哪一筆。
2. **修正版**：`signedMessage` 回傳 `nonce`、金額、地址，用換行接在一起；伺服器用請求裡實際收到的金額與地址重新組出簽名內容，任何一個欄位被改過，簽名就對不上。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「簽名內容（核准當下，情境 ①）：ch-0001」，情境 ②（換地址）與情境 ③（改金額）都被受理，錢轉到攻擊者的地址；情境 ④ 因為 nonce 用過而拒絕：「4 個情境，判斷錯的有 2 個，其中不該受理卻受理的有 2 個」 |
| 修正版 | 簽名內容是 `ch-0001 / 0.1 / 地址 A`；情境 ① 受理，② ③ 拒絕（簽名不符），④ 拒絕（nonce 已經用過）：「4 個情境，判斷錯的有 0 個，其中不該受理卻受理的有 0 個」 |

**為什麼**：step-up 要證明的是「使用者同意了這一筆操作」，不是「使用者在某個時間點通過了生物辨識」。如果簽名只涵蓋一個 nonce，惡意程式或中間人可以在使用者核准之後，原封不動地沿用簽名，只換掉收款地址（或金額）。把金額和地址放進簽名內容，簽名就和這一筆綁死，nonce 則讓同一份簽名不能用第二次。判斷一律在伺服器：客戶端的檢查只是讓流程順暢，不能當作防線。新增出金地址之後加一段冷卻時間、冷卻期間限制出金額度，是業界常見的設計，各家實際的做法不同。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L06_StepUpBinding.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange10-AccountSecurity/L06_StepUpBinding.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange10-account-security/L06_StepUpBinding.kt#bug
```

```lab-solution
android/solution/exchange/exchange10-account-security/L06_StepUpBinding.kt#fixed
```

第 3 題「生物辨識綁在金鑰上」的核心實作（Secure Enclave、Keystore、`CryptoObject`）在 Platform04 的演練 6 練過，這個單元只練失敗之後的降級流程；防截圖與遮罩畫面在 Advanced08 的演練 3、剪貼簿過期在演練 4、裝置完整性（App Attest、Play Integrity）在演練 2 練過，不重複。

## 常見陷阱

- **只用 `evaluatePolicy` 的布林值當作驗證。** 這只是 App 畫面層的檢查，被 hook 就失效。要把生物辨識綁在 Keychain／Keystore 的金鑰上，用 challenge 簽章。
- **TOTP 驗證端沒有防重放。** 同一個碼在有效期內可以重複使用。要記住最後成功的 counter。
- **視窗開太大。** 為了減少客訴放寬到 ±5、±10 個 step，等於讓攻擊者的猜中機率放大好幾倍。時鐘誤差大的使用者應該去校時。
- **把 TOTP 的秘密和密碼放在同一個地方，或讓它出現在 log、截圖裡。** 兩個因素變成一個，被看到一次就永久失效。
- **自動鎖定只是蓋一個畫面。** 記憶體裡的 token 和餘額還在，要同時清掉。
- **用系統時間計算鎖定時間。** 使用者改系統時間就能繞過，要用單調時鐘。
- **裝置識別碼用廣告識別碼或其他可以重設的值。** 要用 Keychain 保存的隨機識別碼，而且是否為新裝置由伺服器判斷。
- **生物辨識註冊後，新增指紋仍然可以通過。** 金鑰沒有綁定註冊時的生物特徵；要用 `.biometryCurrentSet`，Android 保留預設的失效行為，並設計重新註冊流程。
- **改密碼之後，舊的登入狀態沒有失效。** 偷到 token 的人可以繼續使用，要由伺服器讓舊的 refresh token 和其他裝置的 session 失效。

## 面試官想聽到的

1. 能說明 TOTP 的完整算法（counter、HMAC、動態截斷、位數），知道可以用 RFC 的測試資料驗證，也知道伺服器端要防重放、限制視窗和嘗試次數。
2. 能說出生物辨識綁金鑰、challenge 簽章的流程，並說明為什麼單純的布林值不安全。
3. 自動鎖定知道要做的細節：單調時鐘、遮罩畫面、清除記憶體裡的敏感資料、剪貼簿過期。
4. 裝置綁定、完整性驗證（App Attest、Play Integrity）和 session 管理，判斷都放在伺服器。
5. 出金等高風險操作的 step-up 由伺服器強制。實際提供哪些 2FA 方式依各家交易所而定，可以在面試時反問。

> [!FIELD]
> 在一個社交 App 裡做過越獄偵測（iOS）以及 root、Play Integrity 的裝置檢查（Android），對應上面「裝置完整性」這一塊；在一個行情分析 App 裡，iOS 用 Keychain 保存登入狀態，Android 用 Tink（Google 的加密函式庫）搭配 Android Keystore 加密保存登入 cookie，對應 token 只放安全儲存空間的原則。
> 2FA 和「生物辨識綁金鑰」這兩塊，到交易所的 App 我會照這個單元的做法設計：TOTP 用 RFC 的測試資料驗證，生物辨識綁在 Keychain／Keystore 的金鑰上，搭配伺服器的 challenge 簽章。
> 面試時可以這樣說：「我在行情分析 App 用 Keychain 和 Keystore 保存登入狀態，在社交 App 也做過越獄、root 和 Play Integrity 的裝置檢查。到交易所的 App，我會把生物辨識綁在金鑰上，用 challenge 簽章，而不是只看 evaluatePolicy 的結果。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 算 HMAC | CryptoKit 的 `HMAC<Insecure.SHA1>`（SHA-1 在 CryptoKit 屬於 `Insecure`，因為不適合用來做新的安全設計，但 TOTP 規格預設就是它） | `Mac.getInstance("HmacSHA1")` |
| 生物辨識 | `LocalAuthentication`；要綁金鑰用 `SecAccessControl` | `BiometricPrompt` 搭配 `CryptoObject`；強度等級用 `BIOMETRIC_STRONG` |
| 存放私鑰的安全硬體 | Secure Enclave（CryptoKit 提供 P-256 簽章） | TEE（Trusted Execution Environment）或 StrongBox |
| 裝置完整性 | App Attest | Play Integrity API |
| 隱藏多工畫面 | `sceneWillResignActive` 時蓋遮罩 | `FLAG_SECURE`；API 33 起有 `setRecentsScreenshotEnabled(false)` |
| 單調時鐘 | `ContinuousClock`（iOS 16 起）、`mach_continuous_time()` | `SystemClock.elapsedRealtime()` |
| 剪貼簿 | `UIPasteboard` 的 `expirationDate`、`localOnly` | API 33 起 `ClipDescription.EXTRA_IS_SENSITIVE` |
| 驗證碼輸入框 | `textContentType = .oneTimeCode` | `autofillHints`（簡訊驗證碼可用 SMS Retriever 或 User Consent API） |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 兩步驟驗證 | two-factor authentication（2FA） | 二段階認証／二要素認証 |
| 以時間為基礎的一次性密碼 | TOTP（Time-based One-Time Password） | 時間ベースのワンタイムパスワード |
| 重放攻擊 | replay attack | リプレイ攻撃 |
| 生物辨識 | biometric authentication | 生体認証 |
| 挑戰碼（伺服器每次產生的一次性隨機字串） | challenge | チャレンジ |
| 裝置綁定 | device binding | 端末の紐付け |
| 高風險操作時再驗證一次 | step-up authentication | ステップアップ認証 |
| 自動鎖定 | auto-lock | オートロック |
