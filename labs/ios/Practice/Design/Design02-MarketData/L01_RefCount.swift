// L01：離開詳細頁，列表上的價格就停了（訂閱沒有引用計數）
// 對應複習站：Design02MarketData 第 3 題「一條連線、好幾個畫面，要怎麼共用？」
//
// 情境：整個 App 只開一條 WebSocket，訂閱管理者替所有畫面送 sub／unsub。
// 兩個畫面需要的 topic 有重疊：
//       行情列表：market.btcjpy.bbo、market.ethjpy.bbo；BTC 詳細頁：market.
// btcjpy.bbo、market.btcjpy.depth.step0。
//       劇本：列表先出現 → 詳細頁出現 → 伺服器推送一次 market.btcjpy.bbo →
// 使用者按返回，詳細頁離開
//       → 伺服器再推送一次 market.btcjpy.bbo（列表還開著，還在看 BTC 價格）→
// 列表也離開。
//       假伺服器的規則：同一個 topic 每被 sub 一次，每次推送就多送一份；unsub
// 會取消這個 topic 的全部訂閱。
//
// 你要寫：
//   ① Bug 版（L01Bug.Manager）：subscribe 每次都送 sub、unsubscribe 每次都送
// unsub，不計數。
//   ② 修正版（L01Fixed.Manager）：用 counts 記每個 topic 有幾個畫面在用，從 0
// 變 1 才送 sub，從 1 變 0 才送 unsub。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Design/Design02-MarketData/L01_RefCount.swift
import LabKit

private let listTopics = ["market.btcjpy.bbo", "market.ethjpy.bbo"]
private let detailTopics = ["market.btcjpy.bbo", "market.btcjpy.depth.step0"]

/// 訂閱管理者：畫面出現時呼叫 subscribe，離開時呼叫 unsubscribe
private protocol TopicManager: AnyObject {
    func subscribe(_ topic: String)
    func unsubscribe(_ topic: String)
}

/// 演練流程（兩個版本共用）
private func scenario(
    _ log: LabLog, makeManager: (D02FakeServer) -> TopicManager
) {
    let server = D02FakeServer()
    let manager = makeManager(server)
    func topics() -> String {
        server.activeTopics.isEmpty
            ? "（沒有）" : server.activeTopics.joined(separator: "、")
    }

    listTopics.forEach(manager.subscribe)
    log("行情列表出現 → 伺服器上的訂閱：\(topics())（sub 累計 \(server.subCount) 則）")
    detailTopics.forEach(manager.subscribe)
    log("BTC 詳細頁出現 → 伺服器上的訂閱：\(topics())（sub 累計 \(server.subCount) 則）")
    let both = server.push("market.btcjpy.bbo")
    log("伺服器推送 market.btcjpy.bbo（列表和詳細頁都開著）→ App 收到 \(both) 份")
    detailTopics.forEach(manager.unsubscribe)
    log("使用者按返回，詳細頁離開 → 伺服器上的訂閱：\(topics())（unsub 累計 \(server.unsubCount) 則）")
    let after = server.push("market.btcjpy.bbo")
    log("伺服器推送 market.btcjpy.bbo（只剩列表開著，列表還在看 BTC 價格）→ App 收到 \(after) 份")
    listTopics.forEach(manager.unsubscribe)
    log("行情列表也離開 → 伺服器上的訂閱：\(topics())（unsub 累計 \(server.unsubCount) 則）")
    log(
        "最後：sub 一共送出 \(server.subCount) 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 \(both) 份，詳細頁離開後收到 \(after) 份；全部離開後伺服器上剩 \(server.activeTopics.count) 個訂閱"
    )
}

// MARK: - Bug 版
private enum L01Bug {
    final class Manager: TopicManager {
        private let server: D02FakeServer
        init(server: D02FakeServer) { self.server = server }

        func subscribe(_ topic: String) {
            // TODO ①-1：畫面要就送一次 sub（server.send(sub: topic)），
            // 不管別的畫面訂過沒有
        }

        func unsubscribe(_ topic: String) {
            // TODO ①-2：畫面離開就送一次 unsub（server.send(unsub: topic)），
            // 不管別的畫面還在不在用
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Manager(server: $0) }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class Manager: TopicManager {
        private let server: D02FakeServer
        // topic → 目前有幾個畫面在用
        private var counts: [String: Int] = [:]
        init(server: D02FakeServer) { self.server = server }

        func subscribe(_ topic: String) {
            // TODO ②-1：counts[topic] 加 1；加完剛好是 1（第一個畫面要這個
            // topic）才送 sub
        }

        func unsubscribe(_ topic: String) {
            // TODO ②-2：counts[topic] 減 1；減到 0（最後一個畫面離開）才送
            // unsub，並把這個 topic 從 counts 移除。
            //           counts 裡沒有這個 topic 就什麼都不做
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Manager(server: $0) }
    }
}

// MARK: - 演練設定
extension Design02MarketData {
    static let l01 = Lab(
        id: "L01",
        title: "離開詳細頁，列表上的價格就停了",
        question: "第 3 題：一條連線、好幾個畫面，要怎麼共用？",
        goal:
            "多個畫面共用一條連線時，訂閱管理者替每個 topic 記一個引用計數：從 0 變 1 才送 sub，從 1 變 0 才送 unsub。同一個 topic 只訂一次，畫面還在用的 topic 也不會被別的畫面退訂。先寫出不計數的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Design/Design02-MarketData/L01_RefCount.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看每個畫面出現、離開之後，伺服器上的訂閱剩下什麼。",
            "看兩次推送：兩個畫面都開著時，market.btcjpy.bbo 收到幾份？詳細頁離開之後，列表還在看 BTC 價格，卻收到幾份？",
            "想一想：兩個畫面需要同一個 topic 時，誰有資格送 sub？誰有資格送 unsub？",
            "寫 TODO ②（修正版），選「修正版」執行：sub 只送 3 則，詳細頁離開後列表照樣收得到 BTC 價格，全部離開後伺服器上不剩任何訂閱。",
        ],
        expectBug:
            "sub 送了 4 則（market.btcjpy.bbo 訂了兩次，推送收到 2 份）；詳細頁離開時把 market.btcjpy.bbo 一起退訂，列表收到 0 份：「最後：sub 一共送出 4 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 2 份，詳細頁離開後收到 0 份；全部離開後伺服器上剩 0 個訂閱」。",
        expectFixed:
            "sub 只送 3 則，推送一直收到 1 份：「最後：sub 一共送出 3 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 1 份，詳細頁離開後收到 1 份；全部離開後伺服器上剩 0 個訂閱」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, makeManager:) 執行"
            }
            if last.hasPrefix("最後：sub 一共送出 0 則") {
                return "subscribe 還沒送出 sub：Bug 版每次都 server.send(sub: topic)"
            }
            return last.hasPrefix(
                "最後：sub 一共送出 4 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 2 份，詳細頁離開後收到 0 份"
            )
                ? nil
                : "Bug 還沒重現：subscribe 每次都送 sub、unsubscribe 每次都送 unsub（不計數），market.btcjpy.bbo 會被訂兩次，又被詳細頁整個退訂"
        },
        checkFixed: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, makeManager:) 執行"
            }
            if last.hasPrefix("最後：sub 一共送出 0 則") {
                return
                    "subscribe 還沒送出 sub：counts[topic] 從 0 變 1 的時候要 server.send(sub: topic)"
            }
            return last.hasPrefix(
                "最後：sub 一共送出 3 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 1 份，詳細頁離開後收到 1 份；全部離開後伺服器上剩 0 個訂閱"
            )
                ? nil
                : "用 counts[topic] 記有幾個畫面在用：從 0 變 1 才 send(sub:)，從 1 變 0 才 send(unsub:)（並把 topic 從 counts 移除）"
        }
    )
}
