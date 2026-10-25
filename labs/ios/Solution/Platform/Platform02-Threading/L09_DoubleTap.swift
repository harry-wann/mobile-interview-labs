// L09：連按兩次買入，下了兩張單（先鎖住按鈕，再開始非同步工作）
// 對應複習站：Platform02Threading 第 6 題「行情每秒推幾百筆，執行緒要怎麼設計？」（下單按鈕要立即回饋）
//
// 情境：按下「買入」之後，要先向伺服器取最新報價（約 100 ms），再送出委託。使用者看沒有反應，30 ms 之後又按了一次。
// Bug 版：函式開頭檢查 isSubmitting，但要等取到報價之後才把它設成 true。這 100 ms 之內，第二次按下去看到的還是 false，兩次都送出。
// 修正版：檢查通過之後，在任何 await 之前，先同步把 isSubmitting 設成 true（按鈕馬上鎖住、顯示 loading），做完再放開。
import Foundation
import LabKit

/// 假的伺服器（兩個版本共用）：記下收到幾張委託
private final class Exchange {
    private(set) var orders = 0

    func fetchQuote() async -> Int {
        try? await Task.sleep(for: .milliseconds(100))
        return 15_000_000
    }

    func place(price: Int) {
        orders += 1
    }
}

/// 演練流程（兩個版本共用）：按下買入，30 ms 後又按一次
@MainActor
private func scenario(_ log: LabLog, tap: @MainActor (Exchange) -> Void) async {
    let exchange = Exchange()
    tap(exchange)
    try? await Task.sleep(for: .milliseconds(30))
    tap(exchange)                                       // 使用者看沒有反應，又按了一次
    try? await Task.sleep(for: .milliseconds(300))
    log("連按兩次買入，伺服器收到 \(exchange.orders) 張委託")
}

// MARK: - Bug 版
@MainActor
private enum L09Bug {
    static var isSubmitting = false

    static func tapBuy(_ exchange: Exchange) {
        guard !isSubmitting else { return }
        Task {
            let quote = await exchange.fetchQuote()      // 取報價的 100 ms：旗標還是 false，第二次按下去也通過檢查
            isSubmitting = true                          // 太晚才鎖
            exchange.place(price: quote)
            isSubmitting = false
        }
    }

    static func run(_ log: LabLog) async {
        isSubmitting = false
        await scenario(log, tap: tapBuy)
    }
}

// MARK: - 修正版
@MainActor
private enum L09Fixed {
    static var isSubmitting = false

    static func tapBuy(_ exchange: Exchange) {
        guard !isSubmitting else { return }
        isSubmitting = true                              // 先鎖住：在任何 await 之前
        Task {
            let quote = await exchange.fetchQuote()
            exchange.place(price: quote)
            isSubmitting = false                         // 做完再放開
        }
    }

    static func run(_ log: LabLog) async {
        isSubmitting = false
        await scenario(log, tap: tapBuy)
    }
}

// MARK: - 演練設定
private func orders(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("連按兩次買入，伺服器收到 ") }
        .flatMap { Int($0.replacingOccurrences(of: "連按兩次買入，伺服器收到 ", with: "").replacingOccurrences(of: " 張委託", with: "")) }
}

extension Platform02Threading {
    static let l09 = Lab(
        id: "L09",
        title: "連按兩次買入，下了兩張單",
        question: "第 6 題：行情每秒推幾百筆，執行緒要怎麼設計？（下單按鈕要立即回饋）",
        goal: "按下買入要立刻鎖住按鈕，再開始非同步工作。連按兩次只能送出一張委託。",
        steps: [
            "選「Bug 版」按執行，看「伺服器收到幾張委託」。",
            "找出旗標 isSubmitting 在哪一行才被設成 true：它前面有一個 await。",
            "選「修正版」執行：只收到 1 張委託。",
        ],
        expectBug: "連按兩次買入，伺服器收到 2 張委託。",
        expectFixed: "連按兩次買入，伺服器收到 1 張委託。",
        run: { variant, log in
            switch variant {
            case .bug: await L09Bug.run(log)
            case .fixed: await L09Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = orders(lines) else { return "演練沒有跑完" }
            if n == 0 { return "伺服器沒有收到委託：tapBuy 要先取報價（exchange.fetchQuote），再 exchange.place" }
            return n >= 2 ? nil : "Bug 還沒重現：isSubmitting 要等取到報價之後才設成 true"
        },
        checkFixed: { lines in
            guard let n = orders(lines) else { return "演練沒有跑完" }
            if n == 0 { return "伺服器沒有收到委託：tapBuy 要先取報價（exchange.fetchQuote），再 exchange.place" }
            return n == 1 ? nil : "伺服器收到 \(n) 張委託：把 isSubmitting = true 移到 await 之前（檢查通過之後立刻鎖住）"
        }
    )
}
