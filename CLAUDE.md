# CLAUDE.md

Mobile 面試複習站（交易所 App）＋ iOS／Android 實際演練。**這個 repo 是公開的。**

## 語言

- 回覆、內容、註解都用繁體中文（台灣用語）；技術名詞與程式識別字保留原文。
- 日本交易所業務名詞用日文原文並附中文（見 `content/SCHEMA.md`）。

## 公開 repo：不寫可識別的資訊

- 不寫任何真實公司名、產品名、內部網域、ticket 編號、commit hash、可對應到特定人的數字或日期。
- 實務經驗用 `> [!FIELD]` 不具名寫（「在一個即時行情 App 裡…」），見 `content/SCHEMA.md` 2.1。
- 交易所 API 一律寫「Huobi 相容的公開協定」，不指名特定公司。
- 個人資料（履歷、自己的回答）不進 repo；AI 評分要引用履歷時走 `RESUME_PATH`（見 `README.md`）。

## 先讀哪些

| 要做的事 | 先讀 |
|---|---|
| 寫或改單元、練習題 | `content/SCHEMA.md`（照 `swift/01`、`swift/02`、`kotlin/03` 的範本） |
| 寫或改演練 | `labs/README.md`、`labs/AUTHORING.md` |
| 指令、網站結構 | `README.md` |

## 演練（`labs/`）

- iOS、Android **各一個專案、一個 App**，用 module 分區；不要一個單元或一個演練開一個專案。
- Practice 與 Solution 結構一模一樣。**Solution 完整寫好；Practice 是使用者自己要寫的**：型別、函式、流程、log、自動檢查先寫好，只把 Bug 版與修正版的關鍵幾行挖成 TODO。不要替使用者把練習寫完。
- 單元代號是「編號＋單元重點」（`Swift02ARC`、`Kotlin03Coroutines`），不要只寫 `Swift01`。
- 新增演練或單元要加進 `labs/topics.yaml`，再跑 `npm run topics:gen`。順序是 記憶體 → 效能 → 架構 → UI/UX，優先補記憶體與效能。
- 程式碼排版照 `labs/AUTHORING.md` 的「程式碼排版」：80 欄、註解寫在上一行、三行 `// MARK:` 一字不差。
- 結構上有疑問先問，不要自己擴大範圍。

## 驗證

- 內容：`npm run content:check` 要 0 錯誤、0 警告（`content:lint` 是寬鬆版）。
- code 題：`npm run tasks:verify -- <id 前綴>`。
- 網站：`npm run typecheck`、`npm run test:data`。
- 演練：照 `labs/AUTHORING.md` §9。iOS 建置、iOS 模擬器、Android 模擬器只能在 macOS 本機跑；在雲端或 Linux 跑不了的檢查，回報時寫「未驗證」，不要寫成通過。
- 不要另外建立模擬器：iOS 只用 `MobileLabs-Main`，Android 只用一台，`check.sh` 會自動排隊。

## Git

- commit 訊息照現有格式：`<區>/<編號>: <英文說明>`（例 `kotlin/11: rewrite ...`），跨區用 `content:`、`labs:`。
- 在自己的分支工作；推 `main` 前先問，不要 force push `main`。
