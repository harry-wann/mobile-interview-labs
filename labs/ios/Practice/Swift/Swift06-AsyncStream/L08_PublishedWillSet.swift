// L08：切換到 ETH，畫面卻載入了 BTC 的深度（在 $symbol.sink 裡讀 self.symbol，
// 讀到的是舊值）
// 對應複習站：Swift06AsyncStream 第 4 題「Combine 的 debounce、throttle、
// share、switchToLatest 實際上怎麼運作？」
//
// 情境：交易對選擇器的 symbol 是 @Published。symbol 改變時，
// 要載入新交易對的深度。
//
// 你要寫：
//   ① Bug 版：$symbol 接 dropFirst，sink 裡（用 [weak self]）讀 self.symbol，
// log("載入深度：\(self.symbol)")，store(in: &cancellables)。
//   ② 修正版：sink 改用傳進來的參數 newSymbol，log("載入深度：\(newSymbol)")。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift06-AsyncStream/L08_PublishedWillSet.swift
import Combine
import Foundation
import LabKit

// MARK: - Bug 版
private enum L08Bug {
    final class SymbolPicker {
        @Published var symbol = "BTC/JPY"
        private var cancellables = Set<AnyCancellable>()

        init(log: LabLog) {
            // TODO ①：$symbol.dropFirst().sink { [weak self] _ in … }：裡面
            // log("載入深度：\(self.symbol)")（讀屬性），再 store(in:
            // &cancellables)
        }
    }

    static func run(_ log: LabLog) async {
        let picker = SymbolPicker(log: log)
        // 使用者改選 ETH
        picker.symbol = "ETH/JPY"
        log("使用者選的是：\(picker.symbol)")
    }
}

// MARK: - 修正版
private enum L08Fixed {
    final class SymbolPicker {
        @Published var symbol = "BTC/JPY"
        private var cancellables = Set<AnyCancellable>()

        init(log: LabLog) {
            $symbol
                .dropFirst()
                .sink { [weak self] _ in
                    guard let self else { return }
                    // 已寫好，和 Bug 版一樣
                    log("載入深度：\(self.symbol)")
                    // TODO ②：改用 sink 傳進來的參數（把 _ 換成 newSymbol），
                    // log("載入深度：\(newSymbol)")
                }
                .store(in: &cancellables)
        }
    }

    static func run(_ log: LabLog) async {
        let picker = SymbolPicker(log: log)
        picker.symbol = "ETH/JPY"
        log("使用者選的是：\(picker.symbol)")
    }
}

// MARK: - 演練設定
extension Swift06AsyncStream {
    static let l08 = Lab(
        id: "L08",
        title: "切換到 ETH，卻載入了 BTC 的深度",
        question:
            "第 4 題：Combine 的 debounce、throttle、share、switchToLatest 實際上怎麼運作？（@Published 的發送時機）",
        goal: "symbol 改成 ETH/JPY 時，要載入的是 ETH 的深度，不是上一個交易對。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，比較兩行 log：載入深度的是哪個交易對、使用者選的是哪個，出現 ✓。",
            "想一想：@Published 是在值改變之前還是之後發送？sink 收到通知的時候，self.symbol 是新值還是舊值？",
            "寫 TODO ②，選「修正版」執行，載入深度的應該是 ETH/JPY。",
        ],
        expectBug: "載入深度：BTC/JPY（舊值）；使用者選的是：ETH/JPY。",
        expectFixed: "載入深度：ETH/JPY；使用者選的是：ETH/JPY。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("使用者選的是：ETH/JPY") else {
                return "要把 symbol 改成 ETH/JPY（run 已寫好）"
            }
            return lines.contains("載入深度：BTC/JPY")
                ? nil : "Bug 還沒重現：sink 裡讀 self.symbol 應該讀到舊值 BTC/JPY"
        },
        checkFixed: { lines in
            guard lines.contains("使用者選的是：ETH/JPY") else {
                return "要把 symbol 改成 ETH/JPY（run 已寫好）"
            }
            return lines.contains("載入深度：ETH/JPY")
                ? nil : "應該載入 ETH/JPY：sink 要用傳進來的參數，不要讀 self.symbol"
        }
    )
}
