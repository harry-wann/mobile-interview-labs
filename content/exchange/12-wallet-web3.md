---
id: exchange-wallet-web3
title: 錢包與 Web3：錢包的種類、地址驗證、memo／destination tag、QR 付款連結（BIP21）、出金流程、WalletConnect 簽名
summary: 交易所 App 的「錢包」大多是託管型：私鑰在伺服器，App 負責顯示入金地址、收集出金資料、送出申請。真正會出事的是細節：地址驗證、漏填 memo、選錯網路、重複送出。這個單元說明錢包的三種型態、地址格式與檢查碼、BIP21 付款連結、出金的可靠性，以及用 WalletConnect 連接外部錢包時，簽名訊息的標準做法（Sign-In with Ethereum，簡稱 SIWE）。
priority: P1
day: 4
tags: [wallet, address, base58check, bip21, memo, network, withdraw, walletconnect, siwe]
---

## 重點講解

這個單元回答七個面試常見問題：

1. 交易所 App 裡的「錢包」是哪一種？託管、自己保管私鑰、連接外部錢包有什麼差別？
2. 加密資產的地址怎麼驗證？Base58Check、Bech32、EIP-55（EIP 是 Ethereum Improvement Proposal，以太坊改進提案）是什麼？
3. memo／destination tag 和網路選擇，為什麼最容易出事？
4. 掃描 QR 得到的付款連結（BIP21）要怎麼解析？
5. 出金申請的 API（Application Programming Interface，伺服器提供給 App 呼叫的介面）長什麼樣子？送出逾時時為什麼不能直接重送？
6. 出金流程要做哪些防錯？
7. 用 WalletConnect 連接外部錢包時，簽名要怎麼設計？

### 1. 交易所 App 裡的「錢包」是哪一種？

**結論：交易所的出入金是託管型（custodial）：私鑰和簽署交易都在伺服器端，App 裡沒有私鑰。這和「自己保管私鑰的錢包 App」是完全不同的問題，也和「連接使用者自己的外部錢包」不同。面試時不要把三種混在一起說。**

先講兩個名詞。**私鑰**（private key）是能動用某個地址裡資產的秘密，誰拿到私鑰，誰就能把資產轉走。**助記詞**（mnemonic phrase）是 12 或 24 個英文單字，可以還原出私鑰，自己保管私鑰的錢包會要求使用者備份它。

| 種類 | 私鑰在哪裡 | App 要做什麼 |
|---|---|---|
| 託管型（交易所的入金與出金） | 交易所的伺服器 | 顯示入金地址與 QR code（二維條碼）、收集出金資料、送出申請、顯示狀態。**不碰私鑰，也不簽署交易** |
| 自己保管私鑰（self-custody） | 使用者的手機 | 產生金鑰、備份助記詞、簽署交易，要處理 Keychain／Keystore、Secure Enclave 的限制。難度和責任都高很多 |
| 連接外部錢包（WalletConnect） | 使用者自己的外部錢包 App | App 請外部錢包簽名，自己不碰私鑰。例如 NFT（Non-Fungible Token，非同質化代幣）、RWA（Real World Asset，真實世界資產代幣化）這類 Web3 功能 |

交易所在伺服器端怎麼保管客戶的資產，也有法規要求：客戶資產要和公司資產分開，而且大部分要放在冷錢包（cold wallet，私鑰不連網路保管；相對的是連網路、方便出金的熱錢包 hot wallet）。常被引用的比例是 95%，但各國與各業者的規定不同，細節以法務確認為準。這些都在伺服器端，App 不用處理。部分交易所也會做 Web3 或 RWA（real-world assets，把現實世界的資產做成代幣）相關的業務，是否用得到依職缺而定，不要預設一定會考。

```swift
// 託管型：App 只持有「顯示用」的資料，沒有任何私鑰
struct DepositInfo {
    // 資產，例如 "xrp"
    let asset: String
    // 網路，例如 "XRP Ledger"
    let network: String
    // 伺服器給的入金地址
    let address: String
    // 需要時才有，例如 XRP 的 Destination Tag
    let memo: String?
}
```

> [!TIP]
> 面試時可以這樣說：「交易所的出入金是託管型，私鑰在伺服器，App 沒有私鑰，也不用做交易簽署，責任在防止使用者輸入錯誤，以及讓送出可靠。自己保管私鑰的錢包是另一個問題，要處理助記詞和 Keychain、Keystore；用 WalletConnect 連接外部錢包則是請使用者的錢包簽名，App 自己不碰私鑰。」

### 2. 加密資產的地址怎麼驗證？Base58Check、Bech32、EIP-55 是什麼？

**結論：地址裡有校驗碼（checksum：從地址其他部分算出來的一小段驗證資料），可以擋掉打錯字，但不能證明收款人正確，也分辨不出是哪一條鏈。App 做即時的格式與校驗碼檢查，伺服器一定要再驗一次。**

| 類型 | 格式 | 校驗方式 |
|---|---|---|
| Bitcoin 傳統地址（P2PKH 是 Pay-to-Public-Key-Hash，P2SH 是 Pay-to-Script-Hash） | Base58Check，開頭是 `1` 或 `3` | 版本位元組、內容、再加 4 個位元組的校驗碼 |
| Bitcoin SegWit、Taproot 地址 | Bech32／Bech32m，開頭是 `bc1` | 多項式校驗碼；全小寫或全大寫都合法，**大小寫混用則無效** |
| Ethereum 與 EVM（Ethereum Virtual Machine，以太坊虛擬機）相容的鏈 | `0x` 加 40 位十六進位 | EIP-55：用大小寫混合編碼校驗碼；全小寫或全大寫代表**沒有**校驗碼 |
| XRP | Base58Check 的變體（開頭是 `r`，字母表不同） | 校驗碼；Destination Tag 另外處理 |

**Base58Check** 是比特幣用的格式。Base58 是用 58 個字元表示資料的編碼，字母表拿掉容易看錯的 `0`、`O`、`I`、`l`。解碼的步驟：

1. 把字串當成 58 進位的大整數，轉成位元組。
2. **開頭每個 `1` 代表一個 `0x00` 位元組**。轉成大整數時開頭的 0 會消失，要自己補回來，這是最常見的錯誤。
3. 第一個位元組是版本，最後 4 個位元組是校驗碼，中間是內容。
4. 校驗碼 ＝ 前面所有位元組做兩次 SHA-256（Secure Hash Algorithm 256，一種雜湊演算法），取前 4 個位元組。算出來和最後 4 個位元組一致才有效。
5. 版本位元組可能大於 127。Kotlin 的 `Byte` 有正負號，要先 `and 0xFF` 才不會變負數。

```kotlin
import java.math.BigInteger
import java.security.MessageDigest

const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

fun isValidBase58Check(s: String): Boolean {
    // 1. 把字串當成 58 進位的大整數
    var n = BigInteger.ZERO
    for (c in s) {
        val digit = B58.indexOf(c)
        // 0、O、I、l 不在字母表裡
        if (digit < 0) {
            return false
        }
        n = n * BigInteger.valueOf(58) + BigInteger.valueOf(digit.toLong())
    }
    // 2. 大整數轉位元組（toByteArray 可能多一個符號用的 0x00，要去掉）
    var magnitude = if (n.signum() == 0) ByteArray(0) else n.toByteArray()
    if (magnitude.isNotEmpty() && magnitude[0] == 0.toByte()) {
        magnitude = magnitude.copyOfRange(1, magnitude.size)
    }
    // 3. 開頭每個 '1' 代表一個 0x00 位元組，轉成大整數時會消失，要補回
    val bytes = ByteArray(s.takeWhile { it == '1' }.length) + magnitude
    // 至少要有 1 個版本位元組加 4 個校驗位元組
    if (bytes.size < 5) {
        return false
    }
    // 4. 校驗碼 ＝ 前面所有位元組做兩次 SHA-256 的前 4 個位元組
    val sha = MessageDigest.getInstance("SHA-256")
    val checksum = sha.digest(sha.digest(bytes.copyOfRange(0, bytes.size - 4)))
        .copyOfRange(0, 4)
    return checksum.contentEquals(bytes.copyOfRange(bytes.size - 4, bytes.size))
}

// isValidBase58Check("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")  輸出：true（比特幣創世區塊的地址）
// isValidBase58Check("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb")  輸出：false（最後一個字元改過，校驗碼不符）
// isValidBase58Check("0A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")  輸出：false（0 不在字母表裡）
```

**Bech32** 是新格式（SegWit 是 Segregated Witness，比特幣的一次升級），開頭是 `bc1`，用多項式算出的校驗碼，專門針對人工抄寫時常見的輸入錯誤設計。Bech32m 是它的修正版，Taproot 地址用這個。它不是 Base58Check，不能用同一套解碼。

**EIP-55** 是 Ethereum 地址的校驗方式。EIP 是 Ethereum Improvement Proposal 的縮寫（以太坊改進提案）。做法是對小寫的地址算雜湊，再依雜湊值決定每個字母要大寫還是小寫，所以大小寫混合的 `0x` 地址就帶著校驗碼；全小寫的地址格式合法，但沒有校驗碼。**這裡的雜湊要用 Keccak-256，不是 NIST（美國國家標準與技術研究院）標準的 SHA3-256**，兩者的填充規則不同，輸出也不同：

```text
SHA3-256("")     開頭是 a7ffc6f8…（JDK 的 MessageDigest.getInstance("SHA3-256")）
Keccak-256("")   開頭是 c5d24601…（Ethereum 用的）
```

JDK（Java Development Kit，Java 開發套件）內建的 `SHA3-256` 和 Apple 的 CryptoKit 都不是 Keccak-256，要用第三方函式庫（例如 Bouncy Castle）。

**格式正確不等於正確的收款人，也不等於正確的網路。** 同一個 `0x` 地址在 Ethereum、BSC（BNB Smart Chain）、Polygon 這些 EVM 鏈上格式都合法，**用格式分不出網路**。所以驗證要分層：App 做即時的格式與校驗碼檢查（快速回饋）；伺服器一定要再驗（這套交易所 API 的說明文件列有 `ad-ethereum-address` 錯誤碼，代表伺服器也會驗地址）；是否在白名單、是否為已知的合約或交易所地址這類語意檢查，由伺服器做。

> [!TIP]
> 面試時可以這樣說：「地址驗證我會分層。App 端做格式和校驗碼，比特幣的 Base58Check 是雙 SHA-256 的前 4 個位元組，要注意開頭的 1 代表 0x00；Ethereum 的 EIP-55 要用 Keccak-256，不是 SHA3-256。這只能擋手誤，同一個 0x 地址在各條 EVM 鏈都合法，分不出網路，所以網路要讓使用者明確選擇，伺服器也一定要再驗。」

### 3. memo／destination tag 和網路選擇，為什麼最容易出事？

**結論：有些網路（XRP、XLM、EOS 等）入金時要附加一個識別碼，沒帶的話，資產會進到交易所的共用地址，無法自動歸到使用者的帳戶，只能人工處理，嚴重時追不回來。另一個高風險是選錯網路。App 的原則是：tag 當字串處理、依網路驗證；網路不設預設值。**

**memo／tag 的處理**。XRP 的 Destination Tag 是 32 位元的無號整數，範圍是 0 到 4294967295。這套 API 的文件裡，出金申請的 `addr-tag` 是選填的字串，出入金記錄的 `address-tag` 也是字串。常見的錯誤是把 tag 轉成 `Int` 存起來：Android 的 `Int` 是 32 位元有號整數，範圍只到 2147483647，超過就出問題，而且 `toLong().toInt()` 這種強制轉換**不會丟例外，會悄悄變成別的數字**：

```kotlin
// 合法的 XRP Destination Tag（沒有超過 4294967295）
val tag = "3000000000"
// 輸出：null（超過 Int 的範圍）
println(tag.toIntOrNull())
// 輸出：-1294967296（悄悄溢位，tag 變成另一個數字，錢就入到錯的地方）
println(tag.toLong().toInt())
// 輸出：3000000000
println(tag.toLongOrNull())

// 正確做法：保持字串，依網路驗證格式與範圍
fun isValidXrpTag(tag: String): Boolean {
    if (tag.isEmpty() || !tag.all { it in '0'..'9' }) {
        return false
    }
    val value = tag.toLongOrNull() ?: return false
    return value <= 4294967295L
}
```

畫面規則：

- 該網路**必須**填 tag 時，不填就不能送出；可以不填時，要有明確的「我確認不需要」選項，不要自動補 0（tag `0` 本身也是合法值，補 0 會改變意義）。
- tag 保持字串。有些網路的 memo 是文字，開頭的 0 要保留。

**網路（chain）選擇**。同一個資產可能存在於多條鏈上，EVM 系的地址格式又完全相同，所以選錯網路是最常見、也最昂貴的錯誤。做法：

- 網路**不要有預設值**，強制使用者明確選擇，不要讓他「順手按下去」。
- 地址欄和確認頁都顯示網路名稱。
- 地址格式和網路明顯不相容時直接擋下，例如 Tron 的 `T…` 地址不可能是 EVM 地址。
- 顯示該網路的最小入金額、所需的確認數、預計到帳時間和警語。
- 地址簿的每個項目要記錄**資產、網路、地址、tag** 四樣，不要只存地址。

這套 API 的文件裡，出金申請沒有網路（chain）參數，多條鏈的資產怎麼指定網路，文件沒有說明（未證實），是面試時值得詢問的點。

> [!TIP]
> 面試時可以這樣說：「tag 和 memo 我會保持字串，依網路驗證範圍，XRP 的 Destination Tag 最大是 4294967295，轉成 Android 的 Int 會溢位。網路不設預設值，由使用者明確選擇，地址欄和確認頁都顯示網路名稱，地址簿用資產、網路、地址、tag 一起儲存。」

### 4. 掃描 QR 得到的付款連結（BIP21）要怎麼解析？

**結論：BIP21 是比特幣付款連結的格式，掃描或貼上的內容一律視為不可信的輸入：嚴格解析，只把值填進欄位，再走一次完整驗證，不要自動送出。解析時有幾個容易寫錯的規則，特別是 `+` 不是空白。**

BIP 是 Bitcoin Improvement Proposal 的縮寫（比特幣改進提案），BIP21 是第 21 號，定義付款 URI（Uniform Resource Identifier，用網址格式表示付款資訊）：

```text
bitcoin:175tWpb8K1S7NmH4Zx6rewF9WQrcZv245W?amount=20.3&label=Luke-Jr&message=Donation%20for%20project%20xyz
```

規則：

- scheme（`bitcoin:`）不分大小寫；地址緊接在冒號後面，到 `?` 為止。
- `amount` 的單位是 BTC（不是 satoshi，satoshi 是 BTC 的一億分之一），是十進位數字，小數點用 `.`，**不能有指數、千分位**。
- 參數值用百分比編碼（percent-encoding，規格是 RFC 3986；RFC 是 Request for Comments 的縮寫，網路標準文件）：`%XX` 代表一個位元組，中文等字元是 UTF-8 的位元組。**`+` 在這裡是字面的加號，不是空白**。這和網頁表單（`application/x-www-form-urlencoded`）的規則不同，所以 Java 的 `URLDecoder` 會解錯。
- 不認識的參數可以忽略；但名稱以 `req-` 開頭的參數代表「必須理解」，不理解就要**拒絕整個 URI**。
- 同一個參數出現兩次是有歧義的，保守的做法是拒絕。

```kotlin
// 輸出：a b（+ 被當成空白了，但 BIP21 要的是 a+b）
println(java.net.URLDecoder.decode("a+b", "UTF-8"))
// 所以百分比解碼要自己寫：逐個位元組掃描，遇到 % 就讀兩個十六進位字元；
// 解碼後要檢查是不是合法的 UTF-8
```

| 範例 | 結果 |
|---|---|
| `bitcoin:175tW…?amount=20.3&label=Luke-Jr` | 有效 |
| `…?message=a+b` | 有效，`message` 是 `a+b` |
| `…?amount=1e3` | 無效（不能用指數） |
| `…?amount=1&req-pay=x` | 無效（有不認識的 `req-` 參數） |
| `…?amount=1&amount=2` | 無效（重複） |

這個單元的程式題採用比 BIP21 文法更嚴格的自訂規則：BIP21 的文法其實允許 `.5`、`1.` 這類寫法，也沒有 8 位小數的上限，但題目為了防呆一律拒絕。產品上要不要這麼嚴格，要另外決定。

**掃描與貼上的處理**：

- 掃到的內容是不可信輸入。只填入欄位，再走一次完整驗證，不自動送出；QR 帶的 `amount` 也要重新驗證，並且用 `Decimal`／`BigDecimal` 處理，不要用 Double。
- 相機：iOS 用 `AVCaptureMetadataOutput`，Android 用 ML Kit 的條碼掃描搭配 CameraX；相機權限被拒絕時，要說明用途，並提供手動輸入的路徑。
- 以太坊系有 EIP-681 的 URI 格式（含 chain id 與合約呼叫），細節以規格為準。

> [!TIP]
> 面試時可以這樣說：「掃到的 QR 是不可信輸入。我會自己寫嚴格的解析：scheme 不分大小寫、百分比解碼時 + 是加號不是空白、amount 不接受指數、拒絕 req- 參數和重複的參數。解析完只填入欄位，重新驗證，不自動送出。」

### 5. 出金申請的 API 長什麼樣子？送出逾時時為什麼不能直接重送？

**結論：這套交易所 API 的出金申請沒有冪等鍵（idempotency key：讓同一個請求送多次，結果和送一次一樣的識別碼）。送出後逾時，只代表沒收到回應，伺服器可能已經建立了出金。這時不能重送，要先用出入金記錄查詢是否已經建立，再決定。**

這套 API 的文件有出入金相關的端點。範例中的 API 主機是 `https://api.example-exchange.com`，下表的端點都需要簽名：

| 項目 | 內容 |
|---|---|
| 出金申請 | `POST /v1/dw/withdraw/api/create`。參數 `address`、`amount`、`currency`、`fee`（都是必填的字串）、`addr-tag`（選填的字串）。回傳的 `data` 是出金記錄的識別碼（id） |
| 出金取消 | `POST /v1/dw/withdraw-virtual/{withdraw-id}/cancel` |
| 出入金記錄 | `GET /v1/query/deposit-withdraw`，查詢參數 `currency`、`type`（`deposit` 或 `withdraw`）、`from`、`size`。回傳的欄位有 `id`、`type`、`currency`、`tx-hash`、`amount`、`address`、`address-tag`、`fee`、`state`、`created-at`、`updated-at` |

**出金狀態**（`state`）：`submitted`（已申請）、`reexamine`（審查中）、`pass`（核准）、`reject`（拒絕）、`pre-transfer`（處理中）、`wallet-transfer`（已送出）、`wallet-reject`（錢包拒絕）、`confirmed`（區塊鏈上已確認）、`confirm-error`（區塊鏈上確認錯誤）、`canceled`／`repealed`（已取消）。**入金狀態**：`unknown`、`confirming`、`confirmed`、`safe`（文件註解為「完了」）、`orphan`。

App 要把這些狀態對應成使用者看得懂的階段，同時保留原始狀態碼給客服查詢。記錄裡的 `tx-hash` 是這筆交易在區塊鏈上的編號，可以連到區塊瀏覽器（block explorer，查詢鏈上交易的網站；每條鏈的網址由設定提供，不要寫死）。

```swift
enum WithdrawStage {
    case applying, reviewing, processing, sent, completed, failed, canceled
}

func stage(for state: String) -> WithdrawStage? {
    switch state {
    // 申請中
    case "submitted": return .applying
    // 審查中
    case "reexamine": return .reviewing
    // 處理中
    case "pass", "pre-transfer": return .processing
    // 已送出，可以顯示 tx-hash 連結
    case "wallet-transfer": return .sent
    // 完成
    case "confirmed": return .completed
    // 失敗，要有下一步指引
    case "reject", "wallet-reject", "confirm-error": return .failed
    // 已取消
    case "canceled", "repealed": return .canceled
    // 不認識的狀態，顯示原始狀態碼，不要當成失敗
    default: return nil
    }
}
```

**文件沒有說明的事**（全部未證實，可以在面試時詢問）：

- 出金申請沒有 `chain`（網路）參數，同一個資產在多條鏈上時怎麼指定網路。
- 哪些狀態可以取消。
- `fee` 是由 App 帶入的，手續費金額從哪裡取得、是否包含在 `amount` 裡。
- 入金記錄沒有確認數的欄位，「確認 3/12」這種進度條需要其他資料來源。
- 取得入金地址的端點。文件的錢包段落標示「引出しのみサポート」（只支援出金）。

另外，記錄裡的 `amount`、`fee` 在範例中是 JSON（JavaScript Object Notation，一種資料交換格式）的數字（如 `7.457467`），不是字串。用 Double 解碼再轉成 Decimal 會有誤差，要用字串或能保留精度的解析方式（見「精度與金額」單元）。

**逾時怎麼辦？** 送出時，先鎖住按鈕，顯示「處理中」。如果逾時，不要重送：用出入金記錄的端點（`type=withdraw`）查詢是否已經建立（比對地址、數量、`addr-tag`、時間）。找到就追蹤那一筆；找不到，也不代表一定沒成功（記錄可能有延遲），所以要明確告訴使用者「結果不明」，由使用者確認後再送，或聯絡客服。這和下單逾時後查詢訂單的對帳是同一套思路；下單 API 有 `client-order-id`，但出金申請沒有。

> [!TIP]
> 面試時可以這樣說：「出金申請的文件參數裡沒有冪等鍵，逾時只代表沒收到回應，伺服器可能已經建立出金，重送可能產生兩筆不可逆的轉帳。所以我會鎖住按鈕，用出入金記錄端點比對地址、數量、tag 和時間來對帳，找不到時告訴使用者結果不明，而不是假裝失敗。」

### 6. 出金流程要做哪些防錯？

**結論：出金是不可逆的轉帳，防錯要放在送出前：確認頁顯示完整資訊、完整顯示地址並分組、掃描和貼上不自動送出、新增的地址加上額外保護。**

流程的順序：

1. 選擇資產與網路（網路不設預設值）。
2. 輸入出金資料：從地址簿選擇，或輸入、掃描地址（本機即時驗證格式、校驗碼與網路相容性）；依網路需求填寫 memo／tag；輸入數量，並顯示手續費與實收金額。
3. 確認頁：顯示完整地址（分組）、網路、tag、數量、手續費與風險提示；使用者確認後，通過資金密碼或二階段驗證。
4. 鎖住送出按鈕，送出簽名的請求，取得出金記錄 id。
5. 用記錄 id 追蹤狀態，對應成使用者看得懂的階段。

網路是第一步，因為地址格式的相容性、手續費、最小額度與 tag 的需求都取決於網路。確認頁在送出之前，是使用者最後一次能發現錯誤的地方，必須顯示完整資訊。

**Address poisoning（地址投毒）**：攻擊者先向你的地址發一筆很小的交易，用的地址頭尾和你常用的地址相同，期待你下次從交易記錄裡複製到這個假地址。對策是確認頁顯示**完整的地址並分組**（例如每 4 碼一組），不要只顯示前幾碼和後幾碼；從記錄複製地址時再多一個提示。

```swift
// 把地址每 4 碼分成一組，方便使用者核對（0x 前綴獨立一組）
func grouped(_ address: String, size: Int = 4) -> String {
    let prefix = address.hasPrefix("0x") ? "0x " : ""
    let body = Array(address.dropFirst(prefix.isEmpty ? 0 : 2))
    let groups = stride(from: 0, to: body.count, by: size)
        .map { String(body[$0..<min($0 + size, body.count)]) }
    return prefix + groups.joined(separator: " ")
}

print(grouped("0xde709f2102306220921060314715629080e2fb77"))
// 輸出：0x de70 9f21 0230 6220 9210 6031 4715 6290 80e2 fb77
```

**剪貼簿**：不要為了方便，一進畫面就自動讀剪貼簿，把像地址的內容填進去。惡意軟體可以替換剪貼簿的內容；iOS 16 起，App 以程式讀取其他 App 的剪貼簿時，系統會跳出貼上的許可提示（可以改用 `UIPasteControl` 取得不用提示的貼上）；Android 12 起，讀取剪貼簿也有系統提示（細節以官方文件為準）。要讓使用者主動貼上。

**新增的出金地址**：套用冷卻期或額外驗證，是業界常見的做法，各家實際的政策不同。旅行規則（Travel Rule）從 2023 年 6 月起，要求交易所在轉帳時處理轉出方與收款方的資訊（細節以法務確認為準，見「eKYC 與法規」單元），所以登記出金地址的流程，可能需要填寫「對方是哪一家交易所」和「所有權聲明」。多數業者也都有出金地址的審查與核准流程。

> [!TIP]
> 面試時可以這樣說：「出金是不可逆的，所以防錯放在送出前。確認頁顯示完整地址並每 4 碼分組，防 address poisoning；掃描和貼上只填入欄位、重新驗證，不自動送出，也不自動讀剪貼簿；新增地址加冷卻期或額外驗證。送出時鎖住按鈕，逾時先對帳再決定。」

### 7. 用 WalletConnect 連接外部錢包時，簽名要怎麼設計？

**結論：連上錢包只告訴 App 一個地址，不等於證明使用者擁有這個地址。要證明所有權，必須請錢包簽一段訊息，由伺服器驗證簽名。簽名訊息的標準做法是 SIWE（Sign-In with Ethereum，EIP-4361），裡面有伺服器發出的一次性 nonce 和有效期限。**

WalletConnect 是讓手機錢包 App 和其他 App 或網站連線的協定。這個單元以「App 連接使用者自己的外部錢包」為情境，私鑰全程在使用者的錢包裡。流程：

1. **配對**：App 顯示 URI（QR code 或 deep link；deep link 是點了就開到指定 App 的連結），使用者的錢包掃描或點開。
2. **建立 session**：雙方約定可以使用的鏈、方法（例如 `personal_sign`，簽署一段文字訊息）。
3. **請求**：App 送出簽名請求，錢包顯示給使用者確認。
4. **收到簽名**：使用者同意後，錢包回傳簽名。
5. **伺服器驗證**：App 把地址、訊息、簽名送給伺服器，伺服器用簽名還原出簽署者的地址（`ecrecover`），比對是否等於使用者宣稱的地址。

**簽名訊息的設計**：訊息如果每次都一樣，簽名就可以被別人重複使用。標準做法是 SIWE：訊息包含網域（domain）、地址、說明（statement）、URI、版本、chain id、**伺服器發出的一次性 nonce**、發出時間與到期時間。伺服器驗證：nonce 只能用一次且是自己發的、時間沒有過期、網域符合。

```swift
func siweMessage(
    domain: String,
    address: String,
    statement: String,
    uri: String,
    chainId: Int,
    nonce: String,
    issuedAt: String,
    expiration: String
) -> String {
    """
    \(domain) wants you to sign in with your Ethereum account:
    \(address)

    \(statement)

    URI: \(uri)
    Version: 1
    Chain ID: \(chainId)
    Nonce: \(nonce)
    Issued At: \(issuedAt)
    Expiration Time: \(expiration)
    """
}

// nonce 由伺服器產生並記錄，只能使用一次；範例輸出的第一行是：
// exchange.example.com wants you to sign in with your Ethereum account:
```

nonce 必須由**伺服器**發出。如果由 App 自己產生，攻擊者可以自己挑一個值，伺服器也沒有任何狀態可以比對，等於沒有防重放。

其他要處理的情況：

- 智慧合約錢包（EIP-1271）沒有單一的私鑰簽名可以還原，不能用 `ecrecover`，要呼叫合約的 `isValidSignature`。
- 使用者拒絕簽名（EIP-1193 的錯誤碼 4001）是正常流程，畫面要回到可以重試的狀態，不要顯示成錯誤。
- 換鏈、斷線事件要處理；錢包返回 App 的 deep link，要把 session 的結果交回（Universal Link 比自訂 scheme 不容易被其他 App 劫持）；session 要能保存和清除。
- **不要在 App 儲存、也不要索取使用者的私鑰或助記詞。**

> [!TIP]
> 面試時可以這樣說：「連上錢包不等於證明擁有地址，要請錢包簽訊息，伺服器還原簽署者地址再比對。簽名訊息我會用 SIWE 的格式，包含網域、chain id 和伺服器發出的一次性 nonce 與到期時間，伺服器驗證 nonce 只用一次、沒過期、網域正確。智慧合約錢包要用 EIP-1271。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange12Wallet**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange12Wallet**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange12-Wallet/`，解答在 `labs/ios/Solution/Exchange/Exchange12-Wallet/`；Android 你寫在 `labs/android/practice/exchange/exchange12-wallet/`，解答在 `labs/android/solution/exchange/exchange12-wallet/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也沒有任何真的地址或金鑰**：地址都是公開的測試地址（BIP 文件與比特幣創世區塊的地址），伺服器、出金記錄、網路都是程式裡的假物件。Base58 的大整數轉位元組、Bech32 的校驗碼（polymod）、BIP21 的連結切分已經寫好在 `Exchange12Support`，每個演練只練那一個容易寫錯的地方。

### 演練 1：合法的比特幣地址被判定無效（第 2 題）

**檔案**：`L01_Base58Check.swift`（iOS）、`L01_Base58Check.kt`（Android）

**情境**：比特幣傳統地址是 Base58Check：把字串當成 58 進位的大整數轉成位元組，第一個位元組是版本、最後 4 個位元組是校驗碼，校驗碼是前面所有位元組做兩次 SHA-256 的前 4 個位元組。開頭每個 `1` 代表一個 `0x00` 位元組，轉成大整數時開頭的 0 會消失，要自己補回來。大整數轉位元組與校驗碼已經寫好，演練檢查 5 個地址：比特幣創世區塊的地址 `1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa`、另一個以 `1` 開頭的合法地址、以 `3` 開頭的合法地址（P2SH）、最後一個字元被改掉的地址、含有 `0`（不在字母表）的地址。

**怎麼寫**

1. **Bug 版**：直接拿大整數轉出來的位元組算校驗碼，沒有補回開頭的 `0x00`。
2. **修正版**：數出字串開頭連續有幾個 `1`，在位元組前面補同樣數量的 `0x00`，再檢查長度與校驗碼。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 兩個以 `1` 開頭的合法地址都被判成「無效」，以 `3` 開頭的剛好判對（它沒有開頭的 `1`），被改過的、含 `0` 的本來就無效所以也對：「5 個地址，驗證結果錯的有 2 個」 |
| 修正版 | 5 個地址都判斷對：「5 個地址，驗證結果錯的有 0 個」 |

**為什麼**：版本位元組 `0x00` 在大整數裡是最高位的 0，轉成位元組時會被丟掉，少了版本位元組，校驗碼就是用錯的資料算的。這個 bug 很容易漏掉：以 `3` 開頭的 P2SH 地址、很多隨手拿來測的地址都不會中，只有以 `1` 開頭的地址（傳統的 P2PKH）才會。Android 一側還有兩個相關的陷阱：`Byte` 有正負號（版本位元組超過 127 要先 `and 0xFF`），`BigInteger.toByteArray()` 可能多一個符號用的 `0x00`；這兩個在共用檔案裡已經處理好。校驗碼只能擋手誤，不能證明是正確的收款人，也分辨不出是哪條鏈，伺服器一定要再驗一次。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L01_Base58Check.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L01_Base58Check.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange12-wallet/L01_Base58Check.kt#bug
```

```lab-solution
android/solution/exchange/exchange12-wallet/L01_Base58Check.kt#fixed
```

### 演練 2：大小寫混用的 bc1 地址被當成合法（第 2 題）

**檔案**：`L02_Bech32Case.swift`（iOS）、`L02_Bech32Case.kt`（Android）

**情境**：Bech32（SegWit）與 Bech32m（Taproot）地址以 `bc1` 開頭，有多項式校驗碼。規則：全小寫或全大寫都合法（QR code 常用全大寫），大小寫混用則無效。校驗碼的計算（同時支援 Bech32 與 Bech32m）已經寫好，這題只看「大小寫」。演練檢查 6 個地址（BIP 173／350 文件的公開測試地址）：全小寫的合法地址、全大寫的同一個地址、Taproot 的 Bech32m 地址、第一個字母改成大寫、中間一個字母改成大寫、校驗碼被改掉的地址。

**怎麼寫**

1. **Bug 版**：先轉成小寫再驗證校驗碼（iOS 用 `lowercased()`，Android 用 `lowercase()`），結果不是空就合法。
2. **修正版**：先檢查地址等於它的全小寫版本或全大寫版本，混用就直接無效；通過後才轉小寫驗證校驗碼。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「第一個字母改成大寫：合法 ✗」「中間一個字母改成大寫：合法 ✗」，其他四個判斷對：「6 個地址，驗證結果錯的有 2 個」 |
| 修正版 | 6 個地址都判斷對（全大寫與 Taproot 都合法）：「6 個地址，驗證結果錯的有 0 個」 |

**為什麼**：「大小寫都接受」聽起來很周到，轉成小寫之後混用的地址校驗碼也是對的，所以不會被擋下來。但規格明確禁止混用，原因是混用的字串多半是人為抄寫或複製出錯的結果。這個檢查很便宜，不做的話混用的地址就會被當成合法。EIP-55 的 Ethereum 地址剛好相反：大小寫混合就是校驗碼，全小寫反而代表「沒有校驗碼」，兩種地址不能共用同一套大小寫邏輯。這個演練只做 Base58Check 與 Bech32；EIP-55 需要 Keccak-256（CryptoKit 和 JDK 都沒有，要第三方函式庫），沒有演練。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L02_Bech32Case.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L02_Bech32Case.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange12-wallet/L02_Bech32Case.kt#bug
```

```lab-solution
android/solution/exchange/exchange12-wallet/L02_Bech32Case.kt#fixed
```

### 演練 3：入金的 Destination Tag 悄悄變成別的數字（第 3 題）

**檔案**：`L03_TagOverflow.swift`（iOS）、`L03_TagOverflow.kt`（Android）

**情境**：XRP 的 Destination Tag 是 32 位元的無號整數，範圍 0 到 4294967295；Stellar 的 memo 可以是文字，開頭的 0 要保留。API 的 `addr-tag` 是字串。App 把使用者輸入的 tag 整理成「要送出的字串」，不合法的回傳 `nil`（`null`）。演練檢查 7 筆輸入：XRP 的 123456、3000000000、4294967295、4294967296（超出範圍）、0、12a（不是數字），以及 Stellar 的文字 memo 007。

**怎麼寫**

1. **Bug 版**：tag 轉成 32 位元整數再轉回字串：Android 是 `(text.toLongOrNull() ?: 0L).toInt().toString()`；iOS 的 `Int` 是 64 位元，所以用 `Int32(truncatingIfNeeded: Int64(text) ?? 0)` 模擬有些 SDK 與伺服器模型用的 32 位元整數。
2. **修正版**：tag 保持字串：XRP 檢查「只有數字、不超過 4294967295」，通過就原樣送出；Stellar 的 memo 是文字，原樣保留。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `"3000000000"` 變成 `"-1294967296"`，`"4294967295"` 變成 `"-1"`，超出範圍的 `"4294967296"` 變成 `"0"`，`"12a"` 變成 `"0"`，memo `"007"` 變成 `"7"`；123456 與 0 剛好對：「共 7 筆輸入，結果錯的有 5 筆」 |
| 修正版 | 7 筆都和正確答案一致（超出範圍與不是數字的回傳「不合法」，memo 007 原樣保留）：「共 7 筆輸入，結果錯的有 0 筆」 |

**為什麼**：`toLong().toInt()` 與 `Int32(truncatingIfNeeded:)` 都不會丟例外，超出範圍的數字會悄悄變成別的數字，使用者按下送出，資產就入到錯的帳戶或交易所的共用地址，只能人工處理。「解析失敗當成 0」也是同一類錯誤。解法是不要把 tag 當成數字：保持字串、依網路驗證格式與範圍。需要數字比較範圍時，用 `Long`／`UInt64`，比完仍然送出原本的字串。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L03_TagOverflow.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L03_TagOverflow.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange12-wallet/L03_TagOverflow.kt#bug
```

```lab-solution
android/solution/exchange/exchange12-wallet/L03_TagOverflow.kt#fixed
```

### 演練 4：沒選網路也能出金，XRP 沒填 tag 被補成 0（第 3 題）

**檔案**：`L04_TagRequired.swift`（iOS）、`L04_TagRequired.kt`（Android）

**情境**：出金表單有兩個容易出事的欄位：網路（同一個資產可能在多條鏈上，選錯就送到錯的鏈）和 tag（XRP 這類網路入金要帶 tag，漏帶資產會進交易所的共用地址）。規則：網路不設預設值，沒選就不能送出；該網路必填 tag 時，沒填就不能送出，除非使用者明確勾選「我確認不需要 tag」；勾選後送出時 tag 是空的，不能自動補 0（tag 0 本身也是合法的值）；不需要 tag 的網路，tag 也是空的。五種表單：沒選網路、XRP 沒填 tag 也沒勾「不需要」、XRP 沒填 tag 但勾了「不需要」、XRP 填了 5555、Bitcoin。

**怎麼寫**

1. **Bug 版**：`prepare` 把沒選的網路預設成第一個（Bitcoin），tag 空白就自動補 `"0"`，所有網路都帶 tag。
2. **修正版**：沒選網路 → 問題「請選擇網路」；必填 tag 的網路沒填也沒勾「不需要」→ 問題「缺少 tag」；否則通過，tag 去掉空白後是空的就是 `nil`（`null`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 沒選網路、XRP 沒填 tag、XRP 勾了「不需要」都變成「可以送出」並帶 tag `"0"`，連 Bitcoin 也帶 tag `"0"`，只有填了 5555 的是對的：「5 種表單，整理結果錯的有 4 種」 |
| 修正版 | 5 種表單都符合規則：沒選網路、XRP 缺 tag 被擋下，勾了「不需要」的 tag 是「沒有」，不是 `"0"`：「5 種表單，整理結果錯的有 0 種」 |

**為什麼**：「方便使用者」的預設值和自動補值，在出金這種不可逆的操作裡都是危險的。網路有預設值，使用者沒注意就選錯；必填的 tag 被補成 0，伺服器與交易所收到的是一個「看起來有填」的 tag，而 0 本身又是合法的值，沒有人會警覺。正確的做法是強制使用者明確選擇網路、明確確認「不需要 tag」，地址欄與確認頁都顯示網路名稱，地址簿的每個項目要記錄資產、網路、地址、tag 四樣。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L04_TagRequired.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L04_TagRequired.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange12-wallet/L04_TagRequired.kt#bug
```

```lab-solution
android/solution/exchange/exchange12-wallet/L04_TagRequired.kt#fixed
```

### 演練 5：付款連結的備註 a+b 變成 a b（第 4 題）

**檔案**：`L05_Bip21Decode.swift`（iOS）、`L05_Bip21Decode.kt`（Android）

**情境**：BIP21 的參數值用百分比編碼（RFC 3986）：`%XX` 是一個位元組，中文等字元是 UTF-8 的位元組；`+` 在這裡是字面的加號，不是空白（網頁表單的規則才是 `+` 當空白）。解碼後必須是合法的 UTF-8，不完整的 `%` 序列要視為無效。連結的切分已經寫好，這題只寫「單一個參數值怎麼解碼」。演練檢查 7 個值：`Luke-Jr`、`Donation%20for%20project%20xyz`、`a+b`、`%E5%92%96%E5%95%A1`（咖啡）、`%E5%92`（UTF-8 只有一半）、`100%`、`%zz`。

**怎麼寫**

1. **Bug 版**：用表單的規則解碼。iOS：先把 `+` 換成空白，再用 `removingPercentEncoding`；Android：`URLDecoder.decode(raw, "UTF-8")`（例外當成無效）。
2. **修正版**：自己逐個位元組掃描：遇到 `%` 就讀兩個十六進位字元還原成位元組，`+` 原樣保留；最後檢查整串是不是合法的 UTF-8（iOS 用 `String(bytes:encoding:)`，Android 用 `REPORT` 模式的 UTF-8 解碼器），不是就回傳 `nil`（`null`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：`a+b` 變成 `a b`，其他 6 個剛好對（`removingPercentEncoding` 遇到不合法的序列回傳 `nil`）：「共 7 個值，解碼結果錯的有 1 個」。Android：`a+b` 變成 `a b`，只有半個字元的 `%E5%92` 被解成替代字元 U+FFFD（畫面上是一個菱形問號，不是「無效」）：「錯的有 2 個」 |
| 修正版 | 7 個值都和正確答案一致：`a+b` 保持 `a+b`，咖啡解出中文，三個不完整的序列都是「無效」：「共 7 個值，解碼結果錯的有 0 個」 |

**為什麼**：掃到的 QR code 是不可信的輸入。把 `+` 當成空白，資料被悄悄改掉，沒有任何錯誤；半個 UTF-8 字元被解成亂碼，使用者看不出哪裡不對。平台內建的解碼函式規則各有不同（Java 的 `URLDecoder` 是表單規則，不合法的 UTF-8 不報錯；iOS 的 `removingPercentEncoding` 會報錯，但不處理 `+`），所以嚴格解析要自己寫，並且用固定的測試資料固定下來。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L05_Bip21Decode.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L05_Bip21Decode.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange12-wallet/L05_Bip21Decode.kt#bug
```

```lab-solution
android/solution/exchange/exchange12-wallet/L05_Bip21Decode.kt#fixed
```

### 演練 6：掃到 amount=1e3，欄位被填入 1000 BTC（第 4 題）

**檔案**：`L06_Bip21Rules.swift`（iOS）、`L06_Bip21Rules.kt`（Android）

**情境**：掃描或貼上的付款連結是不可信的輸入，要嚴格解析。這個單元採用比 BIP21 文法更嚴格的自訂規則：scheme（`bitcoin:`）不分大小寫；`amount` 單位是 BTC，只接受「數字，可以有最多 8 位小數」，不能有指數、正負號、千分位；名稱以 `req-` 開頭的參數代表「必須理解」，不認識就要拒絕整個連結，其他不認識的參數可以忽略；同一個參數出現兩次是有歧義的，拒絕。連結的切分已經寫好，`parse` 回傳「受理（金額）」或「拒絕」。七個連結：金額 20.3 加 label、大寫的 `BITCOIN:` 加金額 0.01、沒有 amount、`amount=1e3`、`amount=1` 加 `req-pay=x`、`amount=1&amount=2`、`amount=1` 加不認識的 `foo=bar`（要忽略）。

**怎麼寫**

1. **Bug 版**：把 query 轉成字典／Map（同名的後面蓋掉前面），略過所有不認識的參數（包含 `req-`），`amount` 直接丟給 `Decimal(string:)`／`BigDecimal(String)`。
2. **修正版**：先檢查有沒有 `req-` 開頭的參數與重複的參數（有就拒絕）；`amount` 先用正規表示式 `^[0-9]+(\.[0-9]{1,8})?$` 檢查格式，通過才轉成 `Decimal`／`BigDecimal`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「amount=1e3 → 受理，金額 1000 BTC ✗」「不認識的 req- 參數 → 受理 ✗」「amount 出現兩次 → 受理，金額 2 BTC ✗」，其他四個剛好對：「共 7 個連結，解析結果錯的有 3 個」 |
| 修正版 | 7 個連結都和正確答案一致（`1e3`、`req-`、重複的 `amount` 都是「拒絕」，不認識的 `foo=bar` 被忽略）：「共 7 個連結，解析結果錯的有 0 個」 |

**為什麼**：`Decimal(string: "1e3")` 和 `BigDecimal("1e3")` 都認得科學記號，所以不是 `nil`，也不會丟例外，直接得到 1000。它們解決的是 `Double` 的精度問題，不負責「格式驗證」，所以格式要先檢查、再轉換。`req-` 參數代表「必須理解」，忽略它就是違反規格；重複的參數取哪一個沒有定義，取後面的那個，攻擊者就能在連結後面悄悄蓋掉前面的金額。解析完只把值填進欄位，再走一次完整驗證，不要自動送出。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L06_Bip21Rules.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L06_Bip21Rules.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange12-wallet/L06_Bip21Rules.kt#bug
```

```lab-solution
android/solution/exchange/exchange12-wallet/L06_Bip21Rules.kt#fixed
```

### 演練 7：出金逾時後自動重送，帳戶被扣了兩次（第 5 題）

**檔案**：`L07_WithdrawTimeout.swift`（iOS）、`L07_WithdrawTimeout.kt`（Android）

**情境**：這套交易所 API 的出金申請沒有冪等鍵（idempotency key：讓同一個請求送多次，結果和送一次一樣的識別碼）。送出後逾時，只代表「沒收到回應」，伺服器可能已經建立了出金。正確的做法是不重送，先用出入金記錄（`type=withdraw`）查詢，比對地址、數量、tag：有，就追蹤那一筆；找不到，也不代表一定沒成功（記錄可能有延遲），要明確告訴使用者「結果不明」。演練用一台假伺服器（記憶體裡的出金記錄），兩個情境：① 請求送到了，伺服器已經建立出金，但回應遺失（App 看到逾時）；② 請求沒有送到伺服器（App 同樣看到逾時）。

**怎麼寫**

1. **Bug 版**：`handleTimeout` 再呼叫一次 `create`（逾時就重送一次，「失敗了就再試」）。
2. **修正版**：不重送：在出入金記錄裡找地址、數量、tag 都相同的一筆，找到就追蹤它，找不到回傳「結果不明」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「情境 1（伺服器已建立，回應遺失）：伺服器上有 2 筆出金」（同一筆出了兩次），「情境 2（請求沒有送到）：伺服器上有 1 筆出金」（使用者沒確認就自動送出）：「2 個情境，處理錯的有 2 個」 |
| 修正版 | 情境 1 只有 1 筆，App 追蹤出金 #1；情境 2 伺服器上 0 筆，顯示「結果不明」：「2 個情境，處理錯的有 0 個」 |

**為什麼**：App 看到逾時的時候，分辨不出「伺服器已經建立」和「請求根本沒送到」，所以任何自動重送都有可能重複轉帳，而出金是不可逆的。下單 API 有 `client-order-id` 可以安全地重送，出金申請的公開文件沒有，這是兩者最大的差別。所以流程是：送出時鎖住按鈕、顯示「處理中」；逾時先對帳；找不到時把決定權交給使用者，或請他聯絡客服。Platform05 的演練 5 練過下單重送（帶 `clientOrderId`，或先查詢再重送），這題的出金申請沒有 `clientOrderId` 可以帶，只能先對帳。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L07_WithdrawTimeout.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L07_WithdrawTimeout.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange12-wallet/L07_WithdrawTimeout.kt#bug
```

```lab-solution
android/solution/exchange/exchange12-wallet/L07_WithdrawTimeout.kt#fixed
```

### 演練 8：伺服器新增一種狀態，整份出金記錄載入失敗（第 5 題）

**檔案**：`L08_UnknownState.swift`（iOS）、`L08_UnknownState.kt`（Android）

**情境**：出入金記錄的 `state` 是字串：`submitted`、`reexamine`、`pass`、`pre-transfer`、`wallet-transfer`、`confirmed`、`reject`…。App 要把它們對應成使用者看得懂的階段（申請中、審查中、處理中、已送出、完成、失敗、已取消）。不認識的狀態，要顯示原始狀態碼，不要當成失敗，也不能讓整份記錄載入失敗。伺服器日後會新增狀態（演練用假的 `compliance-hold`），舊版 App 一定會遇到。演練的 JSON 有 3 筆記錄：`confirmed`、`compliance-hold`（不認識）、`wallet-transfer`，`parse` 要把它解碼成畫面上的每一列。Android 用 Gson 解析（不用 `org.json`：JVM 單元測試裡它是空殼）。

**怎麼寫**

1. **Bug 版**：`state` 宣告成 enum（每個已知狀態一個值）。iOS：`JSONDecoder` 遇到 enum 沒有的字串丟出 `DecodingError`，整份解碼失敗；Android：Gson 悄悄把欄位設成 `null`，取用 `state` 時 `NullPointerException`，整份載入失敗。
2. **修正版**：`state` 當成 `String` 讀進來，再用函式對應成階段，對應不到就顯示「未知狀態（原始狀態碼）」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「解析失敗：整份出金記錄載入失敗」，「畫面上顯示 0 筆出金記錄」（只有一筆不認識，三筆都不見了） |
| 修正版 | 「#101 → 完成」「#102 → 未知狀態（compliance-hold）」「#103 → 已送出」，「畫面上顯示 3 筆出金記錄」 |

**為什麼**：伺服器新增一個狀態很自然，但舊版 App 在使用者手機上沒辦法立刻更新。把 API 的字串寫成 enum，等於假設「伺服器永遠不會多給一個值」。兩個平台失敗的方式不同：Swift 的 `Decodable` 直接丟錯，Gson 靜默給 `null` 而 Kotlin 的非 `null` 型別擋不住它，在稍後才爆出來，後者更難查。共通的解法是：對外部資料保持寬容，先讀成字串，在 App 自己的對應函式裡處理「不認識」，並且保留原始狀態碼給客服查詢。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L08_UnknownState.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange12-Wallet/L08_UnknownState.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange12-wallet/L08_UnknownState.kt#bug
```

```lab-solution
android/solution/exchange/exchange12-wallet/L08_UnknownState.kt#fixed
```

第 1 題是觀念題（託管型、自己保管私鑰、連接外部錢包三種的差別），沒有演練。第 6 題的 address poisoning（假地址只比對頭尾、新地址要冷卻）在 Advanced08 的演練 5 練過；剪貼簿在 Advanced08 的演練 4 練過；確認頁的地址分組顯示是畫面排版，沒有可以自動檢查的 bug。第 7 題 WalletConnect 的簽名訊息（SIWE）的重放與 nonce，在 Platform06 的演練 5 練過。這個單元不重複。

## 常見陷阱

- **把 `addr-tag` 轉成 `Int`，或把 memo 開頭的 0 丟掉。** Android 的 `Int` 會溢位，tag 悄悄變成別的數字。保持字串，依網路驗證範圍。
- **網路有預設值。** 使用者沒注意就選錯，資產送到錯的鏈。網路要明確選擇，確認頁顯示網路名稱。
- **只驗證地址格式。** 沒有檢查網路與地址是否相容，也沒有伺服器端驗證。
- **EIP-55 用 SHA3-256 計算。** 要用 Keccak-256，兩者輸出不同。
- **Base58 解碼丟掉開頭的 `1`，或把版本位元組當有號數。** 開頭每個 `1` 是一個 `0x00` 位元組，Kotlin 要 `and 0xFF`。
- **BIP21 的 `+` 被當成空白，或沒有拒絕 `req-` 參數和重複的 `amount`。** 要自己寫百分比解碼。
- **確認頁用「前 6 後 4」縮寫顯示地址。** 會被 address poisoning 利用，要顯示完整地址並分組。
- **逾時後重送出金申請。** 出金申請沒有冪等鍵，可能重複轉帳。要先用出入金記錄對帳。
- **掃描到 QR 就自動送出，或自動讀剪貼簿。** 掃描和貼上的內容是不可信輸入，只填入欄位並重新驗證。
- **簽名訊息沒有伺服器發出的一次性 nonce 和有效期限。** 簽名可以被重複使用，要用 SIWE。
- **混淆託管型和自己保管私鑰的錢包。** 兩者是不同的問題，面試時要分清楚。

## 面試官想聽到的

1. 託管型出入金的邊界：App 沒有私鑰，責任是防止輸入錯誤和讓送出可靠。
2. 地址驗證的分層與限制，memo／tag 與網路選擇的風險，以及畫面上的對策。
3. 出金送出的可靠性：沒有冪等鍵時，如何避免重複送出並對帳。
4. 把 API 的狀態對應成使用者看得懂的階段，並給錯誤狀態下一步的指引。
5. WalletConnect 簽名的安全設計：SIWE、nonce、由伺服器驗證。

> [!FIELD]
> 與錢包最接近的經驗是在一個社交 App 整合過 WalletConnect（Reown AppKit）連接使用者的外部錢包：請錢包簽名以證明地址所有權，由後端驗證簽名並綁定帳號；也處理過從錢包 App 用 deep link 返回，以及 iOS 與 Android 各自 SDK（Software Development Kit，軟體開發套件）的連線和初始化失敗處理。同一個 App 也整合過內嵌錢包（嵌在 App 裡、不需要使用者另外安裝錢包）這類方案，並由伺服器驅動 NFT 的顯示。
> 到交易所的 App，如果要做錢包連線，簽名訊息我會依 SIWE 的標準格式設計，並讓伺服器發出 nonce。交易所的出金是託管型，我會從地址與網路的防錯、memo／tag、確認頁，以及送出後的對帳開始。
> 面試時可以這樣說：「我在一個社交 App 整合過 WalletConnect 做地址所有權證明，知道連線不等於證明，簽名訊息要有 nonce 和網域。交易所的出金是託管型，我會從地址與網路的防錯、memo、確認頁、送出後的對帳這幾點設計。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| Keccak-256 | CryptoKit 沒有提供 Keccak-256，需第三方函式庫 | JDK 的 `SHA3-256` 是 NIST 版，不是 Keccak-256；用 Bouncy Castle 等函式庫 |
| 掃描 QR | `AVCaptureMetadataOutput`（`.qr`） | ML Kit 條碼掃描、CameraX |
| 剪貼簿 | `UIPasteControl`；程式讀取會跳提示（iOS 16 起） | `ClipboardManager`；讀取有系統提示（Android 12 起） |
| 大整數 | 沒有內建（Base58 要自己用位元組陣列計算，或用函式庫） | `java.math.BigInteger`（`toByteArray` 可能多一個符號位元組，要處理） |
| 32 位元無號整數 | `UInt32`（`Int` 在 64 位元平台是 64 位元） | `Long` 或 `UInt`；不要用 `Int` 存 |
| 外部錢包 SDK | Reown（WalletConnect）Swift SDK | Reown Android SDK |
| 從錢包返回 App | Universal Link 搭配 `onOpenURL` | App Links 搭配 `Intent` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 託管型錢包 | custodial wallet | カストディアルウォレット（預かり型） |
| 私鑰／助記詞 | private key／mnemonic phrase | 秘密鍵／ニーモニック |
| 校驗碼 | checksum | チェックサム |
| 目的地標籤（XRP） | destination tag | デスティネーションタグ |
| 網路（鏈） | network（chain） | ネットワーク（チェーン） |
| 出金 | withdrawal | 出金 |
| 地址投毒 | address poisoning | アドレスポイズニング |
| 冪等鍵 | idempotency key | 冪等キー（べきとうキー） |
