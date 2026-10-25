---
id: sysdesign-alerts
title: 案例三：價格提醒推播（App 端＋伺服器端）
summary: 交易所 App 常有價格提醒功能。這題考的是：App 被關掉的時候，是誰在看價格？答案是伺服器。App 負責設定提醒、要通知權限、管理推播 token、點通知後打開正確的畫面，以及多語系的通知文字。
priority: P1
day: 5
tags: [system-design, push, notifications, alerts]
---

## 重點講解

這個單元回答六個問題：

1. 價格提醒有哪些需求？
2. 為什麼價格的判斷一定要放在伺服器？
3. 伺服器端大概怎麼做？（講到這個深度就夠）
4. App 端要負責哪些事？
5. 有哪些例外情況？
6. 怎麼測試和監控？

### 1. 價格提醒有哪些需求？

- 使用者對某個交易對設定「價格高於 X」「價格低於 X」或「24 小時漲跌超過 Y%」的提醒，每個人最多 N 個。
- **App 被關掉也要收到通知**，延遲在幾秒內。
- 可以設成只提醒一次，或重複提醒（重複時要有冷卻時間，避免價格在門檻附近上下跳動時一直通知）。
- 使用者有好幾台裝置時，每台都要收到；登出後就不再收到。
- 支援多種語言（例如日文、簡體中文、繁體中文、英文）。

> [!TIP]
> 面試時可以這樣說：「價格提醒我會先確認幾件事：條件是高於、低於還是漲跌幅超過多少，App 被關掉也要在幾秒內收到通知。可以設成只提醒一次或重複提醒，重複時要有冷卻時間。使用者有好幾台裝置時每台都要收到，登出後就不再收到，另外還要支援多種語言。」

### 2. 為什麼價格的判斷一定要放在伺服器？

**結論：App 在背景沒辦法可靠地持續執行，所以「價格到了沒」只能由伺服器判斷。App 只負責把提醒條件送給伺服器。**

- **iOS**：App 進背景後很快就會被暫停。`BGAppRefreshTask` 的執行時間由系統決定，可能幾小時才一次，也可能不執行。
- **Android**：有 Doze（手機閒置時，系統集中延後背景工作）和 App Standby（不常用的 App 被限制背景活動）等省電機制，背景工作同樣不保證準時。

所以正確的分工是：App 把「哪個交易對、什麼條件」送到伺服器；伺服器一直在看即時價格，條件成立就發推播。

> [!TIP]
> 面試時可以這樣說：「App 在背景沒辦法可靠地一直看價格，iOS 會暫停 App，Android 有 Doze，所以判斷一定放在伺服器。App 負責設定提醒、通知權限、推播 token 和點擊後的導向。」

### 3. 伺服器端大概怎麼做？

Mobile 面試講到這個深度就夠了：

```text
價格串流 ──→ 提醒判斷器（依交易對分開處理）
              ├─ 「高於」的提醒：依門檻由小到大排好
              └─ 「低於」的提醒：依門檻由大到小排好
           每來一個新價格：只取出「門檻已經被跨過」的那幾個提醒，不用全部檢查一次
           → 標記為已觸發（同一個提醒不會觸發兩次）→ 推播佇列 → APNs／FCM
```

- **用「跨過」判斷，不要用「等於」**：價格可能直接從 99 跳到 101，從來沒有剛好等於 100。所以條件是「價格 ≥ 門檻」或「價格 ≤ 門檻」。
- **門檻排好順序**：例如「高於」的提醒依門檻由小到大排，新價格進來時，只要從最小的開始取，取到門檻比價格大就停，不用每個提醒都檢查一次。
- **觸發狀態要存起來，而且同一個提醒只觸發一次**：伺服器重啟或重試時，才不會重複發推播。每個使用者也要限制推播頻率。
- APNs 是 Apple 的推播服務，FCM（Firebase Cloud Messaging）是 Google 的推播服務。

> [!TIP]
> 面試時可以這樣說：「伺服器端我會依交易對分開處理，把提醒依門檻排好順序，每來一個新價格，只取出門檻已經被跨過的提醒，不用全部檢查。判斷用跨過而不是等於，因為價格可能直接從 99 跳到 101。觸發狀態要存起來，同一個提醒只觸發一次，伺服器重啟或重試時才不會重複發推播。」

### 4. App 端要負責哪些事？

| 項目 | iOS | Android |
|---|---|---|
| 什麼時候要通知權限 | **使用者第一次建立提醒時**才詢問，不要一打開 App 就問 | Android 13 起要在執行時詢問 `POST_NOTIFICATIONS` 權限，同樣在使用者建立提醒時才問 |
| 推播 token | APNs 給的 device token（或透過 Firebase 轉成 FCM token）；登入後上傳到伺服器，**登出時通知伺服器解除綁定** | FCM token；token 更新時（`onNewToken`）重新上傳 |
| App 在前景時收到通知 | 由 `UNUserNotificationCenterDelegate` 的 `willPresent` 決定要不要顯示橫幅 | App 自己決定要顯示通知還是直接更新畫面 |
| 通知的重要程度 | 可以用 time-sensitive 等級（iOS 15 起，需要開啟對應的 capability），讓提醒穿過專注模式 | 建立一個「價格提醒」的 notification channel（通知類別），設成高重要性 |
| 點擊通知 | 從推播內容取出交易對代號，打開那個交易對的頁面（deep link） | 同上，`PendingIntent` 帶著交易對代號 |
| 多語系 | APNs 的 `loc-key`／`loc-args`：推播只帶「字串的 key 和參數」，由手機用 App 內的翻譯組出文字 | FCM 的 `title_loc_key`／`body_loc_key`，做法相同 |

另外兩件事：

- **表單檢查**：價格依交易對的精度輸入；超過 N 個就不能再建；重複提醒要設冷卻時間。
- **隱私**：鎖定畫面上的通知內容，不要包含資產金額這類敏感資訊。

> [!TIP]
> 面試時可以這樣說：「通知權限我會等使用者第一次建立提醒時才問，因為一開 App 就問很容易被拒絕，之後很難再要。推播 token 登入後上傳、登出時解除綁定，不然換帳號後別人的提醒會推到這台手機。通知文字用 loc-key，由手機依語言組出來。」

### 5. 有哪些例外情況？

| 情況 | 怎麼處理 |
|---|---|
| 價格一下子跳過門檻 | 用「跨過」判斷，觸發一次 |
| 使用者有好幾台裝置 | 推播到這個使用者所有還有效的 token |
| 登出、換帳號 | 登出時解除 token 綁定；沒解除的話，別人的提醒會推到這台裝置，這是隱私問題 |
| token 失效 | APNs／FCM 回報 token 無效時，伺服器把它刪掉 |
| 使用者在系統設定關掉通知 | App 內顯示「通知未開啟」，並引導到設定頁 |
| 交易對下架 | 提醒自動失效，並告訴使用者 |

> [!TIP]
> 面試時可以這樣說：「例外情況我會想到幾個：價格一下子跳過門檻，用跨過判斷、只觸發一次；使用者有好幾台裝置，就推播到所有還有效的 token；登出時要解除 token 綁定，不然別人的提醒會推到這台手機，這是隱私問題；使用者在系統設定關掉通知，App 內要提示並引導到設定頁。」

### 6. 怎麼測試和監控？

- **伺服器**：判斷器的單元測試，包括價格跳過門檻、同時觸發多個、重複觸發的情況。
- **App**：推播內容的解析、點擊後要打開哪一頁的單元測試；用 APNs 的 sandbox 環境做端到端測試（從伺服器發推播到手機點開）。
- **上線後監控**：從「價格到達」→「推播送出」→「送達手機」→「使用者點開」，每一步的延遲和比例；token 失效的比例。

> [!TIP]
> 面試時可以這樣說：「伺服器端我會測判斷器，包括價格跳過門檻、同時觸發多個和重複觸發；App 端測推播內容的解析和點擊後要打開哪一頁，再用 APNs 的 sandbox 環境做端到端測試。上線後監控從價格到達、推播送出、送達手機到使用者點開，每一步的延遲和比例，還有 token 失效的比例。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Design04PriceAlerts**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Design04PriceAlerts**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Design/Design04-PriceAlerts/`，解答在 `labs/ios/Solution/Design/Design04-PriceAlerts/`；Android 你寫在 `labs/android/practice/design/design04-price-alerts/`，解答在 `labs/android/solution/design/design04-price-alerts/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的推播**：這個單元的演練都是假的伺服器、假的推播服務、編好的價格序列，每次執行的結果都一樣。

這個單元的重點是「誰在看價格」：答案是伺服器。App 端的細節（token、前景去重、silent push、深層連結）前面的單元已經練過（清單放在這一段的最後）。這裡的 4 個演練練的是 App 和伺服器接在一起的地方：判斷放在哪裡、判斷怎麼寫、App 改了條件伺服器要怎麼跟上、推播送給好幾台裝置時一台失敗會怎樣。

### 演練 1：App 被關掉之後，價格到了卻沒有提醒（第 2 題）

**檔案**：`L01_ServerSideJudge.swift`（iOS）、`L01_ServerSideJudge.kt`（Android）

**情境**：使用者建立兩個提醒：a1「BTC 高於 9,960,000 円」、a2「BTC 高於 10,000,000 円」。價格依序是 9,900,000、9,970,000、10,020,000、10,050,000。在第三個價格出現之前，使用者把 App 從多工畫面（Android 是最近使用的 App 清單）滑掉，App 被關閉。行情只會送到還活著的 App；伺服器的價格串流則一直都在。手機的通知中心不管通知是 App 自己發的本機通知，還是伺服器透過 APNs（Apple 的推播服務）或 FCM（Firebase Cloud Messaging，Google 的推播服務）送來的推播，都看得到；但本機通知要靠 App 活著才發得出來。伺服器那一端的比對（價格 >= 門檻，而且還沒觸發過，才推播）已經寫好。

**怎麼寫**

1. **Bug 版**：`createAlert` 把提醒存在 App 裡；`onPrice` 在 App 裡走過所有提醒，價格 >= 門檻、而且還沒觸發過的，記下已觸發，並發通知。
2. **修正版**：`createAlert` 把提醒送到伺服器（`server.register`）；App 的 `onPrice` 什麼都不做。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | a1 在 App 還活著的 9,970,000 觸發；a2 到價時 App 已經被關掉，沒有人判斷：「最後：手機收到 1 則通知：a1」 |
| 修正版 | 伺服器一直在看價格，a1、a2 都推播到手機：「最後：手機收到 2 則通知：a1、a2」 |

**為什麼**：App 被關掉之後，不會有任何一行程式在跑。就算 App 只是在背景，iOS 會很快暫停它，Android 有 Doze（手機閒置時，系統集中延後背景工作）這類省電機制；`BGAppRefreshTask` 和 WorkManager 都由系統決定什麼時候執行，不能當成「到價就通知」的機制（見 Platform03 演練 2、3）。所以正確的分工是：App 只負責把「哪個交易對、什麼條件」送到伺服器；伺服器一直在看即時價格，條件成立就用 APNs 或 FCM 推播。這和 Advanced08 演練 1 是同一個道理：手機上的 App 不是可靠的環境，重要的判斷放在伺服器。

**iOS 解答**

```lab-solution
ios/Solution/Design/Design04-PriceAlerts/L01_ServerSideJudge.swift#bug
```

```lab-solution
ios/Solution/Design/Design04-PriceAlerts/L01_ServerSideJudge.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/design/design04-price-alerts/L01_ServerSideJudge.kt#bug
```

```lab-solution
android/solution/design/design04-price-alerts/L01_ServerSideJudge.kt#fixed
```

### 演練 2：價格一下子跳過門檻，提醒沒有響（第 3 題）

**檔案**：`L02_CrossingBook.swift`（iOS）、`L02_CrossingBook.kt`（Android）

**情境**：伺服器替這個交易對保存一組提醒。「高於」提醒：A1 10,000,000、A2 10,100,000、A3 10,200,000；「低於」提醒：B1 9,800,000、B2 9,700,000（加入的順序是打亂的：A2、A1、A3、B2、B1）。價格依序變成 9,950,000、10,030,000（一下子跳過 A1）、10,250,000（一下子跳過 A2 和 A3）、10,250,000（伺服器重送了同一筆）、9,990,000、9,800,000（剛好等於 B1 的門檻）、9,600,000（跳過 B2）。看每個新價格觸發了哪些提醒，以及最後一共觸發幾個（正確是 5 個，每個只觸發一次）。

**怎麼寫**

1. **Bug 版**：用「門檻 → 提醒」的字典存（`byThreshold`），每個新價格去字典裡找「剛好等於這個價格」的提醒（iOS 用 `removeValue(forKey: price)`，Android 用 `remove(price)`）。
2. **修正版**：依方向各存一個排序好的清單（`above`：門檻由小到大；`below`：門檻由大到小）。新價格進來時，從清單最前面開始，門檻已經被跨過（`above` 是門檻 <= 價格，`below` 是門檻 >= 價格）的全部取出來並從清單移除，遇到還沒跨過的就停。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 只有價格剛好踩到門檻的 9,800,000 觸發了 B1，其餘四個永遠不響：「最後：一共觸發 1 個提醒：B1」 |
| 修正版 | 10,030,000 觸發 A1；10,250,000 一次觸發 A2、A3；重送的 10,250,000 不再觸發；9,800,000 觸發 B1；9,600,000 觸發 B2：「最後：一共觸發 5 個提醒：A1、A2、A3、B1、B2」 |

**為什麼**：價格是一跳一跳的，不是連續的：可能直接從 99 跳到 101，從來沒有剛好等於 100。所以判斷要用「跨過」（價格 >= 門檻，或價格 <= 門檻），不能用「等於」。把門檻排好順序有兩個好處：每個新價格只要從最前面取，取到還沒跨過的就停，不用把全部提醒都檢查一次；取出來的同時從清單移除，同一個提醒就只會觸發一次，伺服器重送同一筆價格或重試時，才不會重複推播。真正的伺服器還要把觸發狀態存起來，重啟之後也不會重發。

**iOS 解答**

```lab-solution
ios/Solution/Design/Design04-PriceAlerts/L02_CrossingBook.swift#bug
```

```lab-solution
ios/Solution/Design/Design04-PriceAlerts/L02_CrossingBook.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/design/design04-price-alerts/L02_CrossingBook.kt#bug
```

```lab-solution
android/solution/design/design04-price-alerts/L02_CrossingBook.kt#fixed
```

### 演練 3：把提醒改成 1100 萬，伺服器卻還用舊的門檻（第 2 題）

**檔案**：`L03_AlertVersion.swift`（iOS）、`L03_AlertVersion.kt`（Android）

**情境**：伺服器上的提醒 a1：BTC 高於 10,000,000 円（版本 0）。使用者連續改了兩次門檻：先改成 10,500,000（修改 1），不到一秒又改成 11,000,000（修改 2）。兩個請求走不同的路徑：修改 1 的第一次嘗試逾時、重試過，所以伺服器先收到修改 2，後收到修改 1。之後價格漲到 10,700,000 円：使用者最後設的是 11,000,000，這個價格不該響。

**怎麼寫**

1. **Bug 版**：App 端沒有版本號（已經寫好）；伺服器的 `receive` 收到什麼，就把 `threshold` 蓋成什麼。
2. **修正版**：App 端的 `makeUpdate` 每次把版本號加 1 再送出；伺服器的 `receive` 只接受版本號比目前大的修改（舊的直接忽略），並同時更新 `version` 和 `threshold`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 修改 1 晚到，又蓋掉修改 2，門檻變成 10,500,000，價格 10,700,000 觸發了提醒：「最後：伺服器的門檻是 10,500,000（使用者最後設的是 11,000,000），價格 10,700,000 觸發了提醒」 |
| 修正版 | 修改 1（版本 1）比目前的版本 2 舊，被忽略：「最後：伺服器的門檻是 11,000,000（使用者最後設的是 11,000,000），價格 10,700,000 沒有觸發提醒」 |

**為什麼**：判斷放在伺服器之後，伺服器上存的那份條件就成了「真正生效的條件」，App 送上去的每一次修改，都要讓它和使用者最後設的一致。網路請求會逾時、會重試、會走不同的路徑，到達的順序不保證等於送出的順序，「後到的蓋掉先到的」就會讓舊的條件復活。每次修改帶一個遞增的版本號，伺服器只接受比目前新的，舊的忽略，問題就消失了。為什麼不用手機的時間（updatedAt）當版本？手機時間可能不準（見 Exchange04 演練 6）；遞增的版本號不依賴時鐘。同一個帳號的好幾台裝置都能改時，版本要由伺服器決定（例如 HTTP 的 ETag 與 If-Match），這個演練只看單一裝置。

**iOS 解答**

```lab-solution
ios/Solution/Design/Design04-PriceAlerts/L03_AlertVersion.swift#bug
```

```lab-solution
ios/Solution/Design/Design04-PriceAlerts/L03_AlertVersion.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/design/design04-price-alerts/L03_AlertVersion.kt#bug
```

```lab-solution
android/solution/design/design04-price-alerts/L03_AlertVersion.kt#fixed
```

### 演練 4：舊手機的 token 失效，其他手機也收不到提醒（第 5 題）

**檔案**：`L04_DeviceTokens.swift`（iOS）、`L04_DeviceTokens.kt`（Android）

**情境**：一個使用者登記了三台裝置的推播 token，順序是：舊裝置（已經解除安裝，推播服務回報 token 無效）、平板、新手機。價格兩次達到他的提醒條件，伺服器各發一次推播給他所有的裝置。假的推播服務：送給無效的 token 會丟出 unregistered 錯誤（真的 FCM 是 UNREGISTERED，APNs 是 HTTP 410），其他 token 正常送達。

**怎麼寫**

1. **Bug 版**：用一個迴圈依序對每個 token 送推播，整個迴圈只包在一個 `do/catch`（Android 是 `try/catch`）裡。
2. **修正版**：每個 token 各自 `do/catch`：失敗不影響其他裝置；收到 unregistered 錯誤，就把這個 token 從清單刪掉，下次不再送。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第一個 token 失敗就中斷整個迴圈，兩則提醒都只嘗試送給失效的 token，其他兩台一則都沒收到。iOS：「最後：伺服器共嘗試送出 2 次；iPad 收到 0 則、iPhone-new 收到 0 則；失效的 token 還在清單裡」（Android 的名稱是 tablet、phone-new） |
| 修正版 | 第一則送 3 次（舊裝置失敗後被刪掉），第二則只送 2 次。iOS：「最後：伺服器共嘗試送出 5 次；iPad 收到 2 則、iPhone-new 收到 2 則；失效的 token 已刪掉」 |

**為什麼**：使用者換手機、重新安裝 App 之後，舊的 token 就失效了，伺服器手上的清單卻不會自己更新。逐一推播的迴圈如果一個失敗就整批中斷，只要排在前面的是失效的 token，這個使用者所有的裝置都收不到提醒，而且他不會得到任何錯誤訊息，只會覺得「提醒壞了」。每個 token 各自處理失敗，並在 APNs 或 FCM 回報 token 無效時把它刪掉，才符合筆記的「推播到這個使用者所有還有效的 token」。登出時主動解除綁定、換 token 時更新，見 Platform03 演練 4。

**iOS 解答**

```lab-solution
ios/Solution/Design/Design04-PriceAlerts/L04_DeviceTokens.swift#bug
```

```lab-solution
ios/Solution/Design/Design04-PriceAlerts/L04_DeviceTokens.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/design/design04-price-alerts/L04_DeviceTokens.kt#bug
```

```lab-solution
android/solution/design/design04-price-alerts/L04_DeviceTokens.kt#fixed
```

第 1 題（需求）是說明題，第 6 題的測試與監控是流程與度量題，沒有演練。通知權限要等使用者第一次建立提醒才問、Android 的 notification channel，這類要靠系統對話框與設定畫面的項目，沒有可以穩定自動檢查的演練。其他題目的 bug，前面的單元已經練過，這個單元不重複：

- **第 2 題**的背景執行為什麼不可靠：`BGAppRefreshTask` 的用法（Platform03 演練 2，只有 iOS）、週期性工作的最短間隔（Platform03 演練 3，只有 Android）。
- **第 3 題**的「重複提醒要有冷卻時間」：價格在門檻附近上下跳，同一個提醒響了好幾次（Platform03 演練 6）。
- **第 4 題**（App 端）：推播 token 的生命週期與登出解除綁定（Platform03 演練 4）、App 開著時同一個提醒顯示兩次（Platform03 演練 7）、silent push 與 FCM 訊息的格式（Platform03 演練 5）、點通知打開正確的頁面（Platform06 演練 1）、未登入時先記下目標，登入後再繼續（Platform06 演練 4）。
- **第 5 題**的登出、換帳號：Platform03 演練 4。

## 常見陷阱

- **以為 App 可以在背景定期抓價格來判斷。** 背景執行不可靠，iOS 會暫停 App，Android 有 Doze。判斷要放在伺服器。
- **登出時沒有解除 token。** 換帳號後，前一個帳號的提醒會推到這台裝置，是隱私問題。
- **一打開 App 就要通知權限。** 使用者還不知道為什麼要通知，很容易拒絕；iOS 被拒絕後就不能再跳出系統的詢問視窗；Android 13 起被拒絕兩次後也不會再顯示，之後只能引導使用者自己去設定。
- **推播文字在伺服器寫死成一種語言。** 要用 loc-key，讓手機依使用者的語言顯示。

## 面試官想聽到的

1. 正確的分工：伺服器判斷價格，App 負責設定和顯示。
2. 推播的細節：權限的時機、token 的生命週期、notification channel、多語系的 key。
3. 隱私與安全：登出時解除綁定、鎖定畫面不顯示敏感資訊。

> [!FIELD]
> 在一個社交 App，雙平台都用 Firebase，處理過 FCM 與 APNs 的推播：登入後上傳 token、App 在前景時自己顯示橫幅。價格提醒的 App 端是同一套技術，差別只是觸發條件在伺服器。那個 App 也做過「廣告投放的 Deep Link 行銷導流」，點擊後打開正確頁面的經驗可以直接用上。

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 推播通知 | push notification | プッシュ通知 |
| 推播 token | device token／FCM token | デバイストークン |
| 通知權限 | notification permission | 通知の許可 |
| 價格提醒 | price alert | 価格アラート |
| 通知類別（Android） | notification channel | 通知チャネル |
| 深層連結 | deep link | ディープリンク |
