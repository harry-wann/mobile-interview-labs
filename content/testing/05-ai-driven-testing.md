---
id: testing-ai
title: AI 驅動的自動化測試：放在哪裡、怎麼把關、30／60／90 天計畫
summary: 建立 AI 自動化測試環境，是這類職缺常見的核心職責之一。這個單元給你一個完整、可以實際執行的藍圖：AI 放在測試流程的哪幾個環節、怎麼確保 AI 產生的東西可信、怎麼衡量效果，以及接手後前 90 天要做什麼。
priority: P0
day: 5
tags: [testing, ai, mcp, agents, automation]
---

## 重點講解

這個單元回答五個問題：

1. AI 可以放在測試流程的哪些地方？
2. 怎麼確保 AI 產生的測試是可信的？
3. 2026 年常見的工具有哪些？
4. 整體架構長什麼樣子？
5. 加入後的 30／60／90 天要做什麼？

### 1. AI 可以放在測試流程的哪些地方？

**結論：AI 負責「產生」和「整理」，pass／fail 的判斷一定交給確定性的程式碼或人。**

| 環節 | AI 做什麼 | 誰判斷結果對不對 |
|---|---|---|
| 寫測試 | 根據程式碼、規格、現有的測試清單，產生單元測試和 Maestro 流程的草稿 | 測試程式裡的斷言（寫死的檢查條件，每次結果都一樣） |
| 探索式測試 | AI agent 透過 MCP 操作模擬器或實機，依目標（例如「完成一筆限價買單」）自己摸索，記錄截圖和異常 | 人確認異常清單，再轉成固定的測試 |
| 分析失敗原因 | CI（Continuous Integration，持續整合，每次有人推程式碼就自動建置、跑測試的機制）失敗時讀 log、截圖、程式碼差異，判斷是產品 bug、時好時壞的測試，還是環境問題，在 PR（pull request，把程式碼提交給團隊審查、通過後才合併的請求）上留言 | 人看分類和建議 |
| 檢查畫面 | 比對截圖差異、找出文字被切掉、忘了翻譯的字串、深色模式的對比不足 | 人確認 |
| 維護測試 | UI 改版後，建議怎麼更新元件 ID 或操作步驟（只建議，不自動合併） | review 後才合併 |
| Code review | PR 自動 review，提醒哪裡缺少測試 | 人 |

MCP（Model Context Protocol）是讓 AI 呼叫外部工具的標準協定。透過它，AI agent 可以執行「點擊這個按鈕」「截圖」「建置 App」這類操作。

> [!TIP]
> 面試時可以這樣說：「我會讓 AI 負責產生測試草稿、探索 App、分析失敗原因，但判斷 pass 或 fail 一定交給寫死的斷言或人，不讓 AI 判斷『畫面看起來對不對』。」

### 2. 怎麼確保 AI 產生的測試是可信的？

六個原則：

1. **pass／fail 必須是確定性的**：金額、狀態這類檢查寫在測試程式碼裡，每次跑結果都一樣。不交給 AI 判斷「看起來對不對」，因為 AI 的判斷每次可能不同，也沒辦法重現。
2. **AI 產生的測試要先證明自己有用**：故意把被測的程式改壞（或用 mutation testing 工具自動改），測試要能失敗；連續跑好幾次都通過，確認不是時好時壞的，才能合併。
3. **人工 review**：AI 寫的測試和產品程式碼一樣，要經過 review。
4. **探索的結果要固定下來**：agent 探索時發現的問題，轉成固定的 Maestro 或 UI 測試，之後每天自動跑，不依賴 agent 下次還能找到。
5. **資料與安全**：不把正式環境的使用者資料、金鑰送給外部的 AI 模型；依公司政策選擇模型和部署方式。
6. **控制成本**：agent 探索比較慢、比較貴，放在每天晚上或發布前跑；PR 上只跑快速、確定性的測試。

> [!TIP]
> 面試時可以這樣說：「AI 產生的測試，我要求先證明它有用：故意改壞程式，測試要能抓到；連跑幾次都穩定；再經過人工 review 才合併。只看測試數量變多是沒有意義的。」

### 3. 2026 年常見的工具有哪些？

- **Claude Code、Codex、Cursor**：寫測試、改測試、分析失敗。用專案的規則檔（CLAUDE.md、AGENTS.md）規定測試的寫法，例如：禁止用 `sleep` 等待、測試的命名方式、測試資料放在哪裡、新增測試後要更新測試清單。
- **讓 agent 操作裝置的 MCP 工具**：
  - **mobile-mcp**：同時支援 iOS 和 Android，透過畫面的無障礙資訊（accessibility tree）和截圖操作模擬器或實機。
  - **XcodeBuildMCP**：建置 iOS App、執行、跑測試、操作 iOS 模擬器。
  - 也可以讓 agent 直接呼叫 Maestro 的指令，執行或產生流程。
- **CI**：Codemagic、GitHub Actions、CircleCI 跑確定性的測試；每天晚上排程跑 agent 探索和 device farm。

> [!TIP]
> 面試時可以這樣說：「寫測試和分析失敗，我會用 Claude Code、Codex、Cursor 這類工具，並用 CLAUDE.md、AGENTS.md 規定測試的寫法，例如禁止用 `sleep` 等待。要讓 agent 操作裝置，可以用 mobile-mcp 或 XcodeBuildMCP 這類 MCP 工具，也可以直接呼叫 Maestro。確定性的測試放在 Codemagic、GitHub Actions 或 CircleCI 跑，agent 探索和 device farm 放到每天晚上。」

### 4. 整體架構長什麼樣子？

```text
[好測試的 App]  依賴注入的 stub、啟動參數、元件 ID 命名規範、測試帳號，
                加上固定的 TOTP（Time-based One-Time Password，
                兩步驟驗證用的 6 位數驗證碼）金鑰
      │
[確定性的測試]  單元測試／截圖比對（每個 PR）
                → Maestro 關鍵流程（合併後）
                → device farm 完整一輪（每天晚上）
      │                                          ▲
[AI 層]                                          │ 發現的問題轉成固定測試
  ├─ 寫測試的助手：規則檔＋測試清單 → 產生草稿 → 故意改壞程式驗證 → PR review
  ├─ 探索 agent（每天晚上）：透過 MCP 操作模擬器，依目標清單探索 →
  │    異常報告（crash、版面、沒翻譯、卡頓）
  └─ 失敗分析：CI 失敗 → 分類＋建議 → 在 PR 留言
      │
[指標]  新增的有效測試數、mutation score、時好時壞的比例、
        上線後逃過測試的 bug 數、失敗分析省下的時間
```

重點是分層：最底下是「App 本身好不好測」，往上是「確定性的測試」，AI 是再往上的輔助層，最後用指標證明效果。

> [!TIP]
> 面試時可以這樣說：「我會分四層建：先讓 App 好測（依賴注入、元件 ID、測試帳號），再建確定性的測試骨幹，AI 放在上面當輔助：寫測試草稿、夜間探索、分析失敗原因。最後用 mutation score、flaky 比例和上線後的 bug 數證明效果。」

### 5. 加入後的 30／60／90 天要做什麼？

| 期間 | 目標 | 產出 |
|---|---|---|
| 第 1–30 天 | 盤點現況、打好基礎 | 現況報告（現有測試、CI、時好時壞的測試、線上的 bug）；元件 ID 和測試帳號的規範；規則檔的第一版 |
| 第 31–60 天 | 確定性測試的骨幹＋AI 寫測試的助手 | 5 條關鍵流程的 Maestro 測試進 CI；AI 產生單元測試的流程（含「故意改壞程式」的驗證）開始運作 |
| 第 61–90 天 | AI 探索與失敗分析 | 夜間探索 agent 的第一版報告；CI 失敗的自動分析；用指標向團隊報告效果 |

> [!TIP]
> 面試時可以這樣說：「前 30 天我會盤點現況，交出現況報告，並訂好元件 ID 和測試帳號的規範、規則檔的第一版。31 到 60 天建立確定性測試的骨幹，把 5 條關鍵流程的 Maestro 測試放進 CI，同時讓 AI 產生單元測試的流程開始運作，包含故意改壞程式的驗證。61 到 90 天交出夜間探索 agent 的第一版報告和 CI 失敗的自動分析，並用指標向團隊報告效果。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Testing05AITesting**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Testing05AITesting**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Testing/Testing05-AITesting/`，解答在 `labs/ios/Solution/Testing/Testing05-AITesting/`；Android 你寫在 `labs/android/practice/testing/testing05-ai-testing/`，解答在 `labs/android/solution/testing/testing05-ai-testing/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到任何 AI 服務或外部網路**：這個單元練的是「怎麼確認 AI 產生的測試可信」，所以 AI 產生的測試是事先寫好的一批資料（`T05Suite.aiGenerated`，在 `Testing05Support` 檔案裡）；你寫的是把關的規則。被測的程式是手續費函式 `T05Fee.calculate`，「故意改壞的版本」（`T05Mutation`）也是事先寫好的：真正的 mutation testing 工具會自動改程式碼，這裡用開關代表。

### 演練 1：AI 的測試全過、覆蓋率 100%，卻抓不到壞掉的程式（第 2 題）

**檔案**：`L01_MutationGate.swift`（iOS）、`L01_MutationGate.kt`（Android）

**情境**：AI 幫手續費函式 `T05Fee.calculate` 產生了 5 個單元測試（例如「手續費不會是負的」「taker 手續費大於 0」），全部通過，每個檢查點都跑到（覆蓋率 100%）。審查規則 `accepts(suite)` 決定「這批測試能不能合併」。另外有 5 個故意改壞的版本：`>=` 改成 `>`、maker 與 taker 費率對調、無條件捨去改成四捨五入、拿掉「最少收 1 日圓」、VIP 沒有減半。`suite.passes(on: 某個版本)`（Kotlin 是 `suite.passes(某個版本)`）用這個版本跑一次全部測試，沒有通過代表測試「抓到」這個壞版本。演練分別審查兩批測試：AI 產生的、人補強過的（檢查精確數字與邊界）。

**怎麼寫**

1. **Bug 版**：`accepts` 在「測試全過（`suite.passes` 在正確的實作上通過）而且覆蓋率（`suite.coverage()`）≥ 0.8」時回傳 `true`。
2. **修正版**：`accepts` 先確認測試在正確的實作上全過；再對每個 `T05Mutation.allMutants` 跑 `suite.passes`，沒通過就是抓到；抓到的比例（mutation score）≥ 0.8 才回傳 `true`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「AI 產生的測試：全部通過，覆蓋率 100%」「審查結果：允許合併」，但「被改壞的版本抓到 1／5（mutation score 20%）」；補強後的測試也允許合併 |
| 修正版 | AI 產生的測試「審查結果：擋下」（20%）；補強後的測試「審查結果：允許合併」，「抓到 5／5（mutation score 100%）」 |

**為什麼**：AI 很擅長產生「看起來很完整」的測試：每個函式都被呼叫、每個分支都跑到，但斷言常常只是「結果不是負的」「結果大於 0」，不檢查精確的數字與邊界。只看「全過」和覆蓋率，這批測試會被放行；mutation score 才看得出它只抓到 5 個壞版本裡的 1 個（只有「拿掉最少收 1 日圓」會讓「小額單也有手續費」失敗）。把這個檢查放進 CI，AI 產生的測試要先證明自己有用才能合併，這就是複習站說的「AI 產生的測試要先證明自己有用」。真實專案可以用 Pitest（跑在 Java 虛擬機器 JVM 上）或 Muter（Swift）自動產生這些修改；這個專案沒有安裝，沒有跑過。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing05-AITesting/L01_MutationGate.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing05-AITesting/L01_MutationGate.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing05-ai-testing/L01_MutationGate.kt#bug
```

```lab-solution
android/solution/testing/testing05-ai-testing/L01_MutationGate.kt#fixed
```

### 演練 2：AI 的測試合併時通過，之後時好時壞（第 2 題）

**檔案**：`L02_RepeatRun.swift`（iOS）、`L02_RepeatRun.kt`（Android）

**情境**：AI 產生了一個用「亂數金額」的測試：隨機取一個金額，檢查 `T05Fee.calculate` 的結果等於「金額 × 0.15%，四捨五入」。但規格是「無條件捨去」，所以只要金額乘以費率的小數部分 ≥ 0.5，測試就會失敗，大約四成的亂數會踩到。`isStable(runTest)` 決定這個測試「穩不穩定，能不能合併」。演練用固定的一串數字代表亂數，每次結果相同。兩個測試：AI 產生的（用亂數金額）、固定金額的（穩定）。

**怎麼寫**

1. **Bug 版**：`isStable` 只呼叫一次 `runTest()`，通過就回傳 `true`。
2. **修正版**：`isStable` 連續呼叫 20 次 `runTest()`（每次取下一個亂數），只要有一次回傳 `false` 就回傳 `false`，全部通過才回傳 `true`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「AI 產生的隨機金額測試：審查結果：穩定，允許合併」，但「實際連跑 20 次，失敗 8 次」；固定金額的測試也允許合併 |
| 修正版 | 隨機金額測試「不穩定，擋下」；固定金額的測試「穩定，允許合併」（連跑 20 次失敗 0 次） |

**為什麼**：只跑一次，用到亂數、時間、順序的測試可能剛好通過；合併之後，每次 CI 都有機會失敗，而且失敗訊息只會看到「預期 2，實際 1」，沒有人知道是測試的期望值（四捨五入）寫錯了。要求 AI 產生的測試先連續跑好幾次再合併，是很便宜的把關；更好的做法是規則檔直接要求「測試不能用亂數、不能依賴現在的時間」，用固定的案例。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing05-AITesting/L02_RepeatRun.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing05-AITesting/L02_RepeatRun.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing05-ai-testing/L02_RepeatRun.kt#bug
```

```lab-solution
android/solution/testing/testing05-ai-testing/L02_RepeatRun.kt#fixed
```

### 演練 3：flaky 比例顯示 50%，其實只有 25%（第 4 題）

**檔案**：`L03_FlakyRate.swift`（iOS）、`L03_FlakyRate.kt`（Android）

**情境**：CI 歷史有 3 個 commit、4 個測試的執行紀錄（`T05History.runs`）：`testFee`、`testChart` 一直通過；`testLogin` 在 c2 壞掉（兩次重試都失敗，是真的 bug），c3 修好；`testOrderUI` 在 c1 和 c3 的同一個 commit 上，有時失敗有時通過（flaky）。`flakyTests(runs)` 回傳「flaky 的測試名稱」，看板用它算 flaky 比例 = flaky 測試數 ÷ 全部測試數。

**怎麼寫**

1. **Bug 版**：只要失敗過就算 flaky：回傳所有 `passed` 是 `false` 的測試名稱。
2. **修正版**：以「同一個 commit 上的同一個測試」為單位，收集它出現過的結果；既有通過也有失敗的，測試名稱才放進集合。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「flaky 的測試：testLogin、testOrderUI」，「flaky 比例：2／4 = 50%」 |
| 修正版 | 「flaky 的測試：testOrderUI」，「flaky 比例：1／4 = 25%」 |

**為什麼**：flaky 的定義是「程式沒改，同一個測試有時過有時不過」，所以要在同一個 commit 上比較。`testLogin` 在 c2 的兩次執行都失敗，是穩定的失敗，也就是真的 bug，不是 flaky。把它算進去，flaky 比例被灌水，團隊會花時間去「修 flaky」，其實是在修一個一般的 bug；真的 bug 要另外追蹤（例如上線後逃過測試的 bug 數）。複習站的架構圖把「mutation score、flaky 比例、上線後逃過測試的 bug 數」列為指標，定義錯了，向團隊報告的數字就沒有意義。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing05-AITesting/L03_FlakyRate.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing05-AITesting/L03_FlakyRate.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing05-ai-testing/L03_FlakyRate.kt#bug
```

```lab-solution
android/solution/testing/testing05-ai-testing/L03_FlakyRate.kt#fixed
```

第 1 題（AI 放在哪些環節）、第 3 題（工具）、第 5 題（30／60／90 天計畫）是概念、工具與時程的題目，沒有演練。第 3 題的工具需要真的裝置或外部服務，這個專案沒有安裝，下面是怎麼設定的範例（沒有在這個專案裡跑過；套件名稱與參數以各專案的官方說明為準）。

**規則檔**（`CLAUDE.md` 或 `AGENTS.md`，規定 AI 寫測試的方式）：

```text
## 測試規則
- 不能用 sleep、Thread.sleep、Task.sleep 等待結果；
  iOS 用 await 與 Clock，Android 用 runTest 與注入的 dispatcher。
- 不能用亂數或現在的時間；用固定的測試資料，時間從外面注入。
- 每個測試至少要有一個會因為程式改壞而失敗的斷言，
  不要只檢查「不是 null」「大於 0」。
- 找元件用 accessibilityIdentifier（iOS）或 testTag（Android），
  不要用畫面上的文字。
- 新增測試後，更新 docs/test-inventory.md。
```

**讓 AI 操作裝置的 MCP 設定**（以 Claude Code 的 `.mcp.json` 為例）：

```json
{
  "mcpServers": {
    "mobile-mcp": {
      "command": "npx",
      "args": ["-y", "@mobilenext/mobile-mcp@latest"]
    },
    "XcodeBuildMCP": {
      "command": "npx",
      "args": ["-y", "xcodebuildmcp@latest"]
    }
  }
}
```

mobile-mcp 同時支援 iOS 和 Android 的模擬器與實機，透過畫面的無障礙資訊與截圖操作；XcodeBuildMCP 負責建置、執行、跑測試、操作 iOS 模擬器。agent 探索到的問題，要轉成固定的 Maestro 或 UI 測試（見 Testing04 的範例），不要每天靠 agent 重新找一次。

## 常見陷阱

- **讓 AI 判斷測試結果（「畫面看起來正常」）。** 結果無法重現，也無法信任。
- **AI 一次產生幾百個測試就直接合併。** 數量好看，但不一定抓得到錯。要先驗證它們真的有用。
- **每個 PR 都跑 agent 探索。** 又慢、又貴、結果又不穩定。放到每天晚上。
- **忽略資料安全。** 把正式環境的帳號或使用者資料交給外部的 AI 模型。

## 面試官想聽到的

1. 一個**分層**的藍圖：好測試的 App → 確定性的測試 → AI 輔助層 → 指標。
2. 清楚的把關：AI 產生、確定性的判斷、人工 review、證明測試真的有用。
3. 實際的經驗：你已經做過哪些。
4. 可以執行的時程和可以衡量的成果。

> [!FIELD]
> 在一個社交 App 把 AI agent 納入開發流程，建立「規劃 → 實作 → review → 修正」的循環，並用 AI 撰寫單元測試提升覆蓋率，規劃、架構決策和最後的驗證由人自己把關。實作上，UI 測試用依賴注入換成 stub 讓結果可以重現，測試規則禁止 `sleep`；CI 用 Codemagic 等工具自動建置。
> 面試時可以這樣說：「我在前一個專案已經用 AI 撰寫單元測試來提升覆蓋率，也建立了規劃、實作、review、修正的循環。下一步是把它系統化：讓 AI 產生的測試先通過有效性驗證，再加上夜間的探索測試和失敗分析，這正是我想在交易所 App 做的事。」

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 自動化測試 | test automation | テスト自動化 |
| 探索式測試 | exploratory testing | 探索的テスト |
| 確定性的（每次結果都一樣） | deterministic | 決定的 |
| 變異測試 | mutation testing | ミューテーションテスト |
| 讓 AI 呼叫外部工具的協定 | Model Context Protocol（MCP） | MCP |
| 規則檔 | rules file（CLAUDE.md／AGENTS.md） | ルールファイル |
