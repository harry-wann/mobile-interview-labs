// L13：關掉畫面後，URLSession 還抓著 ViewModel（URLSession 會 strong 持有它的
// delegate）
// 對應複習站：Swift02ARC 第 4 題「實務上哪些寫法最容易造成 retain cycle？」
// （URLSession 的 delegate）
//
// 情境：下單畫面的 ViewModel 自己建立一個 URLSession，把自己設成 delegate
// 來接收報價資料。
//       ViewModel 的 deinit 裡有 invalidateAndCancel()，以為關畫面時 session
// 會被收掉。
//       演練用自訂的 URLProtocol 攔截請求，直接回一筆假報價，不會真的打網路。
//
// 你要寫：
//   ① Bug 版：init 裡用 URLSession(configuration:delegate:delegateQueue:) 建立
// session（設定用 makeQuoteConfiguration()），delegate 設成 self。
//            離開畫面時什麼都不做（screenDidDisappear 已經寫好是空的）。
//   ② 修正版：離開畫面時（screenDidDisappear）呼叫 session 的
// invalidateAndCancel()。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift02-ARC/L13_URLSessionDelegate.
// swift
import SwiftUI
import LabKit

/// 假的報價伺服器：攔截請求，直接回一筆報價（兩個版本共用，不會打網路）
private final class FakeQuoteProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest
    { request }

    override func startLoading() {
        let body = Data(#"{"pair":"BTC/JPY","price":15000000}"#.utf8)
        let response = HTTPURLResponse(
            url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: nil)!
        client?.urlProtocol(
            self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// 用假報價伺服器的 session 設定（兩個版本共用）
private func makeQuoteConfiguration() -> URLSessionConfiguration {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [FakeQuoteProtocol.self]
    return configuration
}

/// 最多等 2 秒，直到 condition 成立（delegate 在背景佇列收資料，需要一點時間）
private func waitUntil(_ condition: () -> Bool) async {
    for _ in 0..<20 {
        if condition() { return }
        try? await Task.sleep(for: .milliseconds(100))
    }
}

// MARK: - Bug 版
private enum L13Bug {
    static let live = "QuoteViewModel（Bug 版）"

    final class QuoteViewModel: NSObject, URLSessionDataDelegate {
        private var session: URLSession?
        private let received: Swift02ARC.HitCounter

        init(received: Swift02ARC.HitCounter) {
            self.received = received
            super.init()
            LiveObjects.shared.add(live)
            // TODO ①：建立 URLSession 存進 session：設定用
            // makeQuoteConfiguration()，delegate 設成 self，delegateQueue 用
            // nil
            //         （session 會 strong 持有 delegate，也就是 self：
            // ViewModel → session → ViewModel）
        }

        func fetchQuote() {
            session?.dataTask(
                with: URL(string: "https://quote.example.invalid/btc-jpy")!
            ).resume()
        }

        func urlSession(
            _ session: URLSession, dataTask: URLSessionDataTask,
            didReceive data: Data
        ) {
            received.hit()
        }

        /// 對應畫面離開（SwiftUI 的 onDisappear、UIKit 的 viewDidDisappear）：
        /// Bug 版沒有做任何事
        // 已寫好：Bug 版什麼都不做
        func screenDidDisappear() {}

        deinit {
            // 有循環時 deinit 不會執行，這行就是白寫
            session?.invalidateAndCancel()
            LiveObjects.shared.remove(live)
        }
    }

    static func run(_ log: LabLog) async {
        let received = Swift02ARC.HitCounter()
        await Swift02ARC.openThenClose(
            log, name: live, settle: .milliseconds(500),
            whileOpen: { (vm: QuoteViewModel) in
                vm.fetchQuote()
                await waitUntil { received.count >= 1 }
                log("畫面開著時，delegate 收到 \(received.count) 筆報價")
            },
            beforeClose: { (vm: QuoteViewModel) in
                vm.screenDidDisappear()
            }
        ) { QuoteViewModel(received: received) }
    }
}

// MARK: - 修正版
private enum L13Fixed {
    static let live = "QuoteViewModel（修正版）"

    final class QuoteViewModel: NSObject, URLSessionDataDelegate {
        private var session: URLSession?
        private let received: Swift02ARC.HitCounter

        init(received: Swift02ARC.HitCounter) {
            self.received = received
            super.init()
            LiveObjects.shared.add(live)
            // 已寫好，和 Bug 版一樣
            session = URLSession(
                configuration: makeQuoteConfiguration(), delegate: self,
                delegateQueue: nil)
        }

        func fetchQuote() {
            session?.dataTask(
                with: URL(string: "https://quote.example.invalid/btc-jpy")!
            ).resume()
        }

        func urlSession(
            _ session: URLSession, dataTask: URLSessionDataTask,
            didReceive data: Data
        ) {
            received.hit()
        }

        /// 離開畫面時 invalidate：session 通知 delegate「我結束了」之後，
        /// 就放掉對 delegate 的 strong 參考
        func screenDidDisappear() {
            // TODO ②：呼叫 session 的 invalidateAndCancel()，session
            // 才會放掉對 delegate 的 strong 參考
        }

        deinit {
            // 保險：已經 invalidate 過，再呼叫沒有影響
            session?.invalidateAndCancel()
            LiveObjects.shared.remove(live)
        }
    }

    static func run(_ log: LabLog) async {
        let received = Swift02ARC.HitCounter()
        await Swift02ARC.openThenClose(
            log, name: live, settle: .milliseconds(500),
            whileOpen: { (vm: QuoteViewModel) in
                vm.fetchQuote()
                await waitUntil { received.count >= 1 }
                log("畫面開著時，delegate 收到 \(received.count) 筆報價")
            },
            beforeClose: { (vm: QuoteViewModel) in
                vm.screenDidDisappear()
            }
        ) { QuoteViewModel(received: received) }
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l13 = Lab(
        id: "L13",
        title: "關掉畫面後 URLSession 還抓著 ViewModel",
        question: "第 4 題：實務上哪些寫法最容易造成 retain cycle？",
        goal:
            "ViewModel 建立 URLSession 並把自己設成 delegate，deinit 裡有 invalidateAndCancel()。找出為什麼畫面關掉後 ViewModel 沒有被釋放，並在離開畫面時修好。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看 log：畫面開著時 delegate 收到 1 筆報價（請求被假的報價伺服器攔截，沒有打網路）。",
            "再看關閉畫面那一行：存活中的 QuoteViewModel 是 1，weak 參考還在，deinit 沒有執行，裡面的 invalidateAndCancel() 當然也沒有執行。",
            "想一想：URLSession 對 delegate 是 strong 還是 weak？官方文件說它會一直持有 delegate，直到什麼時候？ViewModel 又持有 session，誰該放手？",
            "寫 TODO ②，選「修正版」執行，存活數應該回到 0。也可以在畫面上按「打開畫面」「關閉畫面」，數字會回到 0。",
        ],
        expectBug:
            "畫面開著時，delegate 收到 1 筆報價；關閉畫面後，存活中的 QuoteViewModel（Bug 版）仍是 1，weak 參考還在。",
        expectFixed: "畫面開著時，delegate 收到 1 筆報價；關閉畫面後存活數回到 0，weak 參考變成 nil。",
        run: { variant, log in
            switch variant {
            case .bug: await L13Bug.run(log)
            case .fixed: await L13Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug:
                AnyView(
                    ScreenDemo(
                        name: L13Bug.live, close: { $0.screenDidDisappear() }
                    ) { L13Bug.QuoteViewModel(received: HitCounter()) })
            case .fixed:
                AnyView(
                    ScreenDemo(
                        name: L13Fixed.live, close: { $0.screenDidDisappear() }
                    ) { L13Fixed.QuoteViewModel(received: HitCounter()) })
            }
        },
        checkBug: { lines in
            guard lines.contains("畫面開著時，delegate 收到 1 筆報價") else {
                return
                    "畫面開著時 delegate 應該收到 1 筆報價：init 裡要用 URLSession(configuration:delegate:delegateQueue:) 建立 session，delegate 設成 self"
            }
            guard lines.last?.hasSuffix("：1（weak 參考還在）") == true else {
                return
                    "Bug 還沒重現：關閉畫面後 ViewModel 應該還活著（screenDidDisappear 什麼都不做，session 沒有 invalidate）"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("畫面開著時，delegate 收到 1 筆報價") else {
                return
                    "畫面開著時 delegate 應該收到 1 筆報價：init 裡要用 URLSession(configuration:delegate:delegateQueue:) 建立 session，delegate 設成 self"
            }
            guard lines.last?.hasSuffix("：0（weak 參考已變成 nil）") == true else {
                return
                    "關閉畫面後 ViewModel 還活著：screenDidDisappear 裡要呼叫 session?.invalidateAndCancel()，session 才會放掉 delegate"
            }
            return nil
        }
    )
}
