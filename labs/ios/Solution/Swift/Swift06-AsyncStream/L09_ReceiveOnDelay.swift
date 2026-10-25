// L09：send 之後立刻檢查，值還沒送到（receive(on: DispatchQueue.main) 會晚一個 run loop）
// 對應複習站：Swift06AsyncStream 第 4 題「Combine 的 debounce、throttle、share、switchToLatest 實際上怎麼運作？」（receive(on:) 的時序）
//
// 情境：PriceModel 把價格推播經過 receive(on: DispatchQueue.main) 再存進 received，確保在主執行緒更新畫面。
//       寫單元測試時，送出一筆價格，馬上檢查 received。
// Bug 版：send 之後立刻檢查。receive(on:) 就算已經在主執行緒，也會把值排到下一個 run loop（主執行緒的事件迴圈）才送出，
//        所以 send 之後的程式碼先執行，檢查到的是空的，測試失敗。
// 修正版：等值真的送達之後再檢查（單元測試用 XCTestExpectation，這裡用等到收到值或逾時）。
import Combine
import Foundation
import LabKit

/// 價格模型（兩個版本共用）：價格經過 receive(on:) 回到主執行緒再存起來
@MainActor
private final class PriceModel {
    let input = PassthroughSubject<Int, Never>()
    private(set) var received: [Int] = []
    private var cancellable: AnyCancellable?

    init() {
        cancellable = input
            .receive(on: DispatchQueue.main)
            .sink { [weak self] price in
                MainActor.assumeIsolated { self?.received.append(price) }
            }
    }
}

// MARK: - Bug 版
private enum L09Bug {
    @MainActor
    static func run(_ log: LabLog) async {
        let model = PriceModel()
        model.input.send(15_000_000)
        log("send 之後立刻檢查：收到 \(model.received)")            // 值還在排隊，還沒送到
    }
}

// MARK: - 修正版
private enum L09Fixed {
    @MainActor
    static func run(_ log: LabLog) async {
        let model = PriceModel()
        model.input.send(15_000_000)
        for _ in 0..<100 where model.received.isEmpty {              // 等值送達（最多 1 秒），就像 XCTestExpectation
            try? await Task.sleep(for: .milliseconds(10))
        }
        log("等值送達之後再檢查：收到 \(model.received)")
    }
}

// MARK: - 演練設定
extension Swift06AsyncStream {
    static let l09 = Lab(
        id: "L09",
        title: "send 之後立刻檢查，收到的是空的",
        question: "第 4 題：Combine 的 debounce、throttle、share、switchToLatest 實際上怎麼運作？（receive(on:) 的時序）",
        goal: "測試送出一筆價格後要檢查收到的值。找出為什麼立刻檢查會落空，改成等值送達再檢查。",
        steps: [
            "選「Bug 版」執行，看那一行：send 之後立刻檢查，收到什麼。",
            "想一想：receive(on: DispatchQueue.main) 在主執行緒上呼叫時，值是同步送到還是排到下一個 run loop？",
            "選「修正版」執行：等值送達之後再檢查，收到 [15000000]。",
            "進階：單元測試可以注入 scheduler（正式碼用 DispatchQueue.main，測試用 ImmediateScheduler.shared），或用 XCTestExpectation 等值送達。",
        ],
        expectBug: "send 之後立刻檢查：收到 []。",
        expectFixed: "等值送達之後再檢查：收到 [15000000]。",
        run: { variant, log in
            switch variant {
            case .bug: await L09Bug.run(log)
            case .fixed: await L09Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.last == "send 之後立刻檢查：收到 []"
                ? nil : "Bug 還沒重現：send 之後立刻檢查，應該收到空的 []"
        },
        checkFixed: { lines in
            lines.last == "等值送達之後再檢查：收到 [15000000]"
                ? nil : "等值送達之後再檢查，應該收到 [15000000]：send 之後要等 received 有值（最多 1 秒）"
        }
    )
}
