// L03：確認下單的價格，和使用者按下單時看到的不一樣（closure 捕獲的是變數本身）
// 對應複習站：Swift02ARC 第 3 題「closure 是怎麼帶走外面的變數的？」
//
// 情境：使用者看到報價 1500 萬，按「下單」，App 先跳出確認視窗，
// 使用者按下「確認」時才真的送出。
//       在使用者看確認視窗的這段時間，新的行情推播進來，報價變成 1510 萬。
// Bug 版：「確認」的 closure 直接用 price。closure 捕獲的是變數本身，
// 不是當時的值，送出時讀到的是最新的 1510 萬。
// 修正版：capture list 寫 [price]，在建立 closure 的這一刻把值複製進去，
// 送出的永遠是使用者看到的 1500 萬。
import LabKit

// MARK: - Bug 版
private enum L03Bug {
    static func run(_ log: LabLog) async {
        var price = 15_000_000
        log("使用者看到報價 \(price)，按下下單")

        let confirm = {
            // 捕獲的是變數 price 本身
            log("按下確認，送出委託，價格 \(price)")
        }

        // 使用者還在看確認視窗時，新的推播進來
        price = 15_100_000
        log("行情推播：報價變成 \(price)")
        confirm()
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func run(_ log: LabLog) async {
        var price = 15_000_000
        log("使用者看到報價 \(price)，按下下單")

        let confirm = { [price] in
            // [price]：建立 closure 這一刻的值，之後 price 怎麼變都不影響
            log("按下確認，送出委託，價格 \(price)")
        }

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
            "選「Bug 版」執行，看最後一行「送出委託，價格」是多少。",
            "想一想：closure 捕獲的是 price 這個變數本身，還是建立 closure 那一刻的值？",
            "選「修正版」執行，比較最後一行。",
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
            guard lines.contains("行情推播：報價變成 15100000") else {
                return "演練流程要先更新報價，再呼叫 confirm()"
            }
            return lines.last == "按下確認，送出委託，價格 15100000"
                ? nil
                : "Bug 還沒重現：confirm 要直接用 price（不加 capture list），才會讀到最新的 15100000"
        },
        checkFixed: { lines in
            guard lines.contains("行情推播：報價變成 15100000") else {
                return "演練流程要先更新報價，再呼叫 confirm()"
            }
            return lines.last == "按下確認，送出委託，價格 15000000"
                ? nil : "送出的價格應該是使用者看到的 15000000：capture list 寫 [price]"
        }
    )
}
