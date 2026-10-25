// L04：在電梯裡按了買入，畫面沒反應，一分鐘後走出電梯，單卻自己下出去了（沒有網路時把下單排隊，等連線恢復再補送）
// 對應複習站：Design03OrderAssets 第 7 題「有哪些例外情況？怎麼測試和監控？」（斷線時按下單）
//
// 情境：使用者在電梯裡（沒有網路）按下「買入 0.01 BTC，限價 10,000,000 円」。下單頁等了 10 秒沒有結果，使用者以為沒下成功，放棄、關掉畫面。
//       一分鐘後走出電梯，網路恢復，這時市價已經跌到 9,800,000 円。
//       看畫面上顯示什麼，以及網路恢復之後，伺服器收到了幾張單。
// Bug 版：網路層設成「等到有網路再送」（iOS 的 URLSession 設 waitsForConnectivity = true，Android 則是用 WorkManager 加上網路條件）。
//        沒有網路時，下單請求排在佇列裡，畫面顯示「已送出，等待連線」；網路一恢復就自動補送。限價 10,000,000 比市價 9,800,000 高，立刻成交，
//        使用者買到一筆他以為沒下成功的單。
// 修正版：下單前先看連線狀態：沒有網路就不排隊，直接告訴使用者「現在無法下單，請確認網路連線」。網路恢復後不會補送任何東西。
import LabKit

/// 假的網路狀態（兩個版本共用）
private final class FakeNetwork {
    var isOnline = false
}

/// 假的伺服器（兩個版本共用）：收到的訂單
private final class FakeServer {
    private(set) var orders: [String] = []
    func place(_ order: String) { orders.append(order) }
}

private protocol OrderGateway: AnyObject {
    /// 使用者按下買入。回傳畫面要顯示的文字
    func submit(_ order: String) -> String
    /// 網路恢復了（network.isOnline 已經是 true）
    func connectionRestored()
}

private let order = "買 0.01 BTC，限價 10,000,000 円"

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, makeGateway: (FakeNetwork, FakeServer) -> OrderGateway) {
    let network = FakeNetwork()
    let server = FakeServer()
    let gateway = makeGateway(network, server)
    log("0 秒：使用者在電梯裡（沒有網路），按下「\(order)」")
    let message = gateway.submit(order)
    log("畫面顯示：「\(message)」")
    log("10 秒：使用者等不到結果，以為沒下成功，把畫面關掉；伺服器目前收到 \(server.orders.count) 張單")
    network.isOnline = true
    log("60 秒：走出電梯，網路恢復。這時市價已經是 9,800,000 円")
    gateway.connectionRestored()
    for placed in server.orders { log("伺服器收到：「\(placed)」。限價比市價高，立刻成交") }
    log("最後：畫面顯示「\(message)」；網路恢復後伺服器收到 \(server.orders.count) 張單")
}

// MARK: - Bug 版
private enum L04Bug {
    final class Gateway: OrderGateway {
        private let network: FakeNetwork
        private let server: FakeServer
        private var queue: [String] = []
        init(network: FakeNetwork, server: FakeServer) { self.network = network; self.server = server }

        func submit(_ order: String) -> String {
            if network.isOnline {
                server.place(order)
                return "已送出"
            }
            queue.append(order)                                       // 沒有網路：先排隊，等網路恢復再送
            return "已送出，等待連線"
        }

        func connectionRestored() {
            for queued in queue { server.place(queued) }              // 網路一恢復，把排隊的單補送出去
            queue.removeAll()
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Gateway(network: $0, server: $1) }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class Gateway: OrderGateway {
        private let network: FakeNetwork
        private let server: FakeServer
        init(network: FakeNetwork, server: FakeServer) { self.network = network; self.server = server }

        func submit(_ order: String) -> String {
            guard network.isOnline else {
                return "現在無法下單，請確認網路連線"                    // 沒有網路：不排隊，馬上告訴使用者
            }
            server.place(order)
            return "已送出"
        }

        func connectionRestored() {}                                  // 沒有排隊的單，沒有東西要補送
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Gateway(network: $0, server: $1) }
    }
}

// MARK: - 演練設定
extension Design03OrderAssets {
    static let l04 = Lab(
        id: "L04",
        title: "在電梯裡按的買入，走出電梯後自己下出去了",
        question: "第 7 題：有哪些例外情況？怎麼測試和監控？",
        goal: "斷線時按下單，不排隊、不等連線恢復後自動補送：直接告訴使用者現在無法下單。下單是有時效的操作，價格已經變了的單，不能由使用者看不到的背景補送。",
        steps: [
            "選「Bug 版」執行，看畫面顯示什麼、網路恢復之後伺服器收到幾張單。",
            "想一想：使用者看到「已送出，等待連線」，會以為單下了還是沒下？一分鐘後市價已經變了，這張單還是他想要的嗎？",
            "選「修正版」執行：畫面顯示「現在無法下單，請確認網路連線」，網路恢復之後伺服器收到 0 張單。",
            "想一想：行情的訂閱可以在網路恢復後自動補上，下單為什麼不行？（訂閱是冪等的，補上沒有副作用；下單有副作用，而且價格已經不同了。）",
        ],
        expectBug: "畫面顯示「已送出，等待連線」，網路恢復後伺服器自動收到 1 張單：「最後：畫面顯示「已送出，等待連線」；網路恢復後伺服器收到 1 張單」。",
        expectFixed: "畫面顯示「現在無法下單，請確認網路連線」，伺服器沒有收到任何單：「最後：畫面顯示「現在無法下單，請確認網路連線」；網路恢復後伺服器收到 0 張單」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, makeGateway:) 執行" }
            return lines.contains("最後：畫面顯示「已送出，等待連線」；網路恢復後伺服器收到 1 張單")
                ? nil : "Bug 還沒重現：沒有網路時 submit 把單存進 queue、回傳「已送出，等待連線」；connectionRestored 把 queue 裡的單補送給伺服器"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, makeGateway:) 執行" }
            return lines.contains("最後：畫面顯示「現在無法下單，請確認網路連線」；網路恢復後伺服器收到 0 張單")
                ? nil : "沒有網路（network.isOnline 是 false）時，submit 直接回傳「現在無法下單，請確認網路連線」，不要排隊"
        }
    )
}
