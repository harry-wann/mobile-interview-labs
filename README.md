# Mobile 面試複習站（交易所 App）

給 iOS／Android 雙平台工程師準備面試用的複習站＋實際演練，情境以加密資產交易所 App 為主（行情、訂單簿、下單、簽名、eKYC、錢包），技術涵蓋 Swift／SwiftUI、Kotlin／Compose、平台對照、資深進階、AI 自動化測試、系統設計、Live Coding。

兩個部分：

- **複習站**（這個目錄）：w3schools 風格的網站。左側單元目錄、每單元的筆記（一題一題）、8 種題型的練習（選擇、填空、預測輸出、排序、可執行的程式題、口說題、速記卡）、SRS 間隔複習、錯題本、模擬面試、AI 面試官評分（本機）。內容在 `content/`（格式見 `content/SCHEMA.md`），引擎只負責驗證、轉換與顯示。
- **實際演練**（`labs/`）：一個 iOS App（Xcode）和一個 Android App（Android Studio），400 多個「真的會出錯的 bug 對照修好的寫法」。每個演練有 Practice（骨架，自己填 TODO）和 Solution（完整解答）兩份，App 裡按執行就有自動檢查。說明見 [`labs/README.md`](labs/README.md)。

內容是繁體中文（台灣用語），口說題附日文版答案。

## 快速開始

```bash
npm install
./scripts/setup-kotlin.sh     # 第一次：下載 Kotlin 編譯器與 coroutines 到 .tools/（Swift 用系統的 swiftc）
npm run dev                   # http://localhost:6030
```

- `npm run dev` 以 `vite --host` 啟動，終端機會列出 `Network:` 網址。
- **手機連線**：手機與電腦連同一個 Wi-Fi，用 **`npm run dev` 在終端機印出的「手機連線（含 token）」網址**開啟，例如 `http://192.168.x.x:6030/?t=<token>`。
  - 區網上的 `/api/answers`、`/api/progress` 需要 token（`data/.lan-token`，第一次啟動時隨機產生，不進 git）：手機第一次用含 `?t=` 的網址開啟後，頁面會把 token 存起來並從網址移除，之後所有請求都帶 `X-Prep-Token`。localhost 免 token；區網沒帶或帶錯 token 一律 401（GET 也擋）。要換新 token：刪掉 `data/.lan-token` 再重啟。
  - 沒有 token 的區網連線仍可讀筆記、做題（進度只存在那支手機），畫面頂部會提示要用含 token 的網址。
  - 手機可以讀筆記、做題、同步進度與「我的口徑」（寫進電腦的 `data/*.json`）。
  - 「執行測試」與「AI 面試官評分」**只開放在這台電腦**（API 只接受 127.0.0.1／::1），手機上會顯示說明。
- 進度與口徑存在 `data/progress.json`、`data/my-answers.json`（原子寫入）。瀏覽器的 localStorage 同步保留一份備份。資料的合併規則見下一節「口徑不會遺失」。
- 如果資料檔 JSON 壞掉，server 不會覆寫：原檔會改名備份成 `data/<name>.corrupt-<timestamp>.json`，並回 500；重新整理後前端會用 localStorage 的內容補回。
- 這兩個檔案已被 `.gitignore` 排除。要備份請用「我的口徑」頁的**匯出**（複製 JSON 到剪貼簿）。

## 指令

| 指令 | 說明 |
|---|---|
| `npm run dev` | 開發伺服器（容錯：有錯誤的單元會被略過，頂部顯示「N 個內容錯誤」；`content/` 有變動會自動重建並就地更新頁面，不整頁重載） |
| `npm run content:check` | 只驗證內容（zod，嚴格，有錯誤 exit 1）。錯誤訊息包含檔案與題目 id |
| `npm run content:build` | 驗證並輸出 `src/generated/content.json` 與 `server-data.json`（dev／build 會自動執行） |
| `npm run content:lint` | 內容作者用的輕量檢查（較寬鬆，`content:check` 才是準的） |
| `npm run tasks:verify -- <id 前綴>` | 編譯執行 code 題：solution 必須全過、starter 必須有失敗，例：`npm run tasks:verify -- exchange-precision` |
| `npm run build` | 一般建置到 `dist/`（本機 preview 用；有內容錯誤時略過該單元並印出警告，`CONTENT_STRICT=1` 改成嚴格） |
| `npm run preview` | 預覽 `dist/`（同樣帶本機 API） |
| `npm run build:static` | 建置靜態版並產出 `artifact/mobile-prep.html`（**嚴格**：有內容錯誤會中止；`CONTENT_LENIENT=1` 可略過有錯的單元） |
| `npm run test:data` | 資料層回歸測試（欄位級合併、history、遷移、隨機多裝置模擬）＋ store 層整合測試（編輯中收到遠端、亂序快照、離開頁面順序、雲端競態、跨行程檔案鎖） |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run topics:gen` | 從 `labs/topics.yaml` 產生兩個 App 首頁「四大主題」的排序 |

## 口徑不會遺失（資料合併規則）

目標：你寫的口徑永遠不會被靜默覆蓋或遺失——電腦、手機（同 Wi-Fi 的本機版或 claude.ai 靜態版）可以同時用。

- **欄位級合併**：每題的 `text`、每個待確認事實、`grade` 各自帶時間戳記（`textAt`、`factsAt[事實 id]`、`gradeAt`），合併時逐欄位取新者，本地缺的欄位保留遠端。所以「舊分頁存一次 AI 評分」不會再蓋掉手機剛改的文字。
- **事實以穩定 id 對齊**：每個待確認事實的 id 是事實原文的 hash（`factIds`，建置時產生）。內容增刪事實不會讓答案錯位。內容改了某個事實的文字（id 變了）時，如果它在同一個位置、這題的事實數量也沒變、新事實還沒有任何資料，答案會自動搬到新事實（舊欄位清空、舊值留在歷史版本，搬移會同步；不確定的不搬）。內容已移除的事實在題目頁與「我的口徑」頁以「已移除的題目事實」顯示，每筆可以「移到目前事實」（整題被移除的列在「內容已移除的題目」）。舊資料（以 index 對齊）在第一次讀取時用當下的內容對應一次。
- **歷史版本**：任何一次合併「輸掉」、被別台覆蓋的 text／fact 都會進 `history`（含時間與來源裝置）。每個欄位最多 10 筆，依「被取代的時間」保留最新的（剛輸掉的離線版本不會先被淘汰）；雲端文件超過 256 KiB 時也會從最舊的歷史開始丟。回答框旁的「歷史版本」可以檢視與還原（還原時目前的內容會先進歷史）。
- **本機版：時間以 server 為準**。前端只送「我改了哪些欄位」（patch）與該欄位最後一次看到的版本（`base`）；server 套用時蓋上自己的時間，`base` 比 server 現存的舊（別台改過）就照樣套用這次編輯，但 server 原本的內容放進 history。還沒送出的欄位存在 localStorage，重新整理、離線都不會忘。
- **靜態版（沒有 server）**：時間戳記合併；寫入時未來時間最多 `now + 60s`，讀到超過 `now + 1 天` 的時間戳當作 now。雲端寫入前一律「先讀 → 欄位級合併 → 再寫」。
- **新鮮度**：前景每 20 秒 pull 一次；視窗 focus／pageshow／切回前景立即 pull；開始編輯某題與按 AI 評分前先 pull 那一題。別台更新了你正在編輯的題目時，只會顯示「另一台裝置更新了這題」，不會直接換掉輸入框的內容；輸入框記住自己開始編輯時的版本，你繼續打字時，對方的版本一定會進歷史版本（本機與雲端都一樣）。
- **過期的快照**：server 的時間戳記逐欄位遞增，所以比本地舊的快照（例如在送出確認之後才到的 GET）不會把本地退回舊版；server 的資料真的倒退（例如資料檔被還原）時，本地較新的版本會補送回去，server 的內容進歷史。
- **雲端寫入**：先讀 → 合併 → 寫 → 讀回確認。讀回來的不含這次寫入時先當成「還沒讀到」（db 不保證寫入後立即讀到新值），狀態維持「同步中」、3 秒後再確認；連續 3 次都不含才確定被別台覆蓋，重新合併再寫。切到背景／離開頁面時走快速路徑：排在所有輸入框把暫存內容存進 store 之後，不先讀、不確認，所有有變動的文件同時寫入；和別台的競態由下次開機／pull 的欄位級合併處理。
- **首次同步前唯讀**：首次同步成功前，回答與事實唯讀並顯示「同步中…」；5 秒沒同步成功就允許編輯（標示「離線編輯，連線後合併」）。
- **進度**：作答次數各裝置各自計數再加總（兩台同時作答不會互蓋），SRS 狀態以最新一次作答為準。單題超過 20 台裝置時，最久沒作答的併進 `~others` 加總（不丟、合併時不重複計算）。
- **字數**：回答上限 100,000 字、事實 5,000 字；超過會拒絕輸入並提示，不會靜默截斷（雲端存檔每份文件上限 256 KiB，中文約 8 萬字；超過時只存本機並提示）。
- **匯入**：「合併」是欄位級（只採用比現有新的欄位、現有缺的補上，輸掉的進歷史）；匯入前自動存快照，可「復原這次匯入」；「以備份完全取代」需要頁面內二次確認。
- **舊格式自動升級**：舊的 `data/*.json` 在第一次讀取時備份成 `<name>.v<舊版本>-<timestamp>.json` 再用新格式重寫；舊的 localStorage（`bt.answers.v1`／`v2`、`bt.progress.v1`／`v2`、`bt.dirty.v2`）在開機時、以及還開著的舊分頁寫入時合併進來（較舊的版本進歷史），存成新格式後才刪除舊 key。升級後第一次同步會用時間戳記推斷一次哪些欄位還沒送出（「已推斷」標記只在推斷真的跑過之後才寫）。舊版頁面送出口徑或進度都會被 server 以 426 拒絕，請它重新整理；它還沒送出的內容在同一個瀏覽器重新整理後會自動合併。靜態版的口徑改存在 `answers3/`，舊路徑 `answers/` 只讀不寫（開機時合併有變動的文件）。
- **dev 與 preview 同時執行**：兩個 server 行程讀寫同一份資料檔時用跨行程的檔案鎖（`data/.lock-*`，持有者行程消失或逾時會自動清除），不會互相覆蓋。
- 多分頁：監聽 `storage` 事件合併其他分頁的變更，不會用舊狀態整份覆寫。
- 回歸測試：`npm run test:data`：純函式（reviewer 的四個情境、舊格式遷移、隨機多裝置模擬、事實 id、作答次數上限、426）＋ store 層整合測試（`scripts/regress-store.ts`：打包後的前端模組在 vm 裡模擬多台裝置，用假的 server／雲端 db／時鐘；另外用真的 server middleware 測 426、資料檔遷移，並開兩個子行程測跨行程檔案鎖）。

## 靜態版（claude.ai Artifact）

`npm run build:static` 做兩件事：

1. `VITE_STATIC=1` + `vite-plugin-singlefile` 產生單一 HTML（`dist-static/index.html`，JS 為 classic IIFE）。
2. `scripts/make-artifact.mjs` 後處理成 `artifact/mobile-prep.html`：移除 `<!doctype>`／`html`／`head`／`body` 與 charset／viewport meta，依序放 `<title>`、`claude:viewport-fit-sides` meta、Google Fonts `<link>`、`<style>`、`#root`、內嵌 `<script>`；檢查沒有外部 script、title 在前 8KB、檔案 < 16MB 並印出大小。

靜態版停用：程式執行、本機 server 同步。提示與解答、匯入／匯出（複製貼上）照常可用，進度存在瀏覽器 localStorage（沙箱不給用時只存在記憶體）。

### 靜態版的雲端存檔與 AI 評分（需要 Artifact 以 `capabilities: {db: {}, user: {}, sample: {}}` 發布）

只用 `await window.claude.use(name)` 取得 capability，不阻塞第一次渲染；沒有 `window.claude`（本機預覽、另存的檔案）或回傳 `null` 時降級成原本的行為。

- **雲端存檔**（`src/lib/cloud.ts`）：`user.id()` 取得 uid 後，資料存在私有路徑 `data/users/<uid>/` 底下：
  `progress/sections/<sectionId>`（進度，每個 section 一份）、`plan`（計畫勾選）、`mock/sessions/<id>`（模擬面試）、`answers3/items/<exerciseId>`（口徑，每題一份；事實以 id 對齊。舊版的 `answers/items/` 只在開機時合併，不再寫入）。
  開機時讀取並與 localStorage 依時間戳合併；寫入 debounce 2 秒、只寫有變動的 entry、同一時間只有一個寫入；
  `unavailable` 隨機延遲後重試一次，`resource_exhausted` 退避，`quota_exceeded` 與 `invalid_argument` 會在畫面上提示，`revoked`／`not_granted` 靜默退回 localStorage。
  頂部標示「靜態版・雲端同步」或「靜態版・僅存本機」；切到背景／離開頁面時立即寫入；瀏覽器不能寫 localStorage 而且沒有雲端時，頂部會警告「關閉即遺失，請定期匯出備份」。
- **AI 評分**（`src/lib/grader.ts`）：`sample.json(prompt, { modelTier: 'default', signal, onText })`。prompt 與本機版相同（角色、題目、要點、參考答案、追問、回答），**不含履歷檔**。
  按下後顯示「思考中…」與「停止」；`not_granted` 時本次造訪隱藏按鈕，`rate_limited` 提示稍後再試，`invalid_json` 顯示原始回覆，不自動重試。結果存進口徑（也會同步到雲端）。
- **本機測試**：`scripts/mock-claude.js` 是假的 `window.claude`（記憶體／localStorage 模擬 db、user、sample，可注入失敗），只用 Playwright 的 `addInitScript` 注入；`make-artifact.mjs` 會檢查正式產物裡沒有它的痕跡。

## AI 面試官評分

`POST /api/grade` 在本機呼叫 `claude` CLI：

```
claude -p --output-format json --tools "" --no-session-persistence --model ${GRADE_MODEL:-sonnet} \
  --setting-sources "" --strict-mcp-config --system-prompt <面試官角色> --json-schema <輸出 schema>
```

- 用 `GRADE_MODEL` 換模型（預設 `sonnet`）。
- 想讓改寫建議引用你的經歷：把履歷存成純文字檔，設環境變數 `RESUME_PATH=/path/to/resume.md`。沒設就只用你這次的回答（履歷不會進 repo）。
- 180 秒的總期限**從收到請求開始算（含排隊時間）**；瀏覽器斷線或按「停止」時 server 會殺掉 claude 子程序（整個 process group），排隊中的請求也會直接放棄。
- 評分只會引用題目、要點、參考答案、你的回答與履歷，不得捏造沒說過的經歷。

## 結構

```
content/                 內容（內容作者維護）；content/plan/days.yaml 是選填的每日計畫（.env 設 VITE_INTERVIEW_DATE 會顯示倒數）
labs/                    iOS／Android 實際演練（見 labs/README.md）
TECH_STACK.md            三個專案（網站、iOS、Android）的 UI 架構、網路、儲存、測試工具一覽
scripts/build-content.ts 內容建置 CLI；scripts/content/ 為 schema（zod）、markdown、shiki、建置邏輯
scripts/make-artifact.mjs 靜態版後處理
runner/                  run-code.mjs（編譯執行）、prelude.*、server-plugin.ts（/api）、content-plugin.ts（虛擬模組＋監看）
src/                     前端（React 19 + TypeScript）
data/                    my-answers.json、progress.json（個人資料，不進 git）
```

## 網址

只用不含斜線的純錨點：`#home` `#plan` `#practice` `#wrong` `#mock` `#answers` `#sec-<sectionId>` `#<unitId>`。

## 疑難排解

- **Kotlin 找不到編譯器**：執行 `./scripts/setup-kotlin.sh`。
- **「N 個內容錯誤」**：點徽章看清單；常見原因是 YAML 純文字裡有「`: `」（請改用引號或 `|`）、缺少同名 `.ex.yaml`。
- **手機打不開**：確認電腦防火牆允許 Node 接受連線，且兩台裝置在同一個網段。
