// L10：關掉圖表畫面後，接收網頁訊息的 Coordinator 和 WKWebView 都沒被釋放（WKScriptMessageHandler）
// 對應複習站：Swift02ARC 第 5 題「WKWebView 和 JavaScript 溝通時，為什麼容易洩漏？」
//
// 情境：K 線圖用 WKWebView 載入網頁圖表，網頁用 window.webkit.messageHandlers.delayWall.postMessage(…) 通知 App。
//       App 這邊用 Coordinator 接收訊息，並且為了之後呼叫 evaluateJavaScript 而持有 WebView。
//       SwiftUI 的 UIViewRepresentable 就是這個結構：makeUIView 建立 WebView，dismantleUIView 在 view 被移除時呼叫。
// Bug 版：userContentController.add(handler, name:) 會 strong 持有 handler。
//         WebView → userContentController → Coordinator → WebView 繞成一圈，關掉畫面後兩個都不會被釋放。
// 修正版：dismantleUIView 裡呼叫 removeScriptMessageHandler(forName:) 解除註冊，打斷這一圈。
import SwiftUI
import WebKit
import LabKit

/// 圖表網頁：一載入就通知 App「訂閱行情」（兩個版本共用）
private let chartHTML = """
<html><body><script>
window.webkit.messageHandlers.delayWall.postMessage('subscribe');
</script></body></html>
"""

/// 最多等 3 秒，直到 condition 成立（網頁載入需要一點時間）
@MainActor
private func waitUntil(_ condition: () -> Bool) async {
    for _ in 0..<30 {
        if condition() { return }
        try? await Task.sleep(for: .milliseconds(100))
    }
}

// MARK: - Bug 版
private enum L10Bug {
    static let live = "ChartCoordinator（Bug 版）"

    /// 對應 UIViewRepresentable 的 Coordinator
    @MainActor
    final class ChartCoordinator: NSObject, WKScriptMessageHandler {
        private(set) var received: [String] = []
        var webView: WKWebView?                          // 為了呼叫 evaluateJavaScript 而持有 WebView

        override init() {
            super.init()
            LiveObjects.shared.add(live)
        }

        deinit { LiveObjects.shared.remove(live) }

        /// 對應 makeUIView
        func makeWebView() {
            let config = WKWebViewConfiguration()
            config.userContentController.add(self, name: "delayWall")   // 註冊：userContentController 會 strong 持有 self
            let web = WKWebView(frame: .zero, configuration: config)
            web.loadHTMLString(chartHTML, baseURL: nil)
            webView = web
        }

        /// 對應 dismantleUIView：SwiftUI 把這個 view 移除時會呼叫（Bug 版沒有做任何事）
        func dismantleWebView() {}

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            received.append("\(message.body)")
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await Swift02ARC.openThenClose(log, name: live, settle: .seconds(1), whileOpen: { (c: ChartCoordinator) in
            await waitUntil { !c.received.isEmpty }
            log("網頁傳來的訊息：\(c.received)")
        }, beforeClose: { (c: ChartCoordinator) in
            c.dismantleWebView()                         // 關閉畫面時，SwiftUI 會呼叫 dismantleUIView
        }) {
            let coordinator = ChartCoordinator()
            coordinator.makeWebView()
            return coordinator
        }
    }
}

// MARK: - 修正版
private enum L10Fixed {
    static let live = "ChartCoordinator（修正版）"

    @MainActor
    final class ChartCoordinator: NSObject, WKScriptMessageHandler {
        private(set) var received: [String] = []
        var webView: WKWebView?

        override init() {
            super.init()
            LiveObjects.shared.add(live)
        }

        deinit { LiveObjects.shared.remove(live) }

        func makeWebView() {
            let config = WKWebViewConfiguration()
            config.userContentController.add(self, name: "delayWall")
            let web = WKWebView(frame: .zero, configuration: config)
            web.loadHTMLString(chartHTML, baseURL: nil)
            webView = web
        }

        /// 在這裡解除註冊：userContentController 不再持有 Coordinator，循環就斷了
        func dismantleWebView() {
            webView?.configuration.userContentController.removeScriptMessageHandler(forName: "delayWall")
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            received.append("\(message.body)")
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await Swift02ARC.openThenClose(log, name: live, settle: .seconds(1), whileOpen: { (c: ChartCoordinator) in
            await waitUntil { !c.received.isEmpty }
            log("網頁傳來的訊息：\(c.received)")
        }, beforeClose: { (c: ChartCoordinator) in
            c.dismantleWebView()
        }) {
            let coordinator = ChartCoordinator()
            coordinator.makeWebView()
            return coordinator
        }
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l10 = Lab(
        id: "L10",
        title: "關掉圖表畫面後 WebView 還在記憶體裡",
        question: "第 5 題：WKWebView 和 JavaScript 溝通時，為什麼容易洩漏？",
        goal: "Coordinator 持有 WebView，又註冊成網頁訊息的 handler。找出為什麼關掉畫面後兩個都不會被釋放，並在 dismantleUIView 的位置修好。",
        steps: [
            "選「Bug 版」，按畫面上方的「打開畫面」再按「關閉畫面」：「存活中 ChartCoordinator」維持 1。",
            "按下方的「執行」看 log：網頁傳來「subscribe」，關閉畫面後 weak 參考還在。",
            "用 Memory Graph 看循環：從 Xcode 執行（⌘R），打開畫面再關閉，按除錯列的 Debug Memory Graph，左邊搜尋 ChartCoordinator，右邊會看到 WKWebView → WKUserContentController → ChartCoordinator → WKWebView 這一圈。",
            "選「修正版」執行，存活數應該回到 0；畫面上的「打開畫面」「關閉畫面」也一樣會回到 0。",
        ],
        expectBug: "網頁傳來的訊息：[\"subscribe\"]；關閉畫面後，存活中的 ChartCoordinator（Bug 版）仍是 1，weak 參考還在。",
        expectFixed: "網頁傳來的訊息：[\"subscribe\"]；關閉畫面後存活數回到 0，weak 參考變成 nil。",
        run: { variant, log in
            switch variant {
            case .bug: await L10Bug.run(log)
            case .fixed: await L10Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug:
                AnyView(ScreenDemo(name: L10Bug.live, close: { $0.dismantleWebView() }) {
                    let coordinator = L10Bug.ChartCoordinator()
                    coordinator.makeWebView()
                    return coordinator
                })
            case .fixed:
                AnyView(ScreenDemo(name: L10Fixed.live, close: { $0.dismantleWebView() }) {
                    let coordinator = L10Fixed.ChartCoordinator()
                    coordinator.makeWebView()
                    return coordinator
                })
            }
        },
        checkBug: { lines in
            guard lines.contains("網頁傳來的訊息：[\"subscribe\"]") else { return "沒有收到網頁的訊息：makeWebView 要用 userContentController.add(self, name: \"delayWall\") 註冊，並把 WebView 存進 webView" }
            guard lines.last?.hasSuffix("：1（weak 參考還在）") == true else { return "Bug 還沒重現：關閉畫面後 Coordinator 應該還活著（dismantleWebView 什麼都不做）" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("網頁傳來的訊息：[\"subscribe\"]") else { return "沒有收到網頁的訊息：makeWebView 要用 userContentController.add(self, name: \"delayWall\") 註冊，並把 WebView 存進 webView" }
            guard lines.last?.hasSuffix("：0（weak 參考已變成 nil）") == true else { return "關閉畫面後 Coordinator 還活著：dismantleWebView 裡要 removeScriptMessageHandler(forName:)" }
            return nil
        }
    )
}
