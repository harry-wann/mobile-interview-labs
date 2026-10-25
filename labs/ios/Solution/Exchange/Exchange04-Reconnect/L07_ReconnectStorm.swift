// L07：一次斷線，伺服器卻收到三條連線和五個重複的訂閱（每個畫面各自重連）
// 對應複習站：Exchange04Reconnect 第 1 題「為什麼重連要寫成狀態機？狀態和事件有哪些？」（訂閱要有單一來源）
//
// 情境：App 同時有三個畫面開著，各自需要一些 topic：
//       行情列表：market.btcjpy.bbo、market.ethjpy.bbo；K 線：market.btcjpy.kline.1min、market.btcjpy.bbo；下單頁：market.btcjpy.bbo。
//       一共 5 個訂閱需求，其中不同的 topic 只有 3 個。連線斷了，三個畫面都收到「連線關閉」的通知。
// Bug 版：每個畫面收到通知就自己重連，再訂閱自己的 topic。伺服器收到 3 條連線、5 則 sub（market.btcjpy.bbo 重複 3 次，每次推送收到 3 份）。
// 修正版：連線由一個地方負責（單一來源）：已經在重連了就忽略其他畫面的通知；topic 清單合併、去重之後，只訂閱一次。
import Foundation
import LabKit

private let screens = [
    ["market.btcjpy.bbo", "market.ethjpy.bbo"],
    ["market.btcjpy.kline.1min", "market.btcjpy.bbo"],
    ["market.btcjpy.bbo"],
]

/// 假伺服器：記下收到幾條連線、哪些 sub
private final class FakeServer {
    private(set) var connections = 0
    private(set) var subs: [String] = []
    func connect() { connections += 1 }
    func sub(_ topic: String) { subs.append(topic) }
}

/// 演練流程（兩個版本共用）：同一個「連線關閉」通知，依序送給三個畫面
private func scenario(_ log: LabLog, makeHandler: ([[String]]) -> (Int, FakeServer) -> Void) {
    let server = FakeServer()
    let handler = makeHandler(screens)
    for index in screens.indices {
        handler(index, server)
        log("畫面 \(index + 1) 收到連線關閉的通知 → 伺服器累計 \(server.connections) 條連線、\(server.subs.count) 則 sub")
    }
    let duplicates = server.subs.count - Set(server.subs).count
    log("伺服器收到 \(server.connections) 條連線、\(server.subs.count) 則 sub（重複的 \(duplicates) 則）")
}

// MARK: - Bug 版
private enum L07Bug {
    static func makeHandler(screens: [[String]]) -> (Int, FakeServer) -> Void {
        { index, server in
            server.connect()                                    // 每個畫面各自重連
            for topic in screens[index] { server.sub(topic) }   // 各自訂閱自己的 topic
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeHandler: makeHandler(screens:))
    }
}

// MARK: - 修正版
private enum L07Fixed {
    static func makeHandler(screens: [[String]]) -> (Int, FakeServer) -> Void {
        var reconnecting = false
        let topics = Set(screens.flatMap { $0 })                // 單一來源：所有畫面需要的 topic，合併、去重
        return { _, server in
            if reconnecting { return }                          // 已經在重連了：忽略其他畫面的通知
            reconnecting = true
            server.connect()
            for topic in topics.sorted() { server.sub(topic) }  // 只訂閱一次
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeHandler: makeHandler(screens:))
    }
}

// MARK: - 演練設定
extension Exchange04Reconnect {
    static let l07 = Lab(
        id: "L07",
        title: "一次斷線，伺服器收到三條連線和五個訂閱",
        question: "第 1 題：為什麼重連要寫成狀態機？狀態和事件有哪些？",
        goal: "「需要哪些 topic」只記錄在一個地方，重連由一個地方負責。不要讓每個畫面各自重連、各自訂閱。",
        steps: [
            "選「Bug 版」執行，看伺服器累計收到幾條連線、幾則 sub、有幾則是重複的。",
            "想一想：三個畫面都收到同一個「連線關閉」的通知，每個都重連會怎樣？這套交易所的私有 WebSocket 每個 API key 最多 10 條連線。",
            "選「修正版」執行，連線要只有 1 條，sub 只有 3 則（沒有重複）。",
            "進階：畫面離開時要從清單裡移除自己的 topic（引用計數）；真實的 App 會把連線、topic 清單和狀態機放在同一個物件裡。",
        ],
        expectBug: "「伺服器收到 3 條連線、5 則 sub（重複的 2 則）」。",
        expectFixed: "「伺服器收到 1 條連線、3 則 sub（重複的 0 則）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("伺服器收到") }) { return "演練沒有跑完：要用 scenario(log, makeHandler:) 執行" }
            return lines.contains("伺服器收到 3 條連線、5 則 sub（重複的 2 則）")
                ? nil : "Bug 還沒重現：每個畫面收到通知都要 server.connect()，再訂閱 screens[index] 的每個 topic"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("伺服器收到") }) { return "演練沒有跑完：要用 scenario(log, makeHandler:) 執行" }
            return lines.contains("伺服器收到 1 條連線、3 則 sub（重複的 0 則）")
                ? nil : "只能連線一次（用旗標忽略後面畫面的通知），topic 清單先合併、去重（Set），再各訂閱一次"
        }
    )
}
