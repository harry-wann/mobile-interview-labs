// L02：每次打開 App 都跳到同一個活動頁（延遲的深層連結只能用一次）
// 對應複習站：Platform06DeepLink 第 4 題「使用者還沒安裝 App 時，連結要怎麼處理？（deferred deep link）」
//
// 情境：行銷活動的廣告連結，使用者點了之後去商店安裝，第一次打開 App 時要直接到活動指定的行情頁（deferred deep link）。
//       App 靠歸因工具（例如 Airbridge、Adjust）詢問「這個使用者安裝前點過什麼連結」。這個答案每次詢問都一樣，
//       因為歸因工具只知道安裝前點過什麼，不知道 App 有沒有已經用掉。（Android 的 Play Install Referrer 也是每次都回傳同一份。）
//       演練模擬使用者連續冷啟動 3 次；App 用 UserDefaults（演練裡用 LaunchStore 代表，關掉 App 再開也還在）記錄狀態。
// Bug 版：每次冷啟動都問歸因工具，有答案就跳過去。3 次啟動都被帶到活動頁。
// 修正版：第一次處理完就在 LaunchStore 記下「已經處理過」，之後的啟動不再詢問、也不再跳轉。
import Foundation
import LabKit

/// 歸因工具（兩個版本共用）：每次詢問都回傳同一個安裝前點過的連結
private struct AttributionSDK {
    func deferredLink() -> String? { "https://www.example.com/ticker/btcjpy?campaign=autumn" }
}

/// 代表 UserDefaults：App 關掉再開，內容還在（兩個版本共用）
private final class LaunchStore {
    var deferredLinkHandled = false
}

/// 演練流程（兩個版本共用）：連續冷啟動 3 次，每次建立一個新的 handler（App 重新啟動，記憶體裡的東西都沒了），store 是同一個
private func scenario(_ log: LabLog, makeHandler: (LaunchStore, AttributionSDK, @escaping (String) -> Void) -> (() -> Void)) {
    let store = LaunchStore()
    var jumps = 0
    for launch in 1...3 {
        var jumped: String?
        let onLaunch = makeHandler(store, AttributionSDK()) { jumped = $0 }
        onLaunch()
        if let link = jumped {
            jumps += 1
            log("第 \(launch) 次啟動：跳到 \(link)")
        } else {
            log("第 \(launch) 次啟動：照常顯示首頁")
        }
    }
    log("3 次冷啟動，被活動連結帶走 \(jumps) 次")
}

// MARK: - Bug 版
private enum L02Bug {
    static func makeHandler(store: LaunchStore, sdk: AttributionSDK, navigate: @escaping (String) -> Void) -> () -> Void {
        {
            if let link = sdk.deferredLink() {                  // 每次啟動都問，有答案就跳
                navigate(link)
            }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { store, sdk, navigate in makeHandler(store: store, sdk: sdk, navigate: navigate) }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func makeHandler(store: LaunchStore, sdk: AttributionSDK, navigate: @escaping (String) -> Void) -> () -> Void {
        {
            guard !store.deferredLinkHandled else { return }    // 已經處理過了：不再詢問、不再跳轉
            if let link = sdk.deferredLink() {
                store.deferredLinkHandled = true                // 先記下來，再跳轉
                navigate(link)
            }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { store, sdk, navigate in makeHandler(store: store, sdk: sdk, navigate: navigate) }
    }
}

// MARK: - 演練設定
extension Platform06DeepLink {
    static let l02 = Lab(
        id: "L02",
        title: "每次打開 App 都跳到同一個活動頁",
        question: "第 4 題：使用者還沒安裝 App 時，連結要怎麼處理？（deferred deep link）",
        goal: "安裝前點過的連結，只有第一次打開 App 時要帶使用者過去；之後再怎麼冷啟動，都照常顯示首頁。",
        steps: [
            "選「Bug 版」執行，看 3 次冷啟動各自到哪裡。",
            "想一想：歸因工具每次被問，答案會變嗎？App 要自己記住什麼？",
            "選「修正版」執行，只有第 1 次啟動會跳到活動頁。",
            "進階：這個連結是行銷同事設定的、來自外部，跳轉前也要交給 Router 做白名單與參數驗證（見演練 3）。",
        ],
        expectBug: "「第 1、2、3 次啟動：跳到 https://www.example.com/ticker/btcjpy?campaign=autumn」，「3 次冷啟動，被活動連結帶走 3 次」。",
        expectFixed: "第 1 次啟動跳到活動頁，第 2、3 次「照常顯示首頁」，「3 次冷啟動，被活動連結帶走 1 次」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("3 次冷啟動，被活動連結帶走 3 次")
                ? nil : "Bug 還沒重現：每次啟動都問 sdk.deferredLink()，有答案就 navigate，不要記錄是否處理過"
        },
        checkFixed: { lines in
            guard lines.contains("第 1 次啟動：跳到 https://www.example.com/ticker/btcjpy?campaign=autumn") else {
                return "第 1 次啟動要跳到活動頁：沒處理過時，問 sdk.deferredLink() 並 navigate"
            }
            return lines.contains("3 次冷啟動，被活動連結帶走 1 次") ? nil : "只有第 1 次啟動要跳轉：navigate 之前先把 store.deferredLinkHandled 設成 true，之後的啟動一開始就 return"
        }
    )
}
