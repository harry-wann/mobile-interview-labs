---
id: exchange-ekyc-compliance
title: eKYC（線上身分驗證）與法規：開戶的本人確認、マイナンバーカード的 NFC 讀取、旅行規則
summary: 在日本開交易所帳戶，必須先確認「你是你」，而且大多在手機上完成。這個單元說明法律規定的幾種確認方式、App 怎麼用 NFC 讀マイナンバーカード（iOS 和 Android 的限制差很多）、證件影像怎麼處理才不會洩漏個資、審查狀態怎麼管理，以及旅行規則、同意書這些合規要求對 App 的影響。法規細節一律以法務確認為準。
priority: P1
day: 4
tags: [ekyc, kyc, my-number-card, jpki, nfc, core-nfc, isodep, privacy, compliance, travel-rule]
---

## 重點講解

> [!NOTE]
> 這個單元的法規說明是整理性的介紹，沒有逐項對照官方文件；標示「（未證實）」的是尚未確認的內容。面試時談到法規，請說「細節以法務確認為準」，不要斷言細節。

這個單元回答六個面試常見問題：

1. 在日本開戶為什麼要做本人確認？ホ、ヘ、ワ三種方式差在哪裡？
2. マイナンバーカード的 NFC 怎麼讀？iOS 和 Android 差在哪裡？
3. NFC 讀卡失敗時要怎麼處理？
4. 證件和臉部影像是高敏感的個人資料，App 要怎麼處理？
5. 審查要數小時到數日，App 的狀態要怎麼設計？
6. 旅行規則、同意書、風險揭露，對 App 有什麼影響？

### 1. 在日本開戶為什麼要做本人確認？ホ、ヘ、ワ三種方式差在哪裡？

**結論：日本法律要求交易所在開戶時確認使用者的身分，這叫本人確認。線上（不用到櫃檯）的方式有好幾種，面試常提三種：ホ（證件影像加臉部影像）、ヘ（證件的 IC 晶片資訊加臉部影像；IC 是 Integrated Circuit，積體電路）、ワ（電子證明書）。三種的技術路徑不同，App 要做的事也不一樣。**

先把名詞講清楚。本人確認的依據是犯収法，全名是「犯罪による収益の移転防止に関する法律」（犯罪收益移轉防止法），目的是防止洗錢。「非面對面」的意思是不用到櫃檯，在手機上完成。ホ、ヘ、ワ是施行規則裡條列項目的編號（就像 (a)、(b)、(c)）。

| 方式 | 內容 | App 端的技術 |
|---|---|---|
| ホ | 臉部影像加本人確認書類（證件）的影像 | 用相機拍攝證件的正面、背面和厚度，加上臉部自拍與活體偵測（確認鏡頭前是真人，不是照片或影片） |
| ヘ | 本人確認書類的 IC 晶片資訊加臉部影像 | 用 NFC（Near Field Communication，近距離無線通訊）讀取證件的晶片，加上臉部拍攝 |
| ワ | 電子證明書（JPKI，是 マイナンバーカード 裡的署名用電子證明書） | 用 NFC 讀卡，使用者輸入卡片密碼，由後端驗證 |

JPKI 是 Japanese Public Key Infrastructure 的縮寫（日文是公的個人認証サービス），用卡片裡的金鑰和證明書證明「這個人是本人」。JPKI 搭配マイナンバーカード，是最強的確認方式。其他確認方式（例如郵寄）這個單元不展開，以官方規則為準。

日本的加密資產交易所 App 通常提供用マイナンバーカード掃描完成 eKYC（eKYC 是 electronic Know Your Customer 的縮寫，電子化的客戶身分確認）。實際支援哪幾種方式、是自己開發還是使用第三方的 SDK（Software Development Kit，軟體開發套件），各家不同。

**設計上的重點：可用的驗證方式不要寫死在 App 裡，改由伺服器下發。** 做法是用功能旗標（feature flag：伺服器用一個設定值控制某個功能開或關，不用發新版 App）。法規或公司政策改變時，例如假設日後不再接受以影像為主的ホ方式，伺服器可以直接調整可用方式，不需要等使用者更新 App。這也代表 NFC 讀卡的成功率會變成關鍵指標，要追蹤不同機型的支援狀況和失敗原因。

App 內的開戶流程大致如下：

1. 說明頁：需要哪些文件、大約要多久、為什麼需要相機和 NFC 權限，並讓使用者同意條款，記錄同意的版本與時間。
2. 檢查裝置的相機與 NFC 能力、權限，依伺服器下發的可用方式，讓使用者選擇驗證方式。
3. 依選擇的方式取得資料：拍攝證件與臉部，或用 NFC 讀取 IC 晶片再搭配臉部影像。
4. 上傳。要可以續傳，而且重送不會建立兩份申請；成功後刪除本機暫存。
5. 審查中：由伺服器管理狀態，結果用推播通知。
6. 結果頁：通過就依帳號狀態開放功能，被退回就提供下一步。

> [!TIP]
> 面試時可以這樣說：「日本交易所開戶要依犯収法做本人確認，線上的方式有影像方式ホ、IC 晶片加臉部的ヘ、電子證明書的ワ。我會讓可用方式由伺服器下發，不寫死在 App，法規有變動時不用發版。實際規則細節我會以法務確認為準。」

### 2. マイナンバーカード的 NFC 怎麼讀？iOS 和 Android 差在哪裡？

**結論：マイナンバーカード是非接觸式的 IC 卡，通訊規格是 ISO/IEC 14443 Type B，App 用 APDU 指令和卡片溝通。iOS 用 Apple 提供的 Core NFC 框架裡的 `NFCTagReaderSession`，要先設定好權限和 AID 清單；Android 用 `IsoDep`，`transceive` 是會阻塞的呼叫，要放背景執行緒。**

名詞解釋：

- **NFC** 是 Near Field Communication 的縮寫，近距離無線通訊，卡片要貼近手機才能讀。
- **ISO/IEC 14443** 是非接觸式 IC 卡（IC 是 Integrated Circuit，積體電路）的國際標準，由國際標準化組織（ISO）與國際電工委員會（IEC）制定，訊號方式分 Type A 和 Type B。マイナンバーカード屬於 Type B。
- **APDU** 是 Application Protocol Data Unit 的縮寫（ISO 7816 規定）：手機送一個「指令封包」給卡片，卡片回一個「回應封包」。回應最後兩個位元組是狀態碼（SW1、SW2），`90 00` 代表成功。
- **AID** 是 Application Identifier 的縮寫，卡片裡每個應用程式的識別碼。讀卡的第一步是用 SELECT 指令，帶著 AID 指定要使用卡片裡的哪個應用程式。

整個流程是：SELECT（指定應用程式）→ 驗證卡片密碼 → 讀取資料，或請卡片簽署。

| | iOS（Core NFC） | Android |
|---|---|---|
| 裝置能力 | 只有部分 iPhone 機型（iPhone 7 以上、iOS 13 起）。用 `NFCTagReaderSession.readingAvailable` 檢查 | 看裝置有沒有 NFC 硬體、使用者有沒有開啟。`NfcAdapter.getDefaultAdapter` 可能回傳 `null`，`isEnabled` 可能是 `false` |
| 事先設定 | Xcode 加入「Near Field Communication Tag Reading」能力；Info.plist 加 `NFCReaderUsageDescription`（向使用者說明用途的文字），並在 `com.apple.developer.nfc.readersession.iso7816.select-identifiers` 列出要 SELECT 的 AID，不在清單裡的 AID 不能 SELECT | Manifest 加 `android.permission.NFC`；加 `<uses-feature android:name="android.hardware.nfc" android:required="false" />`，沒有 NFC 的裝置也能安裝，再用程式判斷要不要顯示這個功能 |
| 啟動讀取 | `NFCTagReaderSession(pollingOption: .iso14443, delegate:)`，系統會跳出讀取面板；一次只能有一個 session；只能在前景 | `NfcAdapter.enableReaderMode(activity, callback, FLAG_READER_NFC_B or FLAG_READER_SKIP_NDEF_CHECK, null)` |
| 收發資料 | 取得 `NFCISO7816Tag` 後，用 `sendCommand(apdu:)` | `IsoDep.get(tag)`，`connect()`、`transceive(bytes)`。**這是阻塞呼叫，不能在主執行緒執行**，`onTagDiscovered` 的回呼也不在主執行緒 |
| 時間限制 | session 有時間上限（約 60 秒，以官方文件為準），逾時要處理並讓使用者重試 | 沒有固定上限，要自己設 `IsoDep.timeout` 和整體逾時 |
| 提示畫面 | 系統面板可以更新 `alertMessage`（例如「讀取中，請不要移動」） | 自己顯示畫面和引導動畫 |

```swift
// iOS：開始讀卡前先確認裝置支援，再建立讀取 session（需要 import CoreNFC）
final class CardReader: NSObject, NFCTagReaderSessionDelegate {
    private var session: NFCTagReaderSession?
    // 官方文件載明的應用程式識別碼（AID），這裡用變數代替
    private let aid: Data

    init(aid: Data) { self.aid = aid }

    func start() {
        // 不支援的機型回傳 false
        guard NFCTagReaderSession.readingAvailable else { return }
        // .iso14443 會輪詢 Type A 和 Type B 的卡片；マイナンバーカード屬於
        // Type B
        session = NFCTagReaderSession(pollingOption: .iso14443, delegate: self)
        session?.alertMessage = "請將卡片靠近 iPhone 上方"
        session?.begin()
    }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(
        _ session: NFCTagReaderSession,
        didInvalidateWithError error: Error
    ) {
        // 使用者取消、逾時、讀取失敗都會到這裡
        self.session = nil
    }

    func tagReaderSession(
        _ session: NFCTagReaderSession,
        didDetect tags: [NFCTag]
    ) {
        guard let first = tags.first, case .iso7816(let card) = first else {
            session.invalidate(errorMessage: "這不是支援的卡片")
            return
        }
        session.connect(to: first) { error in
            if error != nil {
                session.invalidate(errorMessage: "連線失敗，請再試一次")
                return
            }
            // SELECT 指令：選擇卡片裡要使用的應用程式（expectedResponseLength
            // 為 -1 代表不指定回應長度）
            let select = NFCISO7816APDU(
                instructionClass: 0x00,
                instructionCode: 0xA4,
                p1Parameter: 0x04,
                p2Parameter: 0x0C,
                data: self.aid,
                expectedResponseLength: -1
            )
            card.sendCommand(apdu: select) { _, sw1, sw2, error in
                // 90 00 代表成功
                if error == nil && sw1 == 0x90 && sw2 == 0x00 {
                    session.alertMessage = "讀取中，請不要移動卡片"
                } else {
                    session.invalidate(errorMessage: "讀取失敗，請再試一次")
                }
            }
        }
    }
}
```

```kotlin
// Android：用 reader mode 讀取 Type B 的卡片
class CardReaderActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private val nfcAdapter: NfcAdapter? by lazy {
        NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        if (adapter == null || !adapter.isEnabled) {
            // 沒有 NFC，或使用者沒有開啟：導向替代方式或
            // Settings.ACTION_NFC_SETTINGS
            showNfcUnavailable()
            return
        }
        // FLAG_READER_NFC_B：只偵測 Type B；SKIP_NDEF_CHECK：不去讀 NDEF（NFC
        // 標籤的資料格式），加快速度
        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // 偵測到卡片時被呼叫，這個回呼不在主執行緒
    override fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        // 單一指令最多等 5 秒
        isoDep.timeout = 5_000
        try {
            isoDep.connect()
            // 阻塞呼叫：送出指令，等卡片回應
            val response = isoDep.transceive(selectCommand)
            // 回應最後兩個位元組是狀態碼，90 00 代表成功
        } catch (e: IOException) {
            // 卡片移開、逾時等
            showReadFailed()
        } finally {
            runCatching { isoDep.close() }
        }
    }
}
```

> [!TIP]
> 面試時可以這樣說：「マイナンバーカード是 ISO 14443 Type B 的卡片，用 APDU 溝通。iOS 要啟用 NFC Tag Reading 能力，在 Info.plist 列出要 SELECT 的 AID，session 約 60 秒上限、只能在前景；Android 用 IsoDep，transceive 是阻塞呼叫，要放背景執行緒，manifest 的 NFC feature 設 required false，沒有 NFC 的裝置也能安裝。」

### 3. NFC 讀卡失敗時要怎麼處理？

**結論：讀卡比一般的 App 操作更容易失敗，因為卡片要貼在手機特定的位置，而且過程中不能移動。App 要把每一種失敗都處理成「使用者看得懂、可以再試」，並且為沒有 NFC 或不想讀卡的人準備替代的方式。**

常見的失敗情況和處理方式：

| 情況 | 原因 | App 要做的事 |
|---|---|---|
| 裝置不支援 NFC | iOS 機型太舊；Android 沒有 NFC 硬體 | 進入流程前先檢查，直接提供替代方式 |
| NFC 被關閉（Android） | 使用者在系統設定關掉 | 說明原因，導向 NFC 設定頁 |
| 卡片中途移開 | 使用者手滑、手機殼太厚、卡套或金屬干擾 | 提示「請保持卡片貼著不動」，保留重試按鈕 |
| 逾時 | iOS session 約 60 秒；Android 自己設定的逾時 | 提示重試，不要讓畫面卡住 |
| 卡片密碼輸錯或被鎖定 | 密碼連續輸錯會被鎖定，只能到市區町村的窗口解鎖（細節以官方資料為準） | 依卡片回傳的狀態碼辨識「密碼錯誤」和「已鎖定」，鎖定時給明確指引；**不要儲存、記錄卡片密碼** |
| 找不到天線位置 | iPhone 的天線在機身上方，Android 每支手機位置不同 | 畫面上提供圖示引導 |

資料量比較大的時候（例如讀取證明書），APDU 要分成小段，一次讀一塊，每一塊獨立處理錯誤、逾時，也可以重試。這和藍牙裝置的通訊是同一類問題：這是「請求與回應」的協定，一次只送一個指令、等回應再送下一個，每個指令有自己的逾時。

```swift
// 把要讀取的資料切成一段一段，每一段就是一個 APDU 指令，一次送一個
func chunkPlan(
    totalSize: Int,
    chunkSize: Int = 200
) -> [(offset: Int, length: Int)] {
    var plan: [(offset: Int, length: Int)] = []
    var offset = 0
    while offset < totalSize {
        // 最後一段可能比較短
        let length = min(chunkSize, totalSize - offset)
        plan.append((offset, length))
        offset += length
    }
    return plan
}

print(chunkPlan(totalSize: 450))
// 輸出：[(offset: 0, length: 200), (offset: 200, length: 200), (offset: 400,
// length: 50)]
```

**替代路徑**：沒有 NFC 的裝置，或使用者不願意讀卡時，要提供其他方式與清楚的說明（依法規允許的其他確認方式，以官方為準），不要讓這些使用者卡在這一步。讀到的資料屬於個人資料，只在記憶體裡處理，立刻送到後端驗證；日誌（log）裡不要記錄 APDU 的內容和卡片密碼，也不要送進分析用的 SDK。

> [!TIP]
> 面試時可以這樣說：「讀卡失敗我會分類處理：卡片移開、逾時、密碼鎖定、NFC 關閉，每一種都有明確提示和重試，並提供畫面引導天線位置。大筆資料分段讀，一次一個指令、每個指令有自己的逾時。沒有 NFC 的使用者要有替代路徑，卡片密碼和 APDU 內容不寫進日誌。」

### 4. 證件和臉部影像是高敏感的個人資料，App 要怎麼處理？

**結論：影像只放在 App 自己的沙盒（sandbox，每個 App 專屬、其他 App 讀不到的儲存空間），用加密連線上傳，上傳成功就立刻刪除，而且不能進相簿、日誌或分析工具。**

**拍攝的部分**：證件拍攝要有相機預覽的框線引導，偵測模糊和反光，要求拍攝證件的厚度（把卡片側過來拍）；臉部要做活體偵測（眨眼、轉頭）。這些通常使用第三方的 eKYC SDK，或內嵌網頁（WebView：App 裡內嵌的網頁畫面）。WebView 裡要用相機時：iOS 的 `WKWebView` 從 iOS 14.3 起可以使用 `getUserMedia`，仍需要在 Info.plist 加 `NSCameraUsageDescription`；Android 要處理 `WebChromeClient.onPermissionRequest`。相機權限被拒絕時，要說明用途並導向系統設定，不要讓流程卡死。

虛擬相機、深偽影像（deepfake，用 AI 合成的臉）這類注入攻擊，主要靠 SDK 和後端處理；App 端可以搭配裝置完整性檢查（iOS 的 App Attest、Android 的 Play Integrity）和越獄、root 偵測。

**個資處理**。這裡的 PII 是 personally identifiable information（可以辨識出個人身分的資料）的縮寫，包含姓名、地址、證件號碼、影像等：

- **不存相簿**：相簿會被其他 App 讀取，也可能進入雲端備份。影像只寫進 App 沙盒。
- **上傳走 TLS**（Transport Layer Security，網頁連線使用的加密協定），可以續傳；上傳成功就立刻刪除本機檔案。使用者重試的需求，用「可續傳的上傳」滿足，不要長期保留原始影像。
- **日誌、分析、崩潰回報不能含 PII**，用遮罩或根本不送。
- **畫面保護**：Android 用 `FLAG_SECURE` 禁止截圖與錄影；iOS 無法阻止截圖，但可以偵測螢幕正在錄影或投影，並在 App 切換器（多工畫面）隱藏內容。
- **離開流程時清除暫存**：回上一頁、切到背景、被來電中斷，都要清掉暫存的資料。

```swift
// iOS：影像寫進 App 沙盒，指定「裝置鎖定時無法讀取」的檔案保護；
// 上傳成功後立刻刪除
try data.write(to: url, options: [.completeFileProtection])
try FileManager.default.removeItem(at: url)

// iOS 17 起：偵測畫面正在被錄影或投影，就隱藏敏感內容
if view.traitCollection.sceneCaptureState == .active {
    view.isHidden = true
}
```

```kotlin
// Android：這個視窗不能被截圖、錄影，在最近工作的縮圖也不會顯示內容
window.setFlags(
    WindowManager.LayoutParams.FLAG_SECURE,
    WindowManager.LayoutParams.FLAG_SECURE,
)
```

> [!TIP]
> 面試時可以這樣說：「證件和臉部影像是高敏感的個資。我會只放在 App 沙盒，用 TLS 可續傳地上傳，成功就刪除，不進相簿，日誌、分析和崩潰回報都不含 PII。Android 的拍攝畫面用 FLAG_SECURE，iOS 偵測螢幕錄影並在多工畫面遮罩。」

### 5. 審查要數小時到數日，App 的狀態要怎麼設計？

**結論：審查是非同步的，狀態由伺服器管理。App 啟動和回到前景時去抓最新狀態，審查結果用推播通知，使用者點通知直接回到結果頁。開放哪些功能（交易、入金、出金）由伺服器的帳號狀態決定，App 自己不判斷。**

典型的狀態是：未申請 → 申請中 → 審查中 → 通過，或退回（要求補件）→ 通過後解鎖功能。為什麼不能在本機存一個「已提交」旗標？因為重新安裝、換手機、審查結果改變時，本機的旗標就會和事實不一致，更不能拿來決定要不要開放出金。輪詢（每隔幾秒問一次）也不好：浪費電量和流量，而且 App 在背景本來就不會執行。

```swift
// 審查狀態和「能做什麼」都由伺服器回傳，App 只負責顯示
enum KycStatus: String, Decodable {
    case notStarted, submitted, inReview, approved, rejected
}

struct AccountState: Decodable {
    let kycStatus: KycStatus
    // 能不能交易，由伺服器決定
    let canTrade: Bool
    // 能不能出金，由伺服器決定
    let canWithdraw: Bool
}
```

幾個設計重點：

- **流程可以中斷，也可以續傳**：進度存在伺服器。上傳要具備冪等性（idempotent：同一個請求送出一次或多次，結果都一樣），做法是 App 為每次申請產生一個唯一的識別碼，伺服器看到相同識別碼就不再建立第二份。
- **推播加 deep link**：推播用 APNs（Apple Push Notification service，iOS）和 FCM（Firebase Cloud Messaging，Android）；deep link 是點通知後直接開到 App 內指定畫面的連結（iOS 用 Universal Link，Android 用 App Links）。
- **每個狀態都有明確的畫面和下一步**。被退回時，只告訴使用者「能採取的下一步」（重新拍攝、補件）。**不要顯示內部審查細節**，例如反洗錢篩查的命中原因，向被篩查的客戶透露篩查細節（tipping-off）本身就是合規問題。
- **功能開放看伺服器**：App 端的旗標可以被竄改，不能當作依據。

> [!TIP]
> 面試時可以這樣說：「審查是非同步的，我會讓狀態完全由伺服器管理，App 啟動和回前景時抓取，結果用推播加 deep link 回到結果頁。上傳做成可續傳且冪等。被退回時只給使用者能做的下一步，不洩漏篩查細節。功能開放以伺服器的帳號狀態為準。」

### 6. 旅行規則、同意書、風險揭露，對 App 有什麼影響？

**結論：這些是合規要求，App 的責任是把它們做成明確的畫面和紀錄：出金到外部地址要收集收款方的資訊，同意書要記錄版本和時間，風險要在使用者操作之前清楚呈現。**

**旅行規則**（日文トラベルルール，英文 travel rule）：加密資產從一家交易所轉到另一個地址時，轉出方的交易所要把轉出人與收款人的資訊傳給對方交易所，接收方也要收下並確認。日本從 2023 年 6 月起要求交易所做到這件事（細節以法務確認為準）。對 App 的影響是：登記出金地址的流程，要問清楚這個地址屬於哪家交易所（或是不是使用者自己的錢包）、是不是本人持有。多數業者的出金地址也都有審查與核准的流程，細節依各家規定。出金地址、memo 欄位等細節，見「錢包與 Web3」單元。

**同意書與規約**：要記錄同意的**版本、時間、語系**。App 啟動時檢查是否有新版本需要重新同意，使用者還沒同意前，要擋住相關功能。

```json
{
  "documentType": "terms",
  "version": "2026-10-01",
  "locale": "ja",
  "acceptedAt": "2026-10-24T09:00:00Z"
}
```

日本的加密資產交易所 App 常見日文、簡體中文、繁體中文、英文等多種語言。日文版規約通常是正本，其他語言版本的法律地位依各公司的規約。

**風險揭露**：價格會變動、不是存款保險的對象、槓桿交易的損失可能超過保證金等，要在開戶和開始交易前清楚呈現。宣傳文字不能有誤導性的獲利宣稱（JVCEA（Japan Virtual and Crypto Assets Exchange Association，日本的加密資產交易業自律組織）的自律規範有相關要求，細節以法務確認為準）。

**槓桿上限**：個人客戶的槓桿倍率上限是 2 倍（細節以法務確認為準）。這個上限在 App 畫面和伺服器端都要擋，不能只靠畫面。

**法規動向**：有報導指出 2026 年通過了把加密資產納入金融商品取引法的修正（含內線交易規範），以及加密資產分離課稅（20%）的方向，施行日與細節都未證實。對 App 的影響可能是規約、揭露、損益報表需要調整，不要在面試中斷言細節。

> [!TIP]
> 面試時可以這樣說：「旅行規則會影響出金地址登記，要收集地址屬於哪家交易所、是不是本人持有。同意書我會記錄版本、時間、語系，啟動時檢查有沒有新版本要重新同意。風險揭露要在開戶和交易前明確呈現，槓桿上限在畫面和伺服器都要擋。法規細節我以法務確認為準。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange11eKYC**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange11eKYC**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange11-eKYC/`，解答在 `labs/ios/Solution/Exchange/Exchange11-eKYC/`；Android 你寫在 `labs/android/practice/exchange/exchange11-ekyc/`，解答在 `labs/android/solution/exchange/exchange11-ekyc/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不碰真的 NFC，也沒有任何個資**：卡片、卡片密碼（演練用的 1234）、影像檔、審查狀態、規約版本都是程式裡的假資料，用純邏輯練「流程與規則」。演練裡的規則（旅行規則要填哪些欄位、版本格式…）是為了練習自訂的示範，不是實際法規，實際細節以法務確認為準。

### 演練 1：沒有 NFC 的手機也看得到讀卡，停用的方式還在選單上（第 1 題）

**檔案**：`L01_Methods.swift`（iOS）、`L01_Methods.kt`（Android）

**情境**：線上本人確認有三種方式：ホ（證件影像加臉部影像，用相機）、ヘ（證件的 IC 晶片資訊加臉部影像，要用 NFC 讀卡）、ワ（電子證明書，要用 NFC 讀卡）。畫面上要顯示的選單 = 伺服器開放的方式，過濾掉這支手機做不到的（沒有 NFC 不能用 ヘ、ワ）；一個都沒有時，要顯示說明頁。演練有四種情況：有 NFC 的手機且伺服器開放三種、沒有 NFC 的手機且伺服器開放三種、有 NFC 但伺服器停用了 ホ、沒有 NFC 且伺服器只開放 ヘ、ワ。

**怎麼寫**

1. **Bug 版**：`screen` 一律回傳三種方式都顯示的選單（`.choose(Method.allCases)`／`Choose(L01Method.entries)`），不看伺服器也不看手機。
2. **修正版**：從三種方式裡留下「伺服器有開放，而且（不需要 NFC，或手機有 NFC）」的；留下來的是空的就回傳「沒有可用方式」的說明頁，否則回傳選單。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第 1 種情況剛好對；沒有 NFC 的手機顯示了 ホ、ヘ、ワ，伺服器停用 ホ 之後選單還有 ホ，沒有可用方式時顯示選單而不是說明頁：「4 種情況，畫面判斷錯的有 3 種」 |
| 修正版 | 選單依序是 ホ、ヘ、ワ／只有 ホ／ヘ、ワ，最後一種顯示「沒有可用方式」說明頁：「4 種情況，畫面判斷錯的有 0 種」 |

**為什麼**：寫死的選單，讓「法規變動時不用發版」這個設計目標落空：伺服器想停用某個方式，舊版 App 還是照樣顯示。沒有 NFC 的手機點了讀卡才發現不能用，使用者會卡在開戶流程。正確的做法是兩層過濾：伺服器決定「政策上能不能用」（功能旗標），手機決定「技術上做不做得到」（有沒有 NFC、相機權限），兩者的交集才顯示；交集是空的就要有替代的說明與客服入口。NFC 讀卡的成功率也是重要指標，依機型與失敗原因分類追蹤。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L01_Methods.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L01_Methods.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange11-ekyc/L01_Methods.kt#bug
```

```lab-solution
android/solution/exchange/exchange11-ekyc/L01_Methods.kt#fixed
```

### 演練 2：輸錯一次密碼，卡片被自動重試鎖住（第 3 題）

**檔案**：`L02_CardPin.swift`（iOS）、`L02_CardPin.kt`（Android）

**情境**：用 NFC 讀 IC 卡時，App 送 APDU 指令，卡片回應的最後兩個位元組是狀態碼（SW1、SW2）。演練用一張假卡片，狀態碼用 ISO 7816-4 的通用定義：`9000` 成功；`63Cx` 密碼驗證失敗、還剩 x 次機會；`6983` 密碼已被鎖定。假卡片的密碼是演練用的 `1234`，剩餘 3 次，錯到 0 次就鎖定。App 送出密碼，依狀態碼決定下一步，整個流程最多送 5 次。兩個情境：① 使用者輸錯一次密碼（輸入 `1243`）；② 使用者輸入正確的密碼，但卡片在中途移開了一次（第一次送出沒有收到回應）。

**怎麼寫**

1. **Bug 版**：`classify` 是 `0x9000` 就回傳「成功」，其他（包含沒有回應）一律回傳「重試」。
2. **修正版**：依狀態碼分類：沒有回應（卡片移開）→ 重試；`63Cx` → 停止，顯示「密碼錯誤，還剩 x 次」（x 是 0 就顯示已鎖定）；`6983` → 顯示已鎖定；其他 → 顯示讀取失敗。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「情境 1（輸錯一次密碼）：卡片收到 5 次密碼，剩餘嘗試次數 0，卡片鎖定：是」（同一個錯的密碼被自動送了 5 次），畫面「讀取失敗，請再試一次」；情境 2 重試一次後成功 |
| 修正版 | 「情境 1：卡片收到 1 次密碼，剩餘嘗試次數 2，卡片鎖定：否」，畫面「密碼錯誤，還剩 2 次」；「情境 2：卡片收到 2 次指令，畫面顯示：讀取成功」 |

**為什麼**：「讀卡失敗就自動重試」對卡片移開、逾時這類暫時性的失敗是對的，但對「密碼錯誤」是災難：每一次重試都會消耗卡片的剩餘次數，使用者只輸入了一次，卡片就被 App 自己送 3 次錯誤的密碼鎖住，只能到市區町村的窗口解鎖。所以要用狀態碼把失敗分類：能重試的（沒有回應）、要停下來告訴使用者的（密碼錯誤與剩餘次數）、要給明確指引的（已鎖定）。卡片密碼和 APDU 的內容都不能寫進 log，也不能送進分析工具。卡片的實際規格（剩餘次數、鎖定規則）以官方資料為準，這個演練只練「依狀態碼分類」。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L02_CardPin.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L02_CardPin.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange11-ekyc/L02_CardPin.kt#bug
```

```lab-solution
android/solution/exchange/exchange11-ekyc/L02_CardPin.kt#fixed
```

### 演練 3：中途離開開戶流程，證件影像還留在手機裡（第 4 題）

**檔案**：`L03_TempImages.swift`（iOS）、`L03_TempImages.kt`（Android）

**情境**：開戶時要拍證件的正面、背面和臉部自拍，先存成暫存檔（App 沙盒）再上傳。個資的原則是不要長期保留原始影像：上傳成功就立刻刪除；使用者回上一頁、切到背景、被來電中斷、離開整個流程，也要把暫存檔清掉。演練用記憶體裡的假沙盒（沒有真的檔案與影像），三個情境，每個情境都已經拍好三張：① 上傳成功；② 使用者按返回離開流程；③ 上傳失敗（沒有網路），影像先保留讓使用者重試，之後使用者離開流程。每個情境結束時，沙盒裡都不應該留下任何影像。

**怎麼寫**

1. **Bug 版**：只在 `onUploadSucceeded` 呼叫 `sandbox.clear()`；`onLeaveFlow` 什麼都不做。
2. **修正版**：兩個時機都呼叫 `sandbox.clear()`。上傳失敗之後、使用者還在流程裡的時候，保留影像（這一步演練已經寫好）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「情境 2（按返回離開）：結束時沙盒裡還留著 3 個影像檔（front.jpg、back.jpg、face.jpg）」，情境 3 也留著：「3 個情境，結束時還留著影像的有 2 個」 |
| 修正版 | 三個情境結束時沙盒裡都沒有影像；情境 3 在離開流程之前保留 3 個檔案（給使用者重試）：「3 個情境，結束時還留著影像的有 0 個」 |

**為什麼**：「上傳成功才刪」是最直覺的寫法，卻漏掉了所有「沒有成功」的路徑：使用者拍完不想申請了、被來電中斷後沒有回來、上傳一直失敗就放棄。這些影像會一直留在 App 沙盒裡，直到 App 被刪除，裝置被入侵、備份被取得時就是個資外洩。解法是把清理放在「離開流程」這個時機，而不是只放在成功的回呼裡；需要重試的需求，用可續傳的上傳滿足，不是靠長期保留原始影像。同一組原則還包括：不放相簿、日誌與分析工具不含個資、Android 的拍攝畫面用 `FLAG_SECURE`。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L03_TempImages.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L03_TempImages.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange11-ekyc/L03_TempImages.kt#bug
```

```lab-solution
android/solution/exchange/exchange11-ekyc/L03_TempImages.kt#fixed
```

### 演練 4：重新審查中，出金按鈕卻又亮了起來（第 5 題）

**檔案**：`L04_StatusOrder.swift`（iOS）、`L04_StatusOrder.kt`（Android）

**情境**：審查狀態由伺服器管理，App 有兩個管道拿到它：啟動與回前景時主動抓（fetch），以及審查結果的推播。兩個管道的到達順序沒有保證：fetch 的請求先送出，回應卻比後來才發出的推播晚到。每一份狀態都帶著伺服器給的版本號 `revision`（越大越新），內容是審查狀態與 `canWithdraw`（能不能出金，由伺服器決定，App 只負責顯示）。演練依到達的順序送進 4 份狀態：① fetch（rev 3）通過；② 推播（rev 4）重新審查中，不可出金；③ 晚到的 fetch 回應（rev 3，其實是舊的）；④ 推播（rev 5）通過。

**怎麼寫**

1. **Bug 版**：`merge` 直接回傳 `update`：最後到的就蓋掉前面的。
2. **修正版**：沒有目前的狀態就接受；`update.revision` 比 `current.revision` 大才接受，否則維持目前的。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「③ 晚到的 fetch 回應 rev 3 → 畫面：通過（rev 3），出金按鈕：開啟 ✗（伺服器最新是 審查中，rev 4）」，其他三步是對的：「4 份更新，畫面和伺服器最新狀態不一致的有 1 份」 |
| 修正版 | ③ 的畫面維持「審查中（rev 4），出金按鈕：關閉」，④ 之後才變回通過：「4 份更新，畫面和伺服器最新狀態不一致的有 0 份」 |

**為什麼**：兩個非同步的管道互相競爭，「最後到的就是最新的」是錯的假設：fetch 是在推播之前送出的，只是回應比較慢。解法是讓伺服器給每一份狀態一個單調遞增的版本號，客戶端只接受更新的版本。這和行情的序號（sequence）是同一個想法。就算按鈕被誤開，伺服器收到出金請求仍然會依帳號狀態拒絕，但畫面顯示成「通過、可以出金」會誤導使用者，而且審查中的帳號被顯示成已通過，在合規上也不恰當。還有兩點是這個演練沒有練的：不要在本機存一個「已提交」旗標當依據；退回時只給使用者能採取的下一步，不洩漏內部審查細節。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L04_StatusOrder.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L04_StatusOrder.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange11-ekyc/L04_StatusOrder.kt#bug
```

```lab-solution
android/solution/exchange/exchange11-ekyc/L04_StatusOrder.kt#fixed
```

### 演練 5：該填的收款人資料沒填也能登記出金地址（第 6 題）

**檔案**：`L05_TravelRule.swift`（iOS）、`L05_TravelRule.kt`（Android）

**情境**：旅行規則（travel rule）要求出金到外部地址時，要收集收款方的資訊，所以登記出金地址的表單要依「這個地址屬於誰」要求不同的欄位。演練用自訂的示範規則：屬於其他交易所要填交易所名稱與收款人姓名；使用者自己的錢包要勾選所有權聲明；其他人的錢包要填收款人姓名與關係；欄位只有空白也算沒填。`validate` 回傳缺少的欄位名稱，空的代表可以送出。六種表單：交易所（完整／缺收款人姓名／交易所名稱只有空白）、自己的錢包（沒勾聲明／有勾）、其他人的錢包（什麼都沒填）。

**怎麼寫**

1. **Bug 版**：只在收款對象是「其他交易所」時檢查，而且用 `isEmpty` 判斷其他欄位是否為空。
2. **修正版**：依三種收款對象分別檢查它該填的欄位（不寫 `default`／`else`）；字串欄位先去掉前後空白再判斷（iOS 用 `trimmingCharacters(in: .whitespacesAndNewlines)`，Android 用 `isBlank()`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「交易所，交易所名稱只有空白 → 可以送出 ✗」「自己的錢包，沒勾所有權聲明 → 可以送出 ✗」「其他人的錢包，什麼都沒填 → 可以送出 ✗」：「6 種表單，判斷錯的有 3 種」 |
| 修正版 | 六種表單都符合規則，例如「其他人的錢包，什麼都沒填 → 缺 收款人姓名、與收款人的關係 ✓」：「6 種表單，判斷錯的有 0 種」 |

**為什麼**：必填欄位檢查有兩個常見的洞：只想到最常見的情境（交易所），其他情境沒有人檢查；以為「不是空字串」就是有填，卻忽略只有空白的字串。對旅行規則來說，其他人的錢包反而是風險最高、最需要收集資訊的情境。畫面的檢查是為了讓流程順暢，伺服器還是要再驗一次；欄位的清單最好由伺服器下發，規則改變時不用發版。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L05_TravelRule.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L05_TravelRule.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange11-ekyc/L05_TravelRule.kt#bug
```

```lab-solution
android/solution/exchange/exchange11-ekyc/L05_TravelRule.kt#fixed
```

### 演練 6：規約更新到 1.10，同意過 1.9 的人沒被要求重新同意（第 6 題）

**檔案**：`L06_ConsentVersion.swift`（iOS）、`L06_ConsentVersion.kt`（Android）

**情境**：同意書要記錄版本、時間、語系。App 啟動時，拿「使用者上次同意的版本」和「伺服器要求的版本」比較，伺服器的版本比較新，就要擋住相關功能、要求重新同意。複習站範例用日期當版本號；演練改用「主版本.次版本」（1.9、1.10、2.0），這種格式最容易讓人直接拿字串比較（版本格式是演練的自訂）。六種情況：沒同意過、1.9 → 1.10、1.10 → 1.10（一樣）、2.0 → 10.0、1.10 → 1.9（伺服器退回舊版）、1.2 → 1.3。

**怎麼寫**

1. **Bug 版**：沒同意過回傳 `true`；否則直接用字串比較 `required > accepted`。
2. **修正版**：沒同意過回傳 `true`；否則把兩個版本用 `.` 拆開、轉成整數，逐段比較數字，第一個不同的段決定誰比較新。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「同意過 1.9，伺服器要求 1.10 → 不用重新同意 ✗」「同意過 2.0，伺服器要求 10.0 → 不用重新同意 ✗」「同意過 1.10，伺服器退回 1.9 → 要重新同意 ✗」：「6 種情況，判斷錯的有 3 種」 |
| 修正版 | 六種情況都 ✓：「6 種情況，判斷錯的有 0 種」 |

**為什麼**：字串是逐字元比較的：`"1.10"` 和 `"1.9"` 比到第三個字元，`"1"` 小於 `"9"`，所以 `"1.10"` 被當成比 `"1.9"` 小；`"10.0"` 和 `"2.0"` 也是一樣。結果是新版規約出來了，一部分使用者卻沒有被要求重新同意，卻能繼續使用，這是合規問題。版本比較是很容易被當成小事的地方：一開始只有 1.1 到 1.9，沒人發現，到了 1.10 才出事。用日期當版本時，只要補零的格式固定，字串比較也能用；但不要依賴這個巧合，直接把版本轉成數字或日期物件再比較。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L06_ConsentVersion.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange11-eKYC/L06_ConsentVersion.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange11-ekyc/L06_ConsentVersion.kt#bug
```

```lab-solution
android/solution/exchange/exchange11-ekyc/L06_ConsentVersion.kt#fixed
```

第 2 題（NFC 的前置設定、`NFCTagReaderSession`、`IsoDep.transceive` 不能在主執行緒）要在真機上才看得出差異，沒有可以穩定自動檢查的演練；槓桿上限要在畫面和伺服器都擋，在 Advanced08 的演練 1 練過伺服器端的限額，這個單元不重複。

## 常見陷阱

- **把可用的驗證方式寫死在 App。** 法規或公司政策有變動時就要發版，改用伺服器下發的功能旗標。
- **iOS 沒有宣告 AID，或沒有啟用 NFC 能力。** SELECT 會失敗，而且很難從錯誤訊息看出原因。
- **Android 在主執行緒呼叫 `transceive`，或沒有處理逾時和卡片移開。** 畫面會卡住，使用者看不到錯誤。
- **沒有 NFC 的裝置無路可走。** 要有替代方式和清楚的說明。
- **儲存或記錄卡片密碼、證件影像，或把影像放進分析和日誌。** 這些是高敏感個資，不能離開 App 沙盒。
- **證件影像存進相簿。** 其他 App 讀得到，也可能進入雲端備份。
- **把審查結果存在本機旗標，用本機狀態決定功能開放。** 本機的資料可能過期或被竄改，要以伺服器的帳號狀態為準。
- **對被退回的使用者顯示內部審查細節。** 只呈現使用者能採取行動的部分。
- **同意沒有版本和時間紀錄。** 規約更新後，無法證明使用者同意了哪一版。
- **在面試中斷言法規細節。** 沒有逐項確認過的部分，要說明「以法務確認為準」。

## 面試官想聽到的

1. 能說出ホ、ヘ、ワ的差異與對應的技術，以及法規變動時的設計：驗證方式由伺服器下發，不寫死。
2. NFC 讀卡在兩個平台的前置設定與限制、失敗與逾時的處理，以及沒有 NFC 的裝置的替代路徑。
3. 個資處理：不存相簿、不進日誌、上傳後刪除、畫面保護。
4. 審查是非同步的，由伺服器管理狀態，用推播加 deep link 回到結果頁。
5. 知道同意書版本紀錄、風險揭露、旅行規則這些合規要求會影響 App 的畫面和流程，同時清楚哪些法規細節要交給法務確認。

> [!FIELD]
> 可以連結的經驗：
> - 在一個藍牙智慧鎖 SDK 的專案裡，Android 版另外支援 NFC。マイナンバーカード是 ISO 14443 Type B，用 `IsoDep` 和 APDU，協定不同，但偵測卡片、連線、收發資料、處理錯誤的流程相通，經驗可以延伸。
> - 同一個專案的藍牙（BLE，Bluetooth Low Energy）通訊，是依廠商規格實作的「請求與回應」協定，一次一個指令、等回應再送下一個，和 APDU 讀卡的模式相同。到交易所的 App，我會替每個指令加上逐命令的逾時與重試。
> - 在一個社交 App 裡做過越獄偵測，以及 root 和 Play Integrity 的裝置檢查，和 eKYC 周邊的裝置完整性概念相近；同一個 App 也用自建的字串管理系統統一英、日、繁中三種語系，法律文字的多語系與版本管理可以延伸。
> 面試時可以這樣說：「我在一個藍牙智慧鎖專案做過 Android 的 NFC 和藍牙的請求回應協定。マイナンバーカード是 ISO 14443 Type B，協定不同，但流程相通，我會從 iOS 的 NFC 能力設定和 AID 宣告、Android 的 IsoDep 與執行緒開始，並讓驗證方式由伺服器下發。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| NFC 能力檢查 | `NFCTagReaderSession.readingAvailable` | `NfcAdapter.getDefaultAdapter(ctx)` 與 `isEnabled`；關閉時導向 `Settings.ACTION_NFC_SETTINGS` |
| 事先設定 | NFC Tag Reading 能力、`NFCReaderUsageDescription`、AID 清單 | `android.permission.NFC`、`uses-feature` 的 `required` 設為 `false` |
| 讀卡 | `NFCISO7816Tag.sendCommand` | `IsoDep.transceive`（背景執行緒） |
| 相機 | `AVCaptureSession`、`NSCameraUsageDescription` | CameraX、`CAMERA` 權限 |
| 檔案保護 | `FileProtectionType.complete` | App 私有的內部儲存，用完就刪 |
| 畫面保護 | `UITraitCollection.sceneCaptureState`（iOS 17 起）、多工畫面遮罩 | `FLAG_SECURE` |
| 裝置完整性 | App Attest、越獄偵測 | Play Integrity、root 偵測 |
| 審查結果通知 | APNs 加 Universal Link | FCM 加 App Links |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 本人確認 | identity verification | 本人確認（取引時確認） |
| 犯罪收益移轉防止法 | Act on Prevention of Transfer of Criminal Proceeds | 犯罪による収益の移転防止に関する法律（犯収法） |
| 線上身分驗證 | eKYC | 電子的本人確認（eKYC） |
| 個人編號卡 | My Number Card | マイナンバーカード |
| 公的個人認證 | JPKI（Japanese Public Key Infrastructure） | 公的個人認証サービス |
| 旅行規則 | travel rule | トラベルルール |
| 活體偵測 | liveness detection | ライブネス検知 |
| 個人可識別資訊 | PII（personally identifiable information） | 個人情報 |
