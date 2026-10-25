// L01：點了推播裡的連結卻沒反應（冷啟動時，Universal Link 放在另一個地方）
// 對應複習站：Platform06DeepLink 第 3 題「App 怎麼接到連結？App 沒開著（冷啟動）和已經開著有什麼不同？」
//
// 情境：App 用 scene 架構（SceneDelegate）。連結進來有三個入口，不管從哪個入口進來，都要交給同一個 Router：
//       1. App 沒開著，被連結啟動（冷啟動）：scene(_:willConnectTo:options:) 的 connectionOptions。
//          自訂 scheme 放在 urlContexts，Universal Link 放在 userActivities，是兩個不同的地方。
//       2. App 已經開著，自訂 scheme：scene(_:openURLContexts:)。
//       3. App 已經開著，Universal Link：scene(_:continue:)。
//       演練用簡化的假 SceneDelegate 模擬這三個入口（不依賴真的 UIKit），Router 只記下「收到什麼連結」。
//       劇本：App 先後被自訂 scheme、Universal Link 各冷啟動一次，接著 App 開著時再收到一個自訂 scheme 連結、一個 Universal Link，共 4 個連結。
//
// 你要寫：
//   ① Bug 版：willConnectTo 只處理 options.urlContexts（自訂 scheme），每個連結交給 router.handle。
//   ② 修正版：willConnectTo 處理 options.urlContexts，也處理 options.userActivities（Universal Link）。
// 只改有 TODO 的地方。解答：Solution/Platform/Platform06-DeepLink/L01_EntryPoints.swift
import Foundation
import LabKit

/// 簡化的 UIScene.ConnectionOptions（兩個版本共用）：真實的 userActivities 是 NSUserActivity，連結在 webpageURL，這裡直接放網址
private struct ConnectionOptions {
    var urlContexts: [URL] = []
    var userActivities: [URL] = []
}

/// 簡化的 SceneDelegate 三個入口
private protocol SceneHandling {
    func willConnectTo(_ options: ConnectionOptions)
    func openURLContexts(_ urls: [URL])
    func continueUserActivity(_ url: URL)
}

/// 演練流程（兩個版本共用）：兩次冷啟動（每次都是新的 scene），接著 App 開著時再收到兩個連結
private func scenario(_ log: LabLog, makeScene: (P06LinkCollector) -> SceneHandling) {
    let collector = P06LinkCollector()
    let url = { URL(string: $0)! }

    makeScene(collector).willConnectTo(ConnectionOptions(urlContexts: [url("mobilelabs://ticker/btcjpy")]))       // 冷啟動：被自訂 scheme 啟動
    makeScene(collector).willConnectTo(ConnectionOptions(userActivities: [url("https://www.example.com/ticker/ethjpy")]))   // 冷啟動：被 Universal Link 啟動
    let scene = makeScene(collector)
    scene.openURLContexts([url("mobilelabs://alert/ab12cd34")])                  // App 已經開著：自訂 scheme
    scene.continueUserActivity(url("https://www.example.com/alert/ab12cd34"))      // App 已經開著：Universal Link

    let received = collector.urls
    log("Router 收到 \(received.count) 個連結，漏接 \(4 - received.count) 個（共 4 個）")
    for link in received { log("收到：\(link)") }
}

// MARK: - Bug 版
private enum L01Bug {
    final class SceneDelegate: SceneHandling {
        let router: P06LinkCollector
        init(router: P06LinkCollector) { self.router = router }

        func willConnectTo(_ options: ConnectionOptions) {
            // TODO ①：只處理 options.urlContexts（自訂 scheme）：每個連結交給 router.handle（不要處理 userActivities）
        }
        func openURLContexts(_ urls: [URL]) { urls.forEach(router.handle) }
        func continueUserActivity(_ url: URL) { router.handle(url) }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { SceneDelegate(router: $0) }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class SceneDelegate: SceneHandling {
        let router: P06LinkCollector
        init(router: P06LinkCollector) { self.router = router }

        func willConnectTo(_ options: ConnectionOptions) {
            // TODO ②：冷啟動的連結有兩個地方：options.urlContexts（自訂 scheme）和 options.userActivities（Universal Link），
            //        兩個都要把每個連結交給 router.handle
        }
        func openURLContexts(_ urls: [URL]) { urls.forEach(router.handle) }
        func continueUserActivity(_ url: URL) { router.handle(url) }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { SceneDelegate(router: $0) }
    }
}

// MARK: - 演練設定
extension Platform06DeepLink {
    static let l01 = Lab(
        id: "L01",
        title: "點推播的連結冷啟動 App，卻沒有反應",
        question: "第 3 題：App 怎麼接到連結？App 沒開著（冷啟動）和已經開著有什麼不同？",
        goal: "每個入口收到的連結，都要交給同一個 Router，一個都不能漏。冷啟動的連結放在 connectionOptions 的哪裡？",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看 Router 收到幾個連結、漏接哪一個，出現 ✓。",
            "想一想：冷啟動時，自訂 scheme 的連結和 Universal Link 各放在 connectionOptions 的哪個屬性？Bug 版只看了哪一個？",
            "寫 TODO ②，選「修正版」執行，4 個連結都要收到。",
            "進階：SwiftUI 的 .onOpenURL 同時接收自訂 scheme 和 Universal Link，.onContinueUserActivity(NSUserActivityTypeBrowsingWeb) 是另一個入口；UIKit 的 scene 架構要像這個演練一樣自己處理三個入口。",
        ],
        expectBug: "「Router 收到 3 個連結，漏接 1 個（共 4 個）」，沒有收到冷啟動的 https://www.example.com/ticker/ethjpy。",
        expectFixed: "「Router 收到 4 個連結，漏接 0 個（共 4 個）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("Router 收到") }) else { return "演練沒有跑完：要用 scenario(log, makeScene:) 執行" }
            if !lines.contains("收到：mobilelabs://ticker/btcjpy") { return "Bug 版的 willConnectTo 還是要處理 options.urlContexts（自訂 scheme），交給 router.handle" }
            if lines.contains("收到：https://www.example.com/ticker/ethjpy") { return "Bug 還沒重現：willConnectTo 只處理 urlContexts，不要處理 userActivities，冷啟動的 Universal Link 才會漏掉" }
            return lines.contains("Router 收到 3 個連結，漏接 1 個（共 4 個）") ? nil : "Bug 版應該只漏掉冷啟動的 Universal Link"
        },
        checkFixed: { lines in
            lines.contains("Router 收到 4 個連結，漏接 0 個（共 4 個）") ? nil : "4 個連結都要收到：willConnectTo 除了 options.urlContexts，也要處理 options.userActivities"
        }
    )
}
