// 由 labs/topics.yaml 產生（npm run topics:gen），不要手改。
// App 首頁的「四大主題」：記憶體 → 效能 → 架構 → UI/UX，每一項列出該寫的演練。

public struct JdFocusLab: Hashable {
    public let code: String
    public let labID: String
}

public struct JdFocusGroup {
    public let title: String
    public let note: String?
    public let labs: [JdFocusLab]
}

public struct JdFocusTopic: Identifiable {
    public let id: String
    public let title: String
    public let why: String
    public let groups: [JdFocusGroup]
}

public enum JdFocus {
    public static let topics: [JdFocusTopic] = [
        JdFocusTopic(id: "memory", title: "記憶體管理", why: "交易所 App 常駐行情畫面、圖片多、WebSocket 常開，記憶體問題最容易在線上才爆。面試會問：洩漏怎麼發生、怎麼找、怎麼證明修好了；圖片為什麼最吃記憶體；App 為什麼被系統殺掉。", groups: [
            JdFocusGroup(title: "iOS：參考計數與 retain cycle", note: "先把 ARC 的洩漏寫過一輪。每個演練都用 LiveObjects 看物件有沒有被釋放，再用 Xcode 的 Memory Graph 看是誰抓著。", labs: [
                JdFocusLab(code: "Swift02ARC", labID: "L01"),
                JdFocusLab(code: "Swift02ARC", labID: "L02"),
                JdFocusLab(code: "Swift02ARC", labID: "L04"),
                JdFocusLab(code: "Swift02ARC", labID: "L05"),
                JdFocusLab(code: "Swift02ARC", labID: "L06"),
                JdFocusLab(code: "Swift02ARC", labID: "L07"),
                JdFocusLab(code: "Swift02ARC", labID: "L08"),
                JdFocusLab(code: "Swift02ARC", labID: "L09"),
                JdFocusLab(code: "Swift02ARC", labID: "L10"),
                JdFocusLab(code: "Swift02ARC", labID: "L11"),
                JdFocusLab(code: "Swift02ARC", labID: "L13"),
                JdFocusLab(code: "Swift04StructuredConcurrency", labID: "L01"),
                JdFocusLab(code: "Swift06AsyncStream", labID: "L04"),
                JdFocusLab(code: "Swift08SwiftUIIdentity", labID: "L06"),
                JdFocusLab(code: "Swift09UIKitInterop", labID: "L02"),
                JdFocusLab(code: "Swift09UIKitInterop", labID: "L04"),
                JdFocusLab(code: "Swift09UIKitInterop", labID: "L05"),
                JdFocusLab(code: "Swift11ObjC", labID: "L01"),
                JdFocusLab(code: "Swift11ObjC", labID: "L03"),
            ]),
            JdFocusGroup(title: "Android：被活得更久的東西抓著", note: "Android 有 GC，洩漏是「該回收的物件被單例、scope、listener 抓著」。用 LeakCanary 或 WeakReference 加 GC 驗證。", labs: [
                JdFocusLab(code: "Platform01Lifecycle", labID: "L05"),
                JdFocusLab(code: "Platform08Modules", labID: "L01"),
            ]),
            JdFocusGroup(title: "記憶體用量：圖片、快取、累積", note: "洩漏之外的另一半：不是沒釋放，而是用太多。圖片解碼後的大小、快取沒有上限、長時間累積。", labs: [
                JdFocusLab(code: "Advanced03Memory", labID: "L02"),
                JdFocusLab(code: "Advanced07AppSize", labID: "L02"),
                JdFocusLab(code: "Advanced03Memory", labID: "L01"),
                JdFocusLab(code: "Advanced03Memory", labID: "L04"),
                JdFocusLab(code: "Swift02ARC", labID: "L12"),
                JdFocusLab(code: "Swift03Generics", labID: "L04"),
                JdFocusLab(code: "Swift01ValueSemantics", labID: "L02"),
                JdFocusLab(code: "Swift01ValueSemantics", labID: "L04"),
            ]),
            JdFocusGroup(title: "線上怎麼看", note: "被系統殺掉不會有 crash 報告。OOM 怎麼算、怎麼和 crash 分開看。", labs: [
                JdFocusLab(code: "Advanced06Crash", labID: "L06"),
            ]),
        ]),
        JdFocusTopic(id: "performance", title: "效能優化", why: "面試最常從「畫面卡」追問：卡在哪一格、怎麼量、怎麼證明優化有效。行情列表每秒幾十次更新是交易所 App 的招牌問題。", groups: [
            JdFocusGroup(title: "先會量：沒有數字就沒有優化", note: "平均值會騙人。先學會看 p90／p95、hitch、frame budget，再動手改。", labs: [
                JdFocusLab(code: "Advanced01Startup", labID: "L01"),
                JdFocusLab(code: "Advanced01Startup", labID: "L05"),
                JdFocusLab(code: "Advanced02Jank", labID: "L01"),
                JdFocusLab(code: "Platform02Threading", labID: "L02"),
                JdFocusLab(code: "Advanced10Observability", labID: "L03"),
            ]),
            JdFocusGroup(title: "啟動時間", note: nil, labs: [
                JdFocusLab(code: "Advanced01Startup", labID: "L02"),
                JdFocusLab(code: "Advanced01Startup", labID: "L03"),
            ]),
            JdFocusGroup(title: "主執行緒：什麼都不該在上面做", note: "卡頓的根源九成是主執行緒被占住：同步 I/O、重運算、鎖、runBlocking。", labs: [
                JdFocusLab(code: "Platform02Threading", labID: "L01"),
                JdFocusLab(code: "Platform02Threading", labID: "L03"),
                JdFocusLab(code: "Platform02Threading", labID: "L05"),
                JdFocusLab(code: "Swift05Actors", labID: "L04"),
                JdFocusLab(code: "Swift05Actors", labID: "L07"),
                JdFocusLab(code: "Advanced04Races", labID: "L04"),
                JdFocusLab(code: "Advanced06Crash", labID: "L05"),
            ]),
            JdFocusGroup(title: "高頻行情：合併、節流、背壓", note: "每一筆都更新畫面一定卡。只留最新、固定頻率送到畫面、慢的消費者不能拖慢別人。", labs: [
                JdFocusLab(code: "Platform02Threading", labID: "L08"),
                JdFocusLab(code: "Advanced05HighFrequency", labID: "L01"),
                JdFocusLab(code: "Advanced05HighFrequency", labID: "L02"),
                JdFocusLab(code: "Advanced05HighFrequency", labID: "L04"),
                JdFocusLab(code: "Swift06AsyncStream", labID: "L01"),
                JdFocusLab(code: "Swift06AsyncStream", labID: "L06"),
                JdFocusLab(code: "Exchange06RealtimeUI", labID: "L01"),
                JdFocusLab(code: "Design02MarketData", labID: "L03"),
            ]),
            JdFocusGroup(title: "列表與重組：只重畫有變的那一列", note: "SwiftUI 的 body 重算、Compose 的重組，都用計數器證明「改了之後只有那一列重畫」。", labs: [
                JdFocusLab(code: "Swift07SwiftUIState", labID: "L05"),
                JdFocusLab(code: "Swift07SwiftUIState", labID: "L06"),
                JdFocusLab(code: "Swift08SwiftUIIdentity", labID: "L03"),
                JdFocusLab(code: "Swift08SwiftUIIdentity", labID: "L04"),
                JdFocusLab(code: "Swift08SwiftUIIdentity", labID: "L05"),
                JdFocusLab(code: "Swift08SwiftUIIdentity", labID: "L07"),
                JdFocusLab(code: "Advanced02Jank", labID: "L02"),
                JdFocusLab(code: "Advanced02Jank", labID: "L04"),
                JdFocusLab(code: "Swift09UIKitInterop", labID: "L01"),
                JdFocusLab(code: "Exchange05OrderBook", labID: "L08"),
                JdFocusLab(code: "Exchange06RealtimeUI", labID: "L04"),
            ]),
            JdFocusGroup(title: "演算法與資料結構的成本", note: "Live coding 也會考：資料變 10 倍，成本變幾倍？用計數器算比較次數，不猜。", labs: [
                JdFocusLab(code: "Swift01ValueSemantics", labID: "L02"),
                JdFocusLab(code: "Swift01ValueSemantics", labID: "L04"),
                JdFocusLab(code: "Swift03Generics", labID: "L04"),
                JdFocusLab(code: "Coding01DataStructures", labID: "L03"),
                JdFocusLab(code: "Coding01DataStructures", labID: "L05"),
                JdFocusLab(code: "Coding02TimeStreams", labID: "L04"),
                JdFocusLab(code: "Coding02TimeStreams", labID: "L05"),
                JdFocusLab(code: "Exchange07Kline", labID: "L07"),
                JdFocusLab(code: "Advanced05HighFrequency", labID: "L05"),
            ]),
            JdFocusGroup(title: "App 體積", note: nil, labs: [
                JdFocusLab(code: "Advanced07AppSize", labID: "L01"),
                JdFocusLab(code: "Advanced07AppSize", labID: "L03"),
                JdFocusLab(code: "Advanced07AppSize", labID: "L04"),
            ]),
        ]),
        JdFocusTopic(id: "architecture", title: "行動端架構設計", why: "這一項面試多半用「講」的：怎麼切層、狀態放哪、怎麼讓三個畫面的數字一致、舊程式怎麼逐步換掉。演練負責讓你講的時候有具體的 bug 可以舉。系統設計案例也歸在這裡。", groups: [
            JdFocusGroup(title: "單一資料來源與單向資料流", note: "狀態只有一份、只從一個方向改。三個畫面數字不一樣、按鈕狀態對不上，都是這裡出錯。", labs: [
                JdFocusLab(code: "Design03OrderAssets", labID: "L01"),
                JdFocusLab(code: "Design03OrderAssets", labID: "L02"),
                JdFocusLab(code: "Design03OrderAssets", labID: "L03"),
                JdFocusLab(code: "Advanced09Architecture", labID: "L03"),
                JdFocusLab(code: "Swift07SwiftUIState", labID: "L01"),
                JdFocusLab(code: "Swift07SwiftUIState", labID: "L02"),
                JdFocusLab(code: "Swift07SwiftUIState", labID: "L03"),
                JdFocusLab(code: "Exchange08OrderFlow", labID: "L01"),
                JdFocusLab(code: "Exchange08OrderFlow", labID: "L03"),
            ]),
            JdFocusGroup(title: "依賴方向、DI、模組邊界", note: "誰可以 import 誰。依賴反了，測一個畫面要起整個 App。", labs: [
                JdFocusLab(code: "Advanced09Architecture", labID: "L04"),
                JdFocusLab(code: "Advanced09Architecture", labID: "L06"),
                JdFocusLab(code: "Advanced09Architecture", labID: "L07"),
                JdFocusLab(code: "Platform08Modules", labID: "L01"),
                JdFocusLab(code: "Advanced09Architecture", labID: "L02"),
                JdFocusLab(code: "Swift09UIKitInterop", labID: "L03"),
                JdFocusLab(code: "Swift09UIKitInterop", labID: "L05"),
                JdFocusLab(code: "Testing01Strategy", labID: "L01"),
                JdFocusLab(code: "Testing01Strategy", labID: "L02"),
            ]),
            JdFocusGroup(title: "多畫面共用一條連線（Repository 層）", note: "系統設計案例一的核心：訂閱的引用計數、快取和推送的先後、慢的訂閱者。", labs: [
                JdFocusLab(code: "Design02MarketData", labID: "L01"),
                JdFocusLab(code: "Design02MarketData", labID: "L02"),
                JdFocusLab(code: "Design02MarketData", labID: "L04"),
                JdFocusLab(code: "Swift06AsyncStream", labID: "L03"),
                JdFocusLab(code: "Swift06AsyncStream", labID: "L05"),
            ]),
            JdFocusGroup(title: "架構演進與風險控制", note: "strangler 式替換、adapter 隔離舊 SDK、feature flag 的一致性、kill switch。", labs: [
                JdFocusLab(code: "Advanced09Architecture", labID: "L01"),
                JdFocusLab(code: "Advanced09Architecture", labID: "L05"),
                JdFocusLab(code: "Advanced10Observability", labID: "L05"),
                JdFocusLab(code: "Platform07Release", labID: "L03"),
            ]),
            JdFocusGroup(title: "系統設計案例三：提醒要放在伺服器", note: nil, labs: [
                JdFocusLab(code: "Design04PriceAlerts", labID: "L01"),
                JdFocusLab(code: "Design04PriceAlerts", labID: "L03"),
                JdFocusLab(code: "Design04PriceAlerts", labID: "L04"),
                JdFocusLab(code: "Design03OrderAssets", labID: "L04"),
            ]),
        ]),
        JdFocusTopic(id: "uiux", title: "UI/UX", why: "交易所 App 的 UI 有自己的規矩：價格閃爍方向、數字格式、輸入的數量不能被清掉、舊資料要標示。面試官會從「這個畫面哪裡會讓使用者下錯單」追問。", groups: [
            JdFocusGroup(title: "狀態不能莫名其妙消失或重置", note: "identity 錯了、key 錯了、effect 的 key 寫錯，使用者打到一半的數量就不見。", labs: [
                JdFocusLab(code: "Swift08SwiftUIIdentity", labID: "L01"),
                JdFocusLab(code: "Swift08SwiftUIIdentity", labID: "L02"),
                JdFocusLab(code: "Platform01Lifecycle", labID: "L03"),
                JdFocusLab(code: "Platform01Lifecycle", labID: "L04"),
            ]),
            JdFocusGroup(title: "副作用放錯地方，畫面行為就怪", note: nil, labs: [
                JdFocusLab(code: "Swift07SwiftUIState", labID: "L04"),
            ]),
            JdFocusGroup(title: "交易畫面的體驗：不能讓人下錯單", note: "價格過期要說、成交一半要說「部分」、取消中不能說「已取消」、滑價要看得到。", labs: [
                JdFocusLab(code: "Exchange01Orders", labID: "L01"),
                JdFocusLab(code: "Exchange01Orders", labID: "L02"),
                JdFocusLab(code: "Exchange01Orders", labID: "L04"),
                JdFocusLab(code: "Exchange04Reconnect", labID: "L06"),
                JdFocusLab(code: "Exchange08OrderFlow", labID: "L05"),
                JdFocusLab(code: "Exchange06RealtimeUI", labID: "L02"),
                JdFocusLab(code: "Exchange06RealtimeUI", labID: "L03"),
                JdFocusLab(code: "Exchange05OrderBook", labID: "L03"),
                JdFocusLab(code: "Exchange05OrderBook", labID: "L07"),
                JdFocusLab(code: "Coding02TimeStreams", labID: "L02"),
                JdFocusLab(code: "Coding02TimeStreams", labID: "L03"),
            ]),
            JdFocusGroup(title: "數字與文字怎麼顯示、放大、唸出來", note: "日圓沒有小數、万／億、全形輸入、科學記號、捨入方向，顯示錯了就是金額錯了；字體放大不能截斷，螢幕閱讀器要唸得出漲跌。", labs: [
                JdFocusLab(code: "Exchange02Precision", labID: "L05"),
                JdFocusLab(code: "Exchange02Precision", labID: "L06"),
                JdFocusLab(code: "Exchange02Precision", labID: "L07"),
                JdFocusLab(code: "Exchange13Localization", labID: "L01"),
                JdFocusLab(code: "Exchange13Localization", labID: "L02"),
                JdFocusLab(code: "Exchange13Localization", labID: "L03"),
                JdFocusLab(code: "Exchange13Localization", labID: "L05"),
                JdFocusLab(code: "Exchange13Localization", labID: "L06"),
                JdFocusLab(code: "Swift10LanguageFeatures", labID: "L01"),
                JdFocusLab(code: "Exchange06RealtimeUI", labID: "L05"),
                JdFocusLab(code: "Exchange06RealtimeUI", labID: "L06"),
            ]),
            JdFocusGroup(title: "圖表", note: nil, labs: [
                JdFocusLab(code: "Exchange07Kline", labID: "L06"),
                JdFocusLab(code: "Exchange07Kline", labID: "L07"),
                JdFocusLab(code: "Exchange07Kline", labID: "L09"),
            ]),
            JdFocusGroup(title: "安全相關的畫面行為", note: nil, labs: [
                JdFocusLab(code: "Advanced08Security", labID: "L03"),
                JdFocusLab(code: "Advanced08Security", labID: "L04"),
                JdFocusLab(code: "Advanced08Security", labID: "L05"),
            ]),
        ]),
    ]
}
