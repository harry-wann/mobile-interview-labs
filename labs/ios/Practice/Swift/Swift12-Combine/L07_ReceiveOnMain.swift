// L07：價格在背景執行緒更新到畫面上（Combine 沒有 Driver，少了 receive(on:)）
// 對應複習站：Swift12Combine 第 6 題「observe(on:)、Driver、bind(to:) 在
// Combine 怎麼寫？」
//
// 情境：TickerViewModel 的 priceText 是 @Published，資料來自假的 API，回應像
// URLSession 的 dataTaskPublisher 一樣在背景執行緒送出。演練記下 priceText
// 被更新時在哪條執行緒。
//
// 你要寫：
//   ① Bug 版：init 裡 L07FakeAPI.ticker() 接 map { "價格：\($0)" }，直接
//     assign(to: &$priceText)。
//   ② 修正版：和 ① 一樣，在 assign 之前加 receive(on: DispatchQueue.main)。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift12-Combine/L07_ReceiveOnMain.swift
import Combine
import Foundation
import LabKit

/// 假的行情 API（兩個版本共用）：像 URLSession 的 dataTaskPublisher，
/// 30 毫秒後在背景執行緒送出價格
private enum L07FakeAPI {
    static func ticker() -> AnyPublisher<Int, Never> {
        Just(15_000_000)
            .delay(for: .milliseconds(30), scheduler: DispatchQueue.global())
            .eraseToAnyPublisher()
    }
}

/// 演練流程（兩個版本共用）：畫面訂閱 priceText，記下更新時在哪條執行緒
private func scenario(
    _ log: LabLog,
    owner: AnyObject,
    priceText: Published<String>.Publisher
) async {
    let shown = Swift12Combine.Box<String?>(nil)
    let onMain = Swift12Combine.Box(false)
    let cancellable =
        priceText
        // 跳過訂閱當下送來的初始值「—」
        .dropFirst()
        .sink { text in
            onMain.value = Thread.isMainThread
            shown.value = text
        }
    await Swift12Combine.waitUntil { shown.value != nil }
    log("畫面顯示：\(shown.value ?? "—")")
    log("更新畫面的執行緒：\(onMain.value ? "主執行緒" : "背景執行緒")")
    // ViewModel 和訂閱都要活到這裡
    withExtendedLifetime((owner, cancellable)) {}
}

// MARK: - Bug 版
private enum L07Bug {
    final class TickerViewModel: ObservableObject {
        @Published private(set) var priceText = "—"

        init() {
            // TODO ①：L07FakeAPI.ticker() 接 map，把價格變成「價格：…」的字串，
            // 再 assign(to: &$priceText)
        }
    }

    static func run(_ log: LabLog) async {
        let viewModel = TickerViewModel()
        await scenario(log, owner: viewModel, priceText: viewModel.$priceText)
    }
}

// MARK: - 修正版
private enum L07Fixed {
    final class TickerViewModel: ObservableObject {
        @Published private(set) var priceText = "—"

        init() {
            // TODO ②：和 ① 一樣，但在 assign 之前切到主執行緒
            // （對應 Driver 的「一定在主執行緒」）
        }
    }

    static func run(_ log: LabLog) async {
        let viewModel = TickerViewModel()
        await scenario(log, owner: viewModel, priceText: viewModel.$priceText)
    }
}

// MARK: - 演練設定
extension Swift12Combine {
    static let l07 = Lab(
        id: "L07",
        title: "價格在背景執行緒更新到畫面上",
        question: "第 6 題：observe(on:)、Driver、bind(to:) 在 Combine 怎麼寫？",
        goal: "背景執行緒送來的價格，要在主執行緒寫進 @Published。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：priceText 在背景執行緒被更新，出現 ✓。",
            "想一想：RxSwift 的 Driver 保證了哪三件事？Combine 會自動幫你做哪一件？",
            "寫 TODO ②，選「修正版」執行：priceText 在主執行緒被更新。",
            "進階：把 TickerViewModel 放進一個 SwiftUI 畫面，Bug 版會在 Xcode 出現紫色的執行期警告。",
        ],
        expectBug: "畫面顯示：價格：15000000；更新畫面的執行緒：背景執行緒。",
        expectFixed: "畫面顯示：價格：15000000；更新畫面的執行緒：主執行緒。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("畫面顯示：價格：15000000") else {
                return "priceText 還沒更新：在 init 裡把 ticker() 經過 map 之後 assign(to: &$priceText)"
            }
            return lines.contains("更新畫面的執行緒：背景執行緒")
                ? nil : "Bug 還沒重現：不要加 receive(on:)，讓值在背景執行緒寫入"
        },
        checkFixed: { lines in
            guard lines.contains("畫面顯示：價格：15000000") else {
                return "priceText 還沒更新：在 init 裡把 ticker() 經過 map 之後 assign(to: &$priceText)"
            }
            return lines.contains("更新畫面的執行緒：主執行緒")
                ? nil : "要在主執行緒更新：assign 之前加 receive(on: DispatchQueue.main)"
        }
    )
}
