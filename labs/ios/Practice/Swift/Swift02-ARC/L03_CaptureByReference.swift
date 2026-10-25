// L03：確認下單的價格，和使用者按下單時看到的不一樣（closure 捕獲的是變數本身）
// 對應複習站：Swift02ARC 第 3 題「closure 是怎麼帶走外面的變數的？」
//
// 情境：使用者看到報價 1500 萬，按「下單」，App 先跳出確認視窗，使用者按下「確認」時才真的送出。
//       在使用者看確認視窗的這段時間，新的行情推播進來，報價變成 1510 萬。
//
// 你要寫：
//   ① Bug 版：建立 confirm closure，裡面直接用 price（不加 capture list）。
//   ② 修正版：同樣的 closure，用 capture list 把建立 closure 這一刻的 price 複製進去。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift02-ARC/L03_CaptureByReference.swift
import LabKit

// MARK: - Bug 版
private enum L03Bug {
    static func run(_ log: LabLog) async {
        var price = 15_000_000
        log("使用者看到報價 \(price)，按下下單")

        // TODO ①：寫 confirm closure：log("按下確認，送出委託，價格 …")，裡面直接用 price（不加 capture list）
        let confirm: () -> Void = {}

        price = 15_100_000                                // 使用者還在看確認視窗時，新的推播進來
        log("行情推播：報價變成 \(price)")
        confirm()
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func run(_ log: LabLog) async {
        var price = 15_000_000
        log("使用者看到報價 \(price)，按下下單")

        // TODO ②：同樣的 confirm closure，但在 { 後面加 capture list，捕獲建立 closure 這一刻 price 的值
        let confirm: () -> Void = {}

        price = 15_100_000
        log("行情推播：報價變成 \(price)")
        confirm()
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l03 = Lab(
        id: "L03",
        title: "確認下單的價格被偷偷換成新報價",
        question: "第 3 題：closure 是怎麼帶走外面的變數的？",
        goal: "使用者按「下單」時看到的是 1500 萬，等他按「確認」，送出去的價格也要是 1500 萬，不能被中間的行情推播改掉。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行「送出委託，價格」是多少。",
            "想一想：closure 捕獲的是 price 這個變數本身，還是建立 closure 那一刻的值？",
            "寫 TODO ②，選「修正版」執行，比較最後一行。",
        ],
        expectBug: "最後一行是「按下確認，送出委託，價格 15100000」：送出的是新報價，不是使用者看到的 1500 萬。",
        expectFixed: "最後一行是「按下確認，送出委託，價格 15000000」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("行情推播：報價變成 15100000") else { return "演練流程要先更新報價，再呼叫 confirm()" }
            return lines.last == "按下確認，送出委託，價格 15100000"
                ? nil : "Bug 還沒重現：confirm 要直接用 price（不加 capture list），才會讀到最新的 15100000"
        },
        checkFixed: { lines in
            guard lines.contains("行情推播：報價變成 15100000") else { return "演練流程要先更新報價，再呼叫 confirm()" }
            return lines.last == "按下確認，送出委託，價格 15000000"
                ? nil : "送出的價格應該是使用者看到的 15000000：capture list 寫 [price]"
        }
    )
}
