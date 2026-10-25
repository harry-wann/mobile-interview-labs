// L02：關掉圖表畫面之後，WKWebView 還留在記憶體裡（沒有在 dismantleUIView 移除 handler）
// 對應複習站：Swift09UIKitInterop 第 3 題「UIViewRepresentable 的每個方法什麼時候被呼叫？Coordinator 是做什麼的？」
//
// 情境：圖表是網頁，用 UIViewRepresentable 包 WKWebView。網頁用 JavaScript 傳訊息回來，所以 Coordinator 註冊成 WKScriptMessageHandler；
//      Coordinator 也要拿著 WKWebView，之後才能呼叫 evaluateJavaScript。
//
// 你要寫：
//   ① Bug 版：把 Coordinator 註冊成 WKScriptMessageHandler，也讓 Coordinator 拿著 WKWebView，但離開畫面時什麼都不清。
//   ② 修正版：在 dismantleUIView 移除註冊的 handler。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift09-UIKitInterop/L02_WebViewHandlerLeak.swift
import SwiftUI
import UIKit
import WebKit
import LabKit

/// 會記錄自己有沒有被釋放的 WKWebView（兩個版本共用）
private final class TrackedWebView: WKWebView {
    init(configuration: WKWebViewConfiguration) {
        super.init(frame: .zero, configuration: configuration)
        LiveObjects.shared.add("L02 WKWebView")
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    deinit { LiveObjects.shared.remove("L02 WKWebView") }
}

/// 接收網頁訊息的 Coordinator（兩個版本共用）
private final class WebCoordinator: NSObject, WKScriptMessageHandler {
    var webView: WKWebView?                     // 要呼叫 evaluateJavaScript，所以強持有 WKWebView

    override init() {
        super.init()
        LiveObjects.shared.add("L02 Coordinator")
    }

    deinit { LiveObjects.shared.remove("L02 Coordinator") }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {}
}

/// 演練流程（兩個版本共用）：打開圖表畫面 → 關掉 → 看 WKWebView 與 Coordinator 有沒有被釋放
@MainActor
private func scenario(_ log: LabLog, screen: some View) async {
    LiveObjects.shared.reset()
    let stage = SwiftUILabStage(screen)
    await stage.settle()
    log("圖表畫面打開時：WKWebView \(LiveObjects.shared.count("L02 WKWebView")) 個、Coordinator \(LiveObjects.shared.count("L02 Coordinator")) 個還活著")
    stage.remove()                                  // 使用者關掉圖表畫面
    await stage.settle(ms: 500)
    log("圖表畫面關掉後：WKWebView \(LiveObjects.shared.count("L02 WKWebView")) 個、Coordinator \(LiveObjects.shared.count("L02 Coordinator")) 個還活著")
}

/// 畫面類演練的操作區（兩個版本共用）：開關圖表畫面，看還活著幾個
private struct L02Demo: View {
    let variant: LabVariant
    @State private var showing = true

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle("顯示圖表畫面", isOn: $showing)
            if showing {
                switch variant {
                case .bug: L02Bug.WebChart().frame(height: 60)
                case .fixed: L02Fixed.WebChart().frame(height: 60)
                }
            }
            LiveObjectsView(names: ["L02 WKWebView", "L02 Coordinator"])
        }
    }
}

// MARK: - Bug 版
private enum L02Bug {
    struct WebChart: UIViewRepresentable {
        func makeCoordinator() -> WebCoordinator { WebCoordinator() }

        func makeUIView(context: Context) -> WKWebView {
            let config = WKWebViewConfiguration()
            // TODO ①-1：把 context.coordinator 註冊成 handler：config.userContentController.add(_:name:)，name 用 "delayWall"
            let web = TrackedWebView(configuration: config)
            // TODO ①-2：把 web 存進 context.coordinator.webView（Coordinator 之後要用它執行 JavaScript）
            return web
        }

        func updateUIView(_ uiView: WKWebView, context: Context) {}
        // 這個版本沒有 dismantleUIView：view 離開畫面時沒有人移除 handler
    }
}

// MARK: - 修正版
private enum L02Fixed {
    struct WebChart: UIViewRepresentable {
        func makeCoordinator() -> WebCoordinator { WebCoordinator() }

        func makeUIView(context: Context) -> WKWebView {
            let config = WKWebViewConfiguration()
            config.userContentController.add(context.coordinator, name: "delayWall")
            let web = TrackedWebView(configuration: config)
            context.coordinator.webView = web
            return web
        }

        func updateUIView(_ uiView: WKWebView, context: Context) {}

        // TODO ②：加上 static func dismantleUIView(_:coordinator:)，在裡面把 "delayWall" 這個 handler 從
        //        uiView.configuration.userContentController 移除（removeScriptMessageHandler(forName:)）
    }
}

// MARK: - 演練設定
extension Swift09UIKitInterop {
    static let l02 = Lab(
        id: "L02",
        title: "關掉圖表畫面，WKWebView 還留在記憶體",
        question: "第 3 題：UIViewRepresentable 的每個方法什麼時候被呼叫？Coordinator 是做什麼的？",
        goal: "圖表網頁用 WKWebView 呈現，Coordinator 當網頁訊息的 handler。先寫出關掉畫面後還留在記憶體的 Bug 版，再讓 WKWebView 與 Coordinator 都被釋放。",
        steps: [
            "打開 Practice/Swift/Swift09-UIKitInterop/L02_WebViewHandlerLeak.swift，寫 TODO ①。",
            "選「練習」「Bug 版」，把操作區的「顯示圖表畫面」關掉：「存活中 WKWebView」「存活中 Coordinator」還是 1，再開關幾次，會一直增加。",
            "按「執行」，看 log：圖表畫面關掉後，WKWebView 與 Coordinator 還活著幾個。",
            "寫 TODO ②，選「修正版」，同樣關掉畫面：兩個數字都回到 0。",
            "進階：Bug 版執行後，用 Xcode 的 Debug Memory Graph（Debug bar 的三個圓點圖示）看 Coordinator，會看到 WKWebView → WKWebViewConfiguration → WKUserContentController → Coordinator → WKWebView 這一圈。",
        ],
        expectBug: "圖表畫面關掉後，WKWebView 與 Coordinator 都還活著 1 個，沒有被釋放。",
        expectFixed: "圖表畫面關掉後，WKWebView 與 Coordinator 都是 0 個：dismantleUIView 移除了 handler。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log, screen: L02Bug.WebChart().frame(width: 300, height: 60))
            case .fixed: await scenario(log, screen: L02Fixed.WebChart().frame(width: 300, height: 60))
            }
        },
        demo: { variant, _ in AnyView(L02Demo(variant: variant)) },
        checkBug: { lines in
            if !lines.contains("圖表畫面打開時：WKWebView 1 個、Coordinator 1 個還活著") { return "圖表畫面沒有跑起來：打開時要有 1 個 WKWebView（TrackedWebView）和 1 個 Coordinator" }
            if !lines.contains("圖表畫面關掉後：WKWebView 1 個、Coordinator 1 個還活著") { return "Bug 還沒重現：關掉畫面後，WKWebView 與 Coordinator 都要還活著（沒有移除 handler）" }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("圖表畫面打開時：WKWebView 1 個、Coordinator 1 個還活著") { return "圖表畫面沒有跑起來：打開時要有 1 個 WKWebView（TrackedWebView）和 1 個 Coordinator" }
            if !lines.contains("圖表畫面關掉後：WKWebView 0 個、Coordinator 0 個還活著") { return "關掉畫面後，WKWebView 與 Coordinator 都要被釋放：在 dismantleUIView 移除註冊的 handler" }
            return nil
        }
    )
}
