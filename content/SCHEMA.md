# 內容格式合約（content/）

本站所有學習內容都放在 `content/`，由 `scripts/build-content.ts` 驗證（zod）並轉成 `src/generated/content.json`。
內容作者（人或 agent）只需要照這份合約寫 Markdown 與 YAML，不用碰前端程式。

讀者是 **有幾年經驗的 iOS／Android 雙平台工程師**，要面試加密資產交易所（或其他金融類 App）的 Mobile 工程師職位。
不用從語法教起（例如 `struct` 怎麼宣告、`launch` 怎麼呼叫），但**面試會考的每個概念都要講清楚、講完整**：平常寫 App 的人不一定熟面試用語，常見的回饋是「太簡短、太多自創名詞，看不懂」。寫法規則見 2.1。

---

## 1. 目錄與檔名

```
content/
  # section 順序與標題（已建立，不要改 id）
  sections.yaml
  # 7 日衝刺計畫（main 維護）
  plan/days.yaml
  <section-dir>/
    # 單元筆記（NN = 01、02… 決定排序）
    NN-<slug>.md
    # 該單元的練習題（同 basename + .ex.yaml）
    NN-<slug>.ex.yaml
```

- 一個單元 = 一個 `.md` ＋ 一個同名 `.ex.yaml`。`.ex.yaml` 必須存在（至少 1 題）。
- 單元 `id` 規則：`<section-id>-<slug>`，例如 `swift-concurrency-actors`。全站唯一，只能用小寫英數與 `-`（會被當成網址錨點 `#swift-concurrency-actors`）。
- 題目 `id` 規則：`<unit-id>-NN`，例如 `swift-concurrency-actors-03`。全站唯一。

## 2. 單元 Markdown（`NN-<slug>.md`）

```markdown
---
id: exchange-precision
title: 精度與金額：Decimal、tick size 與捨入
summary: 交易所 App 最常出事的地方：用 Double 算錢、數量捨入方向錯、顯示格式跟著 locale 亂跳。
# P0 面試前一定要會；P1 很可能被問；P2 加分
priority: P0
# 對應 7 日計畫第幾天（1–7），可省略
day: 4
tags: [decimal, money, formatting]
---

## 重點講解
...
## 常見陷阱
...
## 面試官想聽到的
...
## iOS / Android 對照
...（同一概念兩平台並排；不適用就省略這段）
```

### 2.1 筆記寫法（2026-10-24 改版，**照 `swift/01`、`swift/02` 的範本寫**）

**段落**（依序）：`## 重點講解`、`## 實際演練`（技術單元，見 2.3）、`## 常見陷阱`、`## 面試官想聽到的`、`## iOS / Android 對照`（可省略）、`## 名詞對照`。

**`## 重點講解` 的結構**
1. 開頭列出「這個單元回答 N 個面試常見問題」，用問句條列。
2. 每個問題一個 `### N. 問句標題`（例：`### 2. struct 一定比 class 快嗎？`），內容依序是：
   - **先給結論**：1–3 句完整的句子。不要用「**一句話**：」這種標籤。
   - **再解釋為什麼**：用完整段落，搭配一個具體情境（App、交易所行情、下單、聊天室等）。不要只丟幾條碎片 bullet。
   - **程式碼範例**：短、正確，註解寫出「這一行會發生什麼」，能印出結果的要寫出輸出（`// 輸出：…`）。
   - 容易搞混的地方，用「常見誤解」段落或比較表說明。
   - 最後一個 `> [!TIP]`，開頭固定寫「面試時可以這樣說：」，接 1–3 句可以直接念出來的口語回答。
3. 每個 `###` 約 300–800 字（含程式碼）。一個單元 4–7 個問題，讀完約 15–20 分鐘。
4. 比較少考、但被問到要答得出來的主題，標題加「（比較少考，被問到能答就好）」，篇幅縮短。
5. 純冷知識直接刪掉：runtime 內部結構、位元組數、內部函式名（例如 `swift_weakLoadStrong`、side table、existential container 是 40 bytes、`@_implicitSelfCapture`）。寧可少講一個主題，也要把留下來的講清楚。

**用語規則（最重要）**
- **不要自創名詞，也不要自己把英文術語翻成中文新詞。** 技術名詞用官方英文原名（Apple、Google、Kotlin 官方文件上的寫法），第一次出現時用一句白話解釋，例：「COW 是 copy-on-write 的縮寫，意思是先共用，等到真的要修改時才複製」。
- 禁用（以前的版本出現過，讀者看不懂）：假值語意、高頻路徑、升級成 strong、通用盒子、開箱、探針、清理點、暫存回收區、獨佔存取（當專有名詞用）、內聯、特化、trap（當動詞用）、口徑、考點、一句話、加分不必背、打地基、深水區、成熟度展示、擁有權語意。要表達同樣的意思時，用完整的白話句子描述（例：「每秒更新很多次的地方，例如行情列表」）。
- 台灣用語：記憶體、執行緒、物件、變數、函式、非同步、型別、參考、編譯器、伺服器。不要用大陸用語（內存、線程、對象、函數式以外的「函數」、異步、類型）；兩岸說法對照只放在 `strategy` 的技術用語單元。
- 每個縮寫第一次出現都要展開並解釋（ARC、COW、GC、MVI、UDF、ANR、OOM、TOTP、HMAC…）。
- 不要預設讀者看過其他單元；需要的前置概念用一兩句話複習（例：「上一個單元提過，ARC 是……」）。

**`## 常見陷阱`**：每條用「**錯誤做法或誤解。**」開頭，接著說明為什麼錯、正確做法是什麼。
**`## 面試官想聽到的`**：3–5 條，用完整句子寫出評分重點。
**`## 名詞對照`**：表格 `| 中文 | 英文 | 日文 |`，只放這個單元出現過的 4–8 個重要名詞，給日文面試用。

**提示框**（GitHub alert 語法）
- `> [!TIP]` 面試時可以這樣說（每個 `###` 最後一個）
- `> [!WARNING]` 面試官常追問的坑
- `> [!NOTE]` 補充
- `> [!FIELD]` 實務上怎麼做（不具名的實務經驗：「在一個即時行情 App 裡…」）。不寫任何真實公司名、產品名、內部網域、可對應到特定人的數字。

**其他**
- 程式碼區塊標語言：```` ```swift ````、```` ```kotlin ````、```` ```objectivec ````、```` ```java ````、```` ```json ````、```` ```ts ````、```` ```bash ````、```` ```yaml ````、```` ```text ````。程式碼要能編譯（語法正確、API 名稱真實存在）。
- 表格用 GFM 表格。
- 日本交易所業務名詞用日文原文並附中文（例：成行（市價單））。
- 交易所的 API 以 Huobi 相容的公開協定為例，不指名特定公司；法規（犯収法、金商法、旅行規則）只寫公開資訊，不確定的寫「（未證實）」。
- 版本敏感的內容（Swift 6.2、Kotlin 2.x、Compose、Android 15/16、iOS 18/26）要寫明「哪個版本開始」；不確定就不要寫。

### 2.2 練習題的文字也要白話
- 題目、選項、解析用同樣的用語規則。解析要說「為什麼」，每個選項錯在哪裡都要交代。
- 每一題都要能從這個單元的筆記找到答案。筆記刪掉的冷知識，對應的題目也刪掉。

### 2.3 `## 實際演練`（2026-10-24 新增，**照 `swift/01`、`kotlin/03` 的範本寫**）
技術單元（swift、kotlin、platform、advanced、exchange、testing、sysdesign、livecoding）都要有這一段，對應 `labs/` 的 iOS／Android 專案。演練程式碼分成 Practice（練習骨架，讀者自己寫）與 Solution（解答），寫法見 `labs/AUTHORING.md`。

1. 開頭一個 `> [!LAB]`：怎麼打開（專案、App 首頁進入哪個單元代號）、檔案在哪（Practice 與 Solution 的資料夾）、練法（寫 Bug 版 → 執行出現 ✓ → 寫修正版 → 對照解答）。
2. 每個演練一個 `### 演練 N：<看得到的症狀>（第 N 題）`，依序寫：**檔案**、**情境**、**怎麼寫**（1. Bug 版、2. 修正版，白話說明要寫什麼，可以點名 API，不要整段程式碼）、`| 版本 | 寫對了會看到什麼 |` 表格、**為什麼**。
3. 最後附解答，不要貼程式碼，用 fence 從 `labs/` 擷取，預設摺疊：
   ```` ```lab-solution ```` ＋ 一行 `ios/Solution/<區>/<單元資料夾>/<檔名>.swift#bug`
   ```` ```lab-solution ```` ＋ 一行 `…#fixed`
   路徑相對於 `labs/`；`#bug`／`#fixed` 對應檔案裡的 `// MARK: - Bug 版`／`// MARK: - 修正版`。路徑或區段不存在時 `content:check` 會出警告。
4. 雙平台單元：開頭的 `[!LAB]` 寫兩個平台；每個演練的「怎麼寫」分 iOS／Android 寫（做法相同時合併寫），解答 iOS、Android 各放一組 `lab-solution`，前面用 `**iOS 解答**`、`**Android 解答**` 標示。
5. 沒有可以執行的 bug 的題目（純概念、流程題），在段落最後用一句話列出「第 N 題是流程題，沒有演練」，不要硬做。

## 3. 練習題 YAML（`NN-<slug>.ex.yaml`）

檔案內容是一個 **陣列**。每題共同欄位：

| 欄位 | 必填 | 說明 |
|---|---|---|
| `id` | ✔ | `<unit-id>-NN` |
| `type` | ✔ | `single` `multi` `fill` `predict` `order` `code` `open` `flash` |
| `level` | ✔ | 1（熟悉）／2（資深）／3（深挖） |
| `priority` | ✔ | `P0` `P1` `P2` |
| `tags` | | 字串陣列 |
| `prompt` | ✔（flash 除外） | 題幹，Markdown |
| `code` | | 題幹附帶程式碼（純文字，不含 ``` 圍欄） |
| `codeLang` | 有 `code` 時 ✔ | `swift` `kotlin` `objectivec` `java` `ts` `json` `text` |
| `explain` | single/multi/fill/predict/order ✔ | 解析，Markdown。要說「為什麼」，不只是答案 |

所有 Markdown 欄位一律用 YAML block scalar（`|`），避免冒號、`#`、引號造成 YAML 解析錯誤。

### 3.1 `single`／`multi`

```yaml
- id: exchange-precision-01
  type: single
  level: 2
  priority: P0
  prompt: |
    下列哪一種做法最適合在 Swift 端計算訂單金額？
  options:
    - "`Double` 計算後用 `String(format: \"%.2f\")` 顯示"
    - "`Decimal`，捨入用 `NSDecimalRound` 並指定 `.down`"
    - "`Float` 以節省記憶體"
  # single：0-based 索引
  answer: 1
  explain: |
    ...
```
- `multi` 的 `answer` 為索引陣列，例如 `[0, 2]`。選項 3–6 個。
- 不要讓正解總是同一個位置；錯誤選項要「看起來合理」。

### 3.2 `fill`（填空）

```yaml
- id: kotlin-flow-04
  type: fill
  level: 2
  priority: P0
  prompt: |
    把 cold flow 轉成在畫面離開 5 秒後才停止上游的 StateFlow。
  codeLang: kotlin
  code: |
    val uiState = repo.prices
        .map { it.toUi() }
        .____(viewModelScope, SharingStarted.____(5_000), UiState.Loading)
  blanks:
    - [stateIn]
    - [WhileSubscribed]
  explain: |
    ...
```
- `code` 中每個 `____`（**連續 4 個底線**）是一個空格，依出現順序對應 `blanks`。
- `blanks[i]` 是可接受答案的陣列（寫法變體都列出來）。比對前會 trim、合併空白；預設大小寫敏感，可加 `caseInsensitive: true`。

### 3.3 `predict`（預測輸出）

```yaml
- id: swift-concurrency-basics-05
  type: predict
  level: 2
  priority: P1
  prompt: |
    印出什麼？（依序，以空白或換行分隔）
  codeLang: swift
  code: |
    ...
  answer: |
    A C B
  explain: |
    ...
```
- 比對方式：雙方都以 `/[\s,]+/` 切成 token 後逐一比較。所以 `answer` 只寫輸出 token。
- 若有多種可接受答案（真的不確定的順序），用 `acceptAlso: ["A B C"]`；但**盡量出決定性的題目**。
- 程式碼必須真的會產生這個輸出（Swift／Kotlin 題請在心裡或實際跑過確認）。

### 3.4 `order`（排序）

```yaml
- id: exchange-orders-06
  type: order
  level: 1
  priority: P1
  prompt: |
    把下單流程依正確順序排列。
  # 依「正確順序」寫，前端會打亂
  items:
    - 產生 clientOrderId（UUID）並鎖定送出按鈕
    - 前端驗證 tick size／最小數量／餘額
    - 送出簽名 REST 請求
    - 收到 ack，狀態 → New
    - 從 private WS 收到成交推送，狀態 → PartiallyFilled／Filled
  explain: |
    ...
```
- 3–7 個項目。

### 3.5 `code`（本機編譯執行、隱藏測試驗證）

```yaml
- id: exchange-precision-05
  type: code
  level: 2
  priority: P0
  prompt: |
    實作 `roundToTick`：把價格依 tick size **往下** 捨入。
  hints:
    - 先除以 tick，取整數部分再乘回去。
  swift:
    starter: |
      import Foundation
      func roundToTick(_ price: Decimal, tick: Decimal) -> Decimal {
          // TODO
          return price
      }
    solution: |
      import Foundation
      func roundToTick(_ price: Decimal, tick: Decimal) -> Decimal {
          var q = price / tick
          var r = Decimal()
          NSDecimalRound(&r, &q, 0, .down)
          return r * tick
      }
    tests: |
      checkEqual("1234.567 / 0.01", Decimal(string: "1234.56")!) {
          roundToTick(
              Decimal(string: "1234.567")!,
              tick: Decimal(string: "0.01")!
          )
      }
  kotlin:
    starter: |
      import java.math.BigDecimal
      fun roundToTick(price: BigDecimal, tick: BigDecimal): BigDecimal {
          TODO()
      }
    solution: |
      import java.math.BigDecimal
      import java.math.RoundingMode
      fun roundToTick(price: BigDecimal, tick: BigDecimal): BigDecimal =
          price.divide(tick, 0, RoundingMode.DOWN).multiply(tick)
    tests: |
      checkEqual("1234.567 / 0.01", 0) {
          roundToTick(BigDecimal("1234.567"), BigDecimal("0.01"))
              .compareTo(BigDecimal("1234.56"))
      }
```

**Runner 合約**（`runner/prelude.swift`、`runner/prelude.kt` 已建立，內容作者要照這個寫 tests）：

- 至少提供 `swift` 或 `kotlin` 其中一個；交易所與 live coding 題**兩個都要給**。
- 檔案組合方式：`prelude` ＋ `starter 或使用者程式碼` ＋ `tests`，合併成單一檔案。所有 `import` 行會被抽出、去重、放到檔案最上方。
- **Swift**：存成 `main.swift`，用 `swiftc -swift-version 5`（題目可加 `swiftVersion: 6`）編譯。tests 是 top-level 敘述，可直接 `await`。最後 runner 自動補 `__testsDone()`。
- **Kotlin**：存成 `Main.kt`；tests 會被包進 `fun main() = runBlocking { <tests>; __testsDone() }`，所以 tests 裡可以直接呼叫 suspend 函式。classpath 含 `kotlinx-coroutines-core`。使用者程式碼只能有 top-level 宣告（class、fun、val）。
- 斷言 API（兩個平台同名）：

| Swift | Kotlin | 說明 |
|---|---|---|
| `check("名稱") { 條件 }` | `check("名稱") { 條件 }` | 條件為 true 即通過 |
| `checkEqual("名稱", 預期值) { 實際值 }` | `checkEqual("名稱", 預期值) { 實際值 }` | `==` 比較，失敗時顯示兩者 |
| `checkClose("名稱", 預期, eps) { Double }` | `checkClose("名稱", 預期, eps) { Double }` | 浮點容差 |
| `await checkAsync("名稱") { await … }` | （直接用 `check`，lambda 可呼叫 suspend） | Swift 的 async 版本 |
| `await checkEqualAsync("名稱", 預期) { await … }` | — | |

  - 區塊裡丟出例外／錯誤會被記為該題失敗，不會中斷其他測試（但 Swift 的 crash，如陣列越界、`fatalError`，會讓整個程式結束）。
  - 測試名稱用中文短句描述情境，例如 `"tick 0.01 往下捨入"`。
- 每題 3–8 個 test，涵蓋正常、邊界、錯誤輸入。`starter` 必須能編譯，但**至少有一個 test 失敗**；`solution` 必須**全部通過**（`npm run tasks:verify` 會驗證）。
- `starter` 的函式請回傳預設值（`nil`、`emptyList()`、原值），**不要用 `TODO()`／`fatalError()`**，除非 tests 裡所有呼叫都包在 `check…{ }` 區塊內。tests 頂層的設定敘述（例如 `cache.put(...)`）若呼叫到會崩潰的 starter，整支程式會中止，使用者看不到逐項結果。
- `code` 題可加 `timeLimitSec`（Live Coding 計時模式用，例如 1200＝20 分鐘）與 `timeoutSec`（runner 執行逾時，預設 10）。
- 不要依賴執行時間、亂數、網路、檔案系統。時間相關的邏輯請注入時鐘參數（例如 `now: Int64`）。
- 只能用標準函式庫：Swift 用 Foundation（macOS，可用 CryptoKit）；Kotlin 用 stdlib＋`java.*`＋`kotlinx.coroutines`。

### 3.6 `open`（口說題／系統設計題）

```yaml
- id: exchange-ws-protocol-20
  type: open
  level: 2
  priority: P0
  prompt: |
    即時 K 線用 SSE 還是 WebSocket？各在什麼情況下選？
  promptJa: |
    （可省略）日文版題目
  # 自評勾選用，4–8 點，每點一句話
  keyPoints:
    - 單向推送需求，SSE 走一般 HTTP、穿 proxy 容易
    - 兩段式握手：REST 取短效 token → 開 stream
    - 重連固定 1s 的不足 → 指數退避＋jitter
    - 交易所要雙向（訂閱多 topic、私有頻道）→ WebSocket
  modelAnswer: |
    （Markdown，建議 150–400 字，可直接念出來的口語版本）
  modelAnswerJa: |
    （可省略）日文版
  # 面試官可能的追問
  followUps:
    - 背景斷線後怎麼補資料？
  # （可省略）只有作答者本人知道、要先填好的事實，例如「這個專案的使用者規模」
  factsToConfirm:
    - 你的上一個即時行情專案尖峰每秒幾則訊息？
  # 模擬面試計時，可省略（預設 120）
  timeLimitSec: 120
```
- 系統設計題的 `keyPoints` 要涵蓋：需求釐清、資料流、client 架構、邊界情況、效能、測試、監控。

### 3.7 `flash`（速記卡）

```yaml
- id: exchange-business-101-30
  type: flash
  level: 1
  priority: P0
  front: maker／taker 分別是什麼？
  back: maker 掛單提供流動性（通常手續費較低或為負），taker 吃單取走流動性
```
- `front`、`back` 都是 Markdown（多行用 `|`）。

完整範例單元：`content/exchange/02-precision.md` 與 `02-precision.ex.yaml`（含所有題型，程式題已驗證）。寫作風格、密度、解析深度請以它為準。

## 4. 每單元題目配置建議

- 6–10 題：`single`/`multi` 2–4 題、`fill` 或 `predict` 1–3 題、`open` 至少 1 題；有實作價值的單元加 1 題 `code`；`flash` 視需要。
- 難度以 level 2 為主，level 3 至少 1 題。
- 題目要貼近 **交易所 App 場景**（行情、訂單簿、下單、資產、K 線、推播、安全、eKYC），能結合就結合。

## 5. 自我檢查（交付前）

1. `npm run content:lint -- <section-dir>` 0 錯誤（id 唯一、必要欄位、答案索引在範圍內、`____` 數量 = `blanks` 數量）。引擎完成後另有 `npm run content:check`（zod，更嚴格）。
2. 有 `code` 題時跑 `npm run tasks:verify -- <題目或單元 id 前綴>`：solution 全過、starter 能編譯且至少一個失敗。Kotlin 單次約 4 秒，請只驗證自己寫的前綴。
3. `predict` 題的答案務必實際執行確認：把程式碼存到 `.runner-tmp/` 底下，用 `swiftc` 或 `.tools/kotlinc/bin/kotlinc` 跑一次（Kotlin 執行：`java -cp out:.tools/kotlinc/lib/kotlin-stdlib.jar:.tools/lib/* MainKt`）。
3. 技術細節不確定就不寫；寧可少一條也不要錯一條。
