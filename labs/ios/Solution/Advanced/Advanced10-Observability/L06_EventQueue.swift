// L06：事件重複送出，也有送不出去就消失的（沒有本機佇列，也沒有 idempotency key）
// 對應複習站：Advanced10Observability 第 5 題「analytics 事件怎麼設計，才能算出漏斗又不洩漏資料？」
//
// 情境：App 要把下單漏斗的三個事件送到分析服務：order_form_viewed、order_submitted、order_acked。網路狀況依序是：
//       ① 送 order_form_viewed 時：伺服器收到了，但回應在途中遺失，App 看到逾時；
//       ② 送 order_submitted 時：App 沒有網路；
//       ③ 送 order_acked 時：網路恢復正常。最後使用者回到前景，App 再試著送一次。
//       分析服務是假的，它會記下每個事件被算了幾次；如果事件有 eventId，它會自動去除重複（idempotent，同一個 id 只算一次）。
// Bug 版：track 直接送出，失敗就立刻重送一次，還是失敗就丟掉；事件沒有 id。結果 order_form_viewed 被算了 2 次（重複），
//        order_submitted 一次都沒有（遺失）；三個事件總共算到 3 次，數量剛好對，但漏斗是錯的。
// 修正版：事件先放進本機佇列，每個事件有唯一的 eventId；flush 時整批送出，成功才移出佇列，失敗就留著下次再送。
//        重送時伺服器用 eventId 去重，每個事件剛好算 1 次。
import Foundation
import LabKit

private struct Event {
    let name: String
    var id: String?
}

private enum NetworkMode {
    case online
    case offline
    case dropResponseOnce                                       // 伺服器收到了，但回應遺失，App 看到逾時
}

/// 假的分析服務（兩個版本共用）
private final class FakeCollector {
    var mode = NetworkMode.online
    private(set) var counts: [String: Int] = [:]
    private var seenIds = Set<String>()

    func send(_ events: [Event]) throws {
        switch mode {
        case .offline:
            throw URLError(.notConnectedToInternet)
        case .dropResponseOnce:
            receive(events)
            mode = .online
            throw URLError(.timedOut)
        case .online:
            receive(events)
        }
    }

    private func receive(_ events: [Event]) {
        for event in events {
            if let id = event.id, !seenIds.insert(id).inserted { continue }   // 有 id：同一個只算一次
            counts[event.name, default: 0] += 1
        }
    }
}

private protocol Tracker: AnyObject {
    func track(_ name: String)
    func flush()
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, makeTracker: (FakeCollector) -> Tracker) {
    let collector = FakeCollector()
    let tracker = makeTracker(collector)

    collector.mode = .dropResponseOnce
    tracker.track("order_form_viewed"); tracker.flush()         // ① 伺服器收到，回應遺失
    collector.mode = .offline
    tracker.track("order_submitted"); tracker.flush()           // ② 沒有網路
    collector.mode = .online
    tracker.track("order_acked"); tracker.flush()               // ③ 網路恢復
    tracker.flush()                                             // 使用者回到前景，再送一次

    let c = collector.counts
    log("伺服器算到：order_form_viewed ×\(c["order_form_viewed", default: 0])、order_submitted ×\(c["order_submitted", default: 0])、order_acked ×\(c["order_acked", default: 0])")
}

// MARK: - Bug 版
private enum L06Bug {
    final class SimpleTracker: Tracker {
        private let collector: FakeCollector
        init(collector: FakeCollector) { self.collector = collector }

        func track(_ name: String) {
            let event = Event(name: name, id: nil)              // 沒有 id，伺服器沒辦法去除重複
            if (try? collector.send([event])) == nil {
                _ = try? collector.send([event])                // 失敗就立刻重送一次，還是失敗就丟掉
            }
        }

        func flush() {}
    }

    static func run(_ log: LabLog) async {
        scenario(log) { SimpleTracker(collector: $0) }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    final class QueuedTracker: Tracker {
        private let collector: FakeCollector
        private var queue: [Event] = []                         // 真正的 App 會存到檔案或資料庫，重新啟動後還在

        init(collector: FakeCollector) { self.collector = collector }

        func track(_ name: String) {
            queue.append(Event(name: name, id: UUID().uuidString))   // 每個事件有唯一的 eventId
        }

        func flush() {
            guard !queue.isEmpty else { return }
            do {
                try collector.send(queue)
                queue.removeAll()                               // 成功才移出佇列
            } catch {
                // 失敗：留在佇列裡，下次 flush 再送（伺服器用 eventId 去重）
            }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { QueuedTracker(collector: $0) }
    }
}

// MARK: - 演練設定
extension Advanced10Observability {
    static let l06 = Lab(
        id: "L06",
        title: "事件算了兩次，又有事件不見了",
        question: "第 5 題：analytics 事件怎麼設計，才能算出漏斗又不洩漏資料？（重送與離線）",
        goal: "事件要先進本機佇列，每個事件有唯一的 id；送出成功才移除，失敗留著重送，伺服器用 id 去重。",
        steps: [
            "選「Bug 版」執行，看伺服器對三個事件各算了幾次：總數對嗎？每個事件對嗎？",
            "想一想：order_form_viewed 的回應遺失了，App 怎麼知道伺服器有沒有收到？重送會發生什麼事？沒有網路時送的事件去哪了？",
            "選「修正版」執行：每個事件剛好算 1 次。",
            "進階：真正的 App 把佇列存到檔案或資料庫，App 被終止後還在；可以批次送出以省電，並限制佇列上限與保留期限。",
        ],
        expectBug: "「伺服器算到：order_form_viewed ×2、order_submitted ×0、order_acked ×1」：總共 3 次，但重複了一個、遺失了一個。",
        expectFixed: "「伺服器算到：order_form_viewed ×1、order_submitted ×1、order_acked ×1」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("伺服器算到") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("伺服器算到：order_form_viewed ×2、order_submitted ×0、order_acked ×1") {
                return "Bug 還沒重現：track 要直接送出，失敗就立刻重送一次、再失敗就丟掉（事件不帶 id）"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("伺服器算到") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("伺服器算到：order_form_viewed ×1、order_submitted ×1、order_acked ×1") {
                return "每個事件都應該剛好算 1 次：事件要有唯一的 id、先放進佇列，flush 成功才清掉，失敗留著"
            }
            return nil
        }
    )
}
