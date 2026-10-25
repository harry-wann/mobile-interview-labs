// L07：切換到 ETH，畫面卻被慢回來的 BTC 深度蓋掉（用 flatMap，
// 舊請求的回應沒有被取消）
// 對應複習站：Swift06AsyncStream 第 4 題「Combine 的 debounce、throttle、
// share、switchToLatest 實際上怎麼運作？」
//
// 情境：下單頁切換交易對時重新抓訂單簿深度。BTC 的回應要 300 毫秒，ETH 只要
// 100 毫秒。使用者先選 BTC，50 毫秒後改選 ETH。
//
// 你要寫：
//   ① Bug 版：$symbol 接 dropFirst、removeDuplicates，map 成 fetchDepth($0)，
// 再 flatMap { $0 } 展開，receive(on: DispatchQueue.main)，assign(to: &$book)。
//   ② 修正版：把 flatMap 換成 switchToLatest()。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift06-AsyncStream/L07_SwitchToLatest.swift
import Combine
import Foundation
import LabKit

/// 假的深度 API（兩個版本共用）：BTC 慢、ETH 快
private func fetchDepth(_ symbol: String) -> AnyPublisher<String, Never> {
    Just("\(symbol) 的深度")
        .delay(
            for: .milliseconds(symbol == "BTC/JPY" ? 300 : 100),
            scheduler: DispatchQueue.global()
        )
        .eraseToAnyPublisher()
}

// MARK: - Bug 版
private enum L07Bug {
    final class DepthViewModel {
        @Published var symbol = ""
        @Published private(set) var book = "（還沒有資料）"

        init() {
            $symbol
                .dropFirst()
                .removeDuplicates()
            // TODO ①：接著 map { fetchDepth($0) }，再用 flatMap 展開，.
            // receive(on: DispatchQueue.main)，.assign(to: &$book)
        }
    }

    static func run(_ log: LabLog) async {
        let viewModel = DepthViewModel()
        viewModel.symbol = "BTC/JPY"
        try? await Task.sleep(for: .milliseconds(50))
        // 使用者改選 ETH
        viewModel.symbol = "ETH/JPY"
        try? await Task.sleep(for: .milliseconds(500))
        log("選擇的交易對：ETH/JPY，畫面顯示：\(viewModel.book)")
    }
}

// MARK: - 修正版
private enum L07Fixed {
    final class DepthViewModel {
        @Published var symbol = ""
        @Published private(set) var book = "（還沒有資料）"

        init() {
            $symbol
                .dropFirst()
                .removeDuplicates()
                .map { fetchDepth($0) }
                // 已寫好，和 Bug 版一樣
                .flatMap { $0 }
                // TODO ②：把 flatMap 換成 switchToLatest()
                .receive(on: DispatchQueue.main)
                .assign(to: &$book)
        }
    }

    static func run(_ log: LabLog) async {
        let viewModel = DepthViewModel()
        viewModel.symbol = "BTC/JPY"
        try? await Task.sleep(for: .milliseconds(50))
        viewModel.symbol = "ETH/JPY"
        try? await Task.sleep(for: .milliseconds(500))
        log("選擇的交易對：ETH/JPY，畫面顯示：\(viewModel.book)")
    }
}

// MARK: - 演練設定
extension Swift06AsyncStream {
    static let l07 = Lab(
        id: "L07",
        title: "切換到 ETH，畫面被舊的 BTC 蓋掉",
        question:
            "第 4 題：Combine 的 debounce、throttle、share、switchToLatest 實際上怎麼運作？",
        goal: "使用者快速切換交易對，畫面最後顯示的一定要是最後選的那個交易對。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：選的是 ETH，畫面顯示的是哪個交易對的深度，出現 ✓。",
            "想一想：flatMap 對還沒完成的舊請求做了什麼？",
            "寫 TODO ②，選「修正版」執行，畫面應該顯示 ETH 的深度。",
            "進階：flatMap(maxPublishers: .max(1)) 是排隊一個一個來，也不能取代 switchToLatest。",
        ],
        expectBug: "選擇的交易對：ETH/JPY，畫面顯示：BTC/JPY 的深度。",
        expectFixed: "選擇的交易對：ETH/JPY，畫面顯示：ETH/JPY 的深度。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.last == "選擇的交易對：ETH/JPY，畫面顯示：BTC/JPY 的深度"
                ? nil : "Bug 還沒重現：畫面應該（錯誤地）顯示慢回來的 BTC 深度（map 之後用 flatMap 展開）"
        },
        checkFixed: { lines in
            lines.last == "選擇的交易對：ETH/JPY，畫面顯示：ETH/JPY 的深度"
                ? nil : "畫面應該顯示 ETH 的深度：map 之後改用 switchToLatest"
        }
    )
}
