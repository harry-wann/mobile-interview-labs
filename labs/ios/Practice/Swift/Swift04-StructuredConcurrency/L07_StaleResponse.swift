// L07：切換到 ETH，畫面卻顯示 BTC 的深度（舊請求的回應蓋掉新資料）
// 對應複習站：Swift04StructuredConcurrency 第 4 題「Task 的取消是怎麼運作的？」
//
// 情境：下單頁可以切換交易對，切換時重新抓訂單簿深度。BTC 的回應要 300 毫秒，
// ETH 只要 100 毫秒。
//       使用者先選 BTC，50 毫秒後改選 ETH。
//
// 你要寫：
//   ① Bug 版：select 每次開一個新 Task 抓深度，抓到就寫進 shown。
//   ② 修正版：select 先取消舊的 Task、記下 selected，再開新 Task 抓深度；
// 抓到之後確認 symbol 還是 selected，才寫進 shown。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift04-StructuredConcurrency/L07_StaleResponse.swift
import Foundation
import LabKit

/// 假的深度 API（兩個版本共用）：BTC 慢、ETH 快；被取消時 Task.sleep 會丟出
/// CancellationError
private func fetchDepth(_ symbol: String) async throws -> String {
    try await Task.sleep(for: .milliseconds(symbol == "BTC/JPY" ? 300 : 100))
    return "\(symbol) 的深度"
}

// MARK: - Bug 版
private enum L07Bug {
    @MainActor
    final class DepthScreen {
        private(set) var shown = "（還沒有資料）"

        func select(_ symbol: String) {
            // TODO ①：開一個 Task：if let depth = try? await
            // fetchDepth(symbol)，抓到就寫進 shown
        }
    }

    static func run(_ log: LabLog) async {
        let screen = await DepthScreen()
        await screen.select("BTC/JPY")
        try? await Task.sleep(for: .milliseconds(50))
        // 使用者改選 ETH
        await screen.select("ETH/JPY")
        // 等兩個回應都回來
        try? await Task.sleep(for: .milliseconds(500))
        log("選擇的交易對：ETH/JPY，畫面顯示：\(await screen.shown)")
    }
}

// MARK: - 修正版
private enum L07Fixed {
    @MainActor
    final class DepthScreen {
        private(set) var shown = "（還沒有資料）"
        private var selected = ""
        private var loading: Task<Void, Never>?

        func select(_ symbol: String) {
            // TODO ②：1. 取消舊的 loading  2. 記下 selected = symbol
            //        3. loading = Task { … }：抓深度（被取消就結束），
            // 抓到之後確認 symbol == selected，才寫進 shown
        }
    }

    static func run(_ log: LabLog) async {
        let screen = await DepthScreen()
        await screen.select("BTC/JPY")
        try? await Task.sleep(for: .milliseconds(50))
        await screen.select("ETH/JPY")
        try? await Task.sleep(for: .milliseconds(500))
        log("選擇的交易對：ETH/JPY，畫面顯示：\(await screen.shown)")
    }
}

// MARK: - 演練設定
extension Swift04StructuredConcurrency {
    static let l07 = Lab(
        id: "L07",
        title: "切換到 ETH，畫面卻顯示 BTC 的深度",
        question: "第 4 題：Task 的取消是怎麼運作的？",
        goal: "使用者快速切換交易對，畫面最後顯示的一定要是最後選的那個交易對，不能被慢回來的舊回應蓋掉。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：選的是 ETH，畫面顯示的是哪個交易對的深度，出現 ✓。",
            "想一想：BTC 的請求比 ETH 慢，它的回應什麼時候到？到了之後誰會擋住它？",
            "寫 TODO ②，選「修正版」執行，畫面應該顯示 ETH 的深度。",
            "進階：SwiftUI 裡用 .task(id: symbol) { } 就是同樣的效果：symbol 變了，框架自動取消舊的 Task、用新的 symbol 重開一個。",
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
                ? nil : "Bug 還沒重現：畫面應該（錯誤地）顯示慢回來的 BTC 深度（抓到就寫進畫面，不要取消、也不要確認）"
        },
        checkFixed: { lines in
            lines.last == "選擇的交易對：ETH/JPY，畫面顯示：ETH/JPY 的深度"
                ? nil : "畫面應該顯示 ETH 的深度：切換時取消舊的 Task，回應回來時確認 symbol 還是目前選的"
        }
    )
}
