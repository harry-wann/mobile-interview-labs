// L06：行情一直在跳，畫面卻一直沒更新（行情用 debounce，被餓死了）
// 對應複習站：Swift06AsyncStream 第 4 題「Combine 的 debounce、throttle、share、switchToLatest 實際上怎麼運作？」
//
// 情境：行情每 50 毫秒送來一筆價格，連續送 1 秒（共 20 筆）。畫面想把更新頻率降到每 200 毫秒一次。
//
// 你要寫：
//   ① Bug 版：對 ticks 用 debounce(for: 200 毫秒, scheduler: DispatchQueue.main)，sink 時 updates.hit()，存進 cancellables。
//   ② 修正版：改用 throttle(for: 200 毫秒, scheduler: DispatchQueue.main, latest: true)。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift06-AsyncStream/L06_DebounceStarvation.swift
import Combine
import Foundation
import LabKit

// MARK: - Bug 版
private enum L06Bug {
    static func run(_ log: LabLog) async {
        let ticks = PassthroughSubject<Int, Never>()
        let updates = Swift06AsyncStream.Counter()
        var cancellables = Set<AnyCancellable>()
        // TODO ①：ticks.debounce(for: .milliseconds(200), scheduler: DispatchQueue.main)，再 sink { updates.hit() }，store(in: &cancellables)

        for price in 1...20 {                                        // 行情持續 1 秒，每 50 毫秒一筆
            ticks.send(price)
            try? await Task.sleep(for: .milliseconds(50))
        }
        log("行情持續 1 秒，畫面更新了 \(updates.count) 次")
        try? await Task.sleep(for: .milliseconds(500))
        log("行情停止後，畫面總共更新了 \(updates.count) 次")
        withExtendedLifetime(cancellables) {}                        // 訂閱要活到這裡（Release 建置的區域變數用完最後一次就會被釋放）
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func run(_ log: LabLog) async {
        let ticks = PassthroughSubject<Int, Never>()
        let updates = Swift06AsyncStream.Counter()
        var cancellables = Set<AnyCancellable>()
        ticks
            .debounce(for: .milliseconds(200), scheduler: DispatchQueue.main)   // 已寫好，和 Bug 版一樣
            // TODO ②：把 debounce 換成 throttle（同樣 200 毫秒、DispatchQueue.main，要送出區間內最新的一筆）
            .sink { _ in updates.hit() }
            .store(in: &cancellables)

        for price in 1...20 {
            ticks.send(price)
            try? await Task.sleep(for: .milliseconds(50))
        }
        log("行情持續 1 秒，畫面更新了 \(updates.count) 次")
        try? await Task.sleep(for: .milliseconds(500))
        log("行情停止後，畫面總共更新了 \(updates.count) 次")
        withExtendedLifetime(cancellables) {}                        // 訂閱要活到這裡（Release 建置的區域變數用完最後一次就會被釋放）
    }
}

// MARK: - 演練設定
private func updateCount(prefix: String, in lines: [String]) -> Int? {
    lines.first { $0.hasPrefix(prefix) }
        .flatMap { Int($0.dropFirst(prefix.count).prefix { $0.isNumber }) }
}

extension Swift06AsyncStream {
    static let l06 = Lab(
        id: "L06",
        title: "行情一直在跳，畫面卻不更新",
        question: "第 4 題：Combine 的 debounce、throttle、share、switchToLatest 實際上怎麼運作？",
        goal: "行情每 50 毫秒一筆，畫面要降到每 200 毫秒更新一次，而且行情持續進來的時候也要持續更新。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看兩行 log：行情持續的 1 秒內畫面更新了幾次，行情停止後總共更新幾次，出現 ✓。",
            "想一想：debounce 的計時什麼時候會被重置？行情一直進來，計時還走得完嗎？",
            "寫 TODO ②，選「修正版」執行：行情持續的 1 秒內，畫面大約每 200 毫秒更新一次。",
            "進階：把 latest: true 改成 false，看送出的是該區間第一筆還是最新一筆（行情要用最新的）。",
        ],
        expectBug: "行情持續 1 秒，畫面更新了 0 次；行情停止後，畫面總共更新了 1 次。",
        expectFixed: "行情持續 1 秒，畫面更新了 5 到 6 次（本機實測 6 次：第一筆馬上送，之後每 200 毫秒一次）；行情停止後，畫面總共更新了 6 次左右。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let during = updateCount(prefix: "行情持續 1 秒，畫面更新了 ", in: lines),
                  let total = updateCount(prefix: "行情停止後，畫面總共更新了 ", in: lines) else { return "找不到更新次數：要對 ticks 接一個節流運算子，再 sink 時 updates.hit()" }
            guard total >= 1 else { return "行情停止後，畫面至少要更新一次" }
            return during == 0 ? nil : "Bug 還沒重現：行情持續進來的 1 秒內，畫面應該一次都沒更新（要用 debounce）。實際更新 \(during) 次"
        },
        checkFixed: { lines in
            guard let during = updateCount(prefix: "行情持續 1 秒，畫面更新了 ", in: lines) else { return "找不到更新次數：要對 ticks 接一個節流運算子，再 sink 時 updates.hit()" }
            return during >= 3 ? nil : "行情持續的 1 秒內，畫面只更新了 \(during) 次：要用 throttle(for:scheduler:latest: true)，固定間隔送出最新的一筆"
        }
    )
}
