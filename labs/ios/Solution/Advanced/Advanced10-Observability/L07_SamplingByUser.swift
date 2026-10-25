// L07：抽樣之後，漏斗的轉換率從 60% 變成 10%，error
// 事件也只剩一成（抽樣要以使用者為單位，error 一律送）
// 對應複習站：Advanced10Observability 第 5 題「analytics 事件怎麼設計，
// 才能算出漏斗又不洩漏資料？」（遠端 log 要有取樣與預算，見第 1 題）
//
// 情境：為了省流量與費用，App 對事件抽樣，只送 10%。演練有 2000 位使用者，
// 每位都有 order_form_viewed；其中 60% 有 order_submitted、
//       50% 有 order_acked、40% 有 order_filled；另有 40 位使用者遇到 1 次
// error 事件。分析端要算：
//       「有 order_form_viewed 紀錄的使用者，有多少比例也有 order_submitted
// 紀錄」，真實值是 60%。
// Bug 版：每個事件各自擲一次骰子（隨機數小於 10% 才送）。
// 一位使用者的各個事件被分開抽，大部分使用者只剩下其中一兩個事件，
//        分析端算出來的轉換率只剩約 10%；error 事件也只收到約一成，
// 真正的問題被漏掉。
// 修正版：以使用者為單位抽樣：用 userId 算出固定的分桶（0 到 99），桶號小於 10
// 的使用者，所有事件都送；其他使用者都不送。
//        error 事件不抽樣，一律送。轉換率接近真實的 60%（使用者變少了，
// 但每位使用者的漏斗是完整的）。
import Foundation
import LabKit

private struct AppEvent {
    let userId: String
    let name: String
    var isError: Bool { name == "error" }
}

private let samplePercent = 10

/// 2000 位使用者的所有事件（兩個版本共用）
private let allEvents: [AppEvent] = {
    var events: [AppEvent] = []
    for i in 0..<2_000 {
        let user = "u\(i)"
        events.append(AppEvent(userId: user, name: "order_form_viewed"))
        if i % 10 < 6 {
            events.append(AppEvent(userId: user, name: "order_submitted"))
        }
        if i % 10 < 5 {
            events.append(AppEvent(userId: user, name: "order_acked"))
        }
        if i % 10 < 4 {
            events.append(AppEvent(userId: user, name: "order_filled"))
        }
        if i % 50 == 0 { events.append(AppEvent(userId: user, name: "error")) }
    }
    return events
}()

/// 固定種子的亂數（Bug 版用，結果才穩定）
private struct SeededRandom: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// 演練流程（兩個版本共用）：makeSampler 回傳「這個事件要不要送」
private func scenario(_ log: LabLog, makeSampler: () -> (AppEvent) -> Bool) {
    let shouldSend = makeSampler()
    // 分析端收到的事件
    let received = allEvents.filter(shouldSend)

    let viewedUsers = Set(
        received.filter { $0.name == "order_form_viewed" }.map(\.userId))
    let submittedUsers = Set(
        received.filter { $0.name == "order_submitted" }.map(\.userId))
    let both = viewedUsers.intersection(submittedUsers).count
    let percent = viewedUsers.isEmpty ? 0 : both * 100 / viewedUsers.count
    log("收到 \(received.count) 筆事件（全部 \(allEvents.count) 筆）")
    log(
        "有 order_form_viewed 紀錄的使用者 \(viewedUsers.count) 位，其中也有 order_submitted 紀錄的 \(both) 位：轉換率 \(percent)%（真實是 60%）"
    )
    log(
        "error 事件：收到 \(received.filter(\.isError).count) 筆（實際 \(allEvents.filter(\.isError).count) 筆）"
    )
}

// MARK: - Bug 版
private enum L07Bug {
    static func makeSampler() -> (AppEvent) -> Bool {
        var rng = SeededRandom(state: 2026)
        // 每個事件各擲一次骰子
        return { _ in Int.random(in: 0..<100, using: &rng) < samplePercent }
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeSampler: makeSampler)
    }
}

// MARK: - 修正版
private enum L07Fixed {
    /// FNV-1a 32 位元雜湊：同一個 userId，不管哪一次、哪個平台，分桶都一樣
    static func bucket(of userId: String) -> Int {
        var hash: UInt32 = 2_166_136_261
        for byte in "sample:\(userId)".utf8 {
            hash = (hash ^ UInt32(byte)) &* 16_777_619
        }
        return Int(hash % 100)
    }

    static func makeSampler() -> (AppEvent) -> Bool {
        // error 一律送；其他以使用者為單位
        { event in event.isError || bucket(of: event.userId) < samplePercent }
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeSampler: makeSampler)
    }
}

// MARK: - 演練設定
extension Advanced10Observability {
    static let l07 = Lab(
        id: "L07",
        title: "抽樣之後轉換率從 60% 變 10%",
        question: "第 5 題：analytics 事件怎麼設計，才能算出漏斗又不洩漏資料？（取樣與預算）",
        goal: "抽樣要以使用者為單位（同一位使用者的事件全送或全不送），而且 error 這類關鍵事件不抽樣。",
        steps: [
            "選「Bug 版」執行，看分析端算出來的轉換率與 error 事件的數量。",
            "想一想：每個事件各擲一次骰子，一位使用者的 order_form_viewed 和 order_submitted 同時被送出的機率是多少？",
            "選「修正版」執行：以 userId 分桶抽樣，轉換率回到 60% 附近，error 事件全部收到。",
            "進階：分桶用固定的雜湊（FNV-1a），這樣同一位使用者在 iOS、Android、每次啟動都得到一樣的結果；抽樣比例要隨事件送出，分析端才能還原總量。",
        ],
        expectBug: "轉換率只剩 8%（真實是 60%），error 事件只收到 1 筆（實際 40 筆）。",
        expectFixed:
            "轉換率 56%（接近真實的 60%，以收到的 209 位使用者計算），error 事件收到 40 筆（實際 40 筆）。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let percent = conversionPercent(lines) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !(300...800).contains(receivedCount(lines)) {
                return
                    "Bug 版應該送出約 10% 的事件（約 500 筆）：每個事件各擲一次骰子，隨機數小於 samplePercent 才送"
            }
            if percent > 25 {
                return "Bug 還沒重現：每個事件各擲一次骰子（10% 才送），轉換率應該只剩約 10%，目前 \(percent)%"
            }
            return nil
        },
        checkFixed: { lines in
            guard let percent = conversionPercent(lines) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !(45...75).contains(percent) {
                return "轉換率應該接近 60%（以使用者為單位抽樣），目前 \(percent)%"
            }
            if !lines.contains(where: { $0.hasPrefix("error 事件：收到 40 筆") }) {
                return "error 事件應該一律送出，要收到全部 40 筆"
            }
            return nil
        }
    )
}

private func conversionPercent(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.contains("轉換率") }) else {
        return nil
    }
    return line.components(separatedBy: "轉換率 ").last?.components(
        separatedBy: "%"
    ).first.flatMap { Int($0) }
}

private func receivedCount(_ lines: [String]) -> Int {
    lines.first(where: { $0.hasPrefix("收到 ") })?.components(separatedBy: " ")
        .dropFirst().first.flatMap { Int($0) } ?? 0
}
