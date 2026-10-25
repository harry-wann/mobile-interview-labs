// L07：斷線重連之後，畫面停在最後一個價格不動了（重連後沒有重新訂閱）
// 對應複習站：Exchange03WebSocket 第 6
// 題「收到訊息後怎麼分派？斷線重連後要做什麼？」
//
// 情境：畫面訂閱了兩個 topic：market.btcjpy.bbo 和 market.btcjpy.depth.step0。
// 連線一斷，伺服器就不記得這條連線訂閱過什麼。
//       App 重新連上之後，伺服器只有在收到新的 sub 之後才會推送。
//       （演練用假的連線與假伺服器：伺服器每次「推送一輪」，會對每個已訂閱的
// topic 各推一則。）
// Bug 版：重連時只重新建立連線，沒有把訂閱的 topic 再送一次。伺服器什麼都不推，
// 畫面停在斷線前的最後一個價格。
// 修正版：App 把需要的 topic 記在清單裡；重連成功後，依清單把每一個 topic 重新
// sub 一次。
import Foundation
import LabKit

/// 假伺服器：只記得「目前這條連線」訂閱了什麼
private final class FakeServer {
    private var subscribed: Set<String> = []
    private(set) var connections = 0

    // 新的連線：伺服器不記得之前訂閱過什麼
    func connect() { connections += 1; subscribed = [] }

    func receive(_ text: String) {
        guard let json = e03Parse(text), let topic = json["sub"] as? String
        else { return }
        subscribed.insert(topic)
    }

    /// 推送一輪：每個已訂閱的 topic 各推一則
    func pushRound() -> Int { subscribed.count }
}

private func subMessage(_ topic: String, id: Int) -> String {
    "{\"sub\":\"\(topic)\",\"id\":\"id\(id)\"}"
}

/// 演練流程（兩個版本共用）：訂閱 → 推送 → 斷線 → 重連 → 再推送
private func scenario(
    _ log: LabLog,
    makeClient: (FakeServer) -> (
        subscribe: (String) -> Void, onReconnected: () -> Void
    )
) {
    let server = FakeServer()
    server.connect()
    let client = makeClient(server)
    client.subscribe("market.btcjpy.bbo")
    client.subscribe("market.btcjpy.depth.step0")
    log("斷線前：伺服器推送一輪，App 收到 \(server.pushRound()) 則")
    log("連線斷了（伺服器忘記這條連線的所有訂閱）")
    client.onReconnected()
    log("重連後：目前連線數 \(server.connections)")
    let after = server.pushRound()
    log("重連後：伺服器推送一輪，App 收到 \(after) 則\(after == 0 ? "，畫面停在斷線前的最後一個價格" : "")")
}

// MARK: - Bug 版
private enum L07Bug {
    final class Client {
        private let server: FakeServer
        // 畫面需要的 topic 清單
        private var topics: [String] = []
        private var nextID = 1

        init(server: FakeServer) { self.server = server }

        func subscribe(_ topic: String) {
            topics.append(topic)
            server.receive(subMessage(topic, id: nextID))
            nextID += 1
        }

        func onReconnected() {
            // 只重新建立連線
            server.connect()
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { server in
            let client = Client(server: server)
            return (client.subscribe, client.onReconnected)
        }
    }
}

// MARK: - 修正版
private enum L07Fixed {
    final class Client {
        private let server: FakeServer
        private var topics: [String] = []
        private var nextID = 1

        init(server: FakeServer) { self.server = server }

        func subscribe(_ topic: String) {
            topics.append(topic)
            server.receive(subMessage(topic, id: nextID))
            nextID += 1
        }

        func onReconnected() {
            server.connect()
            // 依清單，把每一個 topic 重新訂閱（帶新的 id）
            for topic in topics {
                server.receive(subMessage(topic, id: nextID))
                nextID += 1
            }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { server in
            let client = Client(server: server)
            return (client.subscribe, client.onReconnected)
        }
    }
}

// MARK: - 演練設定
extension Exchange03WebSocket {
    static let l07 = Lab(
        id: "L07",
        title: "斷線重連之後，畫面停在最後一個價格不動",
        question: "第 6 題：收到訊息後怎麼分派？斷線重連後要做什麼？",
        goal: "連線一斷，伺服器就不記得訂閱了什麼。重連成功後，App 要依自己記錄的 topic 清單重新訂閱。",
        steps: [
            "選「Bug 版」執行，比較斷線前和重連後，每一輪推送收到幾則。",
            "想一想：新的連線上，伺服器知道這個 App 想要哪些 topic 嗎？",
            "選「修正版」執行，重連後也要收到兩則。",
            "進階：私有頻道重連後要先重新 auth、認證成功再 sub（見演練 5）；板的深度這類狀態型資料還要重新抓快照（見訂單簿單元）。",
        ],
        expectBug:
            "「斷線前：伺服器推送一輪，App 收到 2 則」，「重連後：伺服器推送一輪，App 收到 0 則，畫面停在斷線前的最後一個價格」。",
        expectFixed: "「重連後：伺服器推送一輪，App 收到 2 則」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("重連後：伺服器推送一輪") }) {
                return "演練沒有跑完：要用 scenario(log, makeClient:) 執行"
            }
            if !lines.contains("重連後：目前連線數 2") {
                return "onReconnected 要呼叫 server.connect() 重新建立連線"
            }
            return lines.contains("重連後：伺服器推送一輪，App 收到 0 則，畫面停在斷線前的最後一個價格")
                ? nil : "Bug 還沒重現：重連時只呼叫 server.connect()，不要重新 sub"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("重連後：伺服器推送一輪") }) {
                return "演練沒有跑完：要用 scenario(log, makeClient:) 執行"
            }
            if !lines.contains("重連後：目前連線數 2") {
                return "onReconnected 要呼叫 server.connect() 重新建立連線"
            }
            return lines.contains("重連後：伺服器推送一輪，App 收到 2 則")
                ? nil
                : "重連之後，要把 topics 清單裡的每一個 topic 都用 subMessage(topic, id:) 重新送一次"
        }
    )
}
