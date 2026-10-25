// L01：按了「＋」，下單張數超過上限 100（property wrapper）
// 對應複習站：Swift10LanguageFeatures 第 1 題「property wrapper
// 是什麼？解決什麼問題？」
//
// 情境：下單頁的「張數」必須落在 1 到 100 之間。這個數字有三條路會被改：
// 使用者輸入、按「＋」、還原上次的設定。
//
// 你要寫：
//   ① Bug 版：QuantityInput 用普通的 var，三條會改張數的路徑裡，「按＋」
// 那一條漏了夾在 1...100。
//   ② 修正版：把 Clamped property wrapper 寫完整（init、wrappedValue 的 set、
// projectedValue），QuantityInput 就不用自己夾。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift10-LanguageFeatures/L01_PropertyWrapper.swift
import Foundation
import LabKit

// MARK: - Bug 版
private enum L01Bug {
    struct QuantityInput {
        var lots = 1

        // 使用者輸入：有夾
        mutating func type(_ n: Int) { lots = min(max(n, 1), 100) }
        // TODO ①：按「＋」：直接把 n 加到 lots 上，這一條路徑漏了夾在 1...100
        // 裡（Bug 就出在這裡）
        mutating func plus(_ n: Int) {}
        // 還原設定：有夾
        mutating func restore(_ n: Int) { lots = min(max(n, 1), 100) }
    }

    static func run(_ log: LabLog) async {
        var input = QuantityInput()
        input.type(90)
        log("輸入 90 張：\(input.lots)")
        input.plus(20)
        log("再按「＋20」之後：\(input.lots)")
        input.restore(-5)
        log("還原成 -5 張：\(input.lots)")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    @propertyWrapper
    struct Clamped<Value: Comparable> {
        private var value: Value
        let range: ClosedRange<Value>

        init(wrappedValue: Value, _ range: ClosedRange<Value>) {
            self.range = range
            // TODO ②-1：初始值也要夾在 range 裡（比下限小就用下限，
            // 比上限大就用上限）
            self.value = wrappedValue
        }

        var wrappedValue: Value {
            get { value }
            // TODO ②-2：每次寫入都要夾在 range 裡
            set { value = newValue }
        }

        // TODO ②-3：projectedValue 是用 $ 前綴取得的值，要回傳允許的範圍
        // range（現在回傳的是錯的）
        var projectedValue: ClosedRange<Value> {
            range.lowerBound...range.lowerBound
        }
    }

    struct QuantityInput {
        @Clamped(1...100) var lots = 1

        mutating func type(_ n: Int) { lots = n }
        // 不用自己夾：wrapper 會處理
        mutating func plus(_ n: Int) { lots += n }
        mutating func restore(_ n: Int) { lots = n }
    }

    static func run(_ log: LabLog) async {
        var input = QuantityInput()
        input.type(90)
        log("輸入 90 張：\(input.lots)")
        input.plus(20)
        log("再按「＋20」之後：\(input.lots)")
        input.restore(-5)
        log("還原成 -5 張：\(input.lots)")
        log("允許的範圍（$lots）：\(input.$lots)")
    }
}

// MARK: - 演練設定
extension Swift10LanguageFeatures {
    static let l01 = Lab(
        id: "L01",
        title: "按「＋」之後，張數超過上限",
        question: "第 1 題：property wrapper 是什麼？解決什麼問題？",
        goal:
            "下單張數必須在 1 到 100 之間，改它的地方有三個。先寫出漏了其中一個的 Bug 版，再用 property wrapper 讓每條路徑都自動守住範圍。",
        steps: [
            "打開 Practice/Swift/Swift10-LanguageFeatures/L01_PropertyWrapper.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 log：「再按＋20 之後」的張數。",
            "寫 TODO ②，選「修正版」執行：張數被夾在 100，最後一行用 $lots 取得允許的範圍。",
            "對照兩個版本的 plus：Bug 版要自己夾，修正版只寫 lots += n。",
            "想想還有哪些地方也會漏：初始值、反序列化、直接指派。wrapper 的 init 與 wrappedValue 的 set 都處理了嗎？",
        ],
        expectBug: "輸入 90 張之後按「＋20」，張數變成 110，超過上限 100。",
        expectFixed: "按「＋20」之後張數是 100（被夾住）；還原成 -5 張得到 1；$lots 是 1...100。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("再按「＋20」之後：110")
                ? nil : "Bug 還沒重現：按「＋」的那一條路徑不要夾，張數要變成 110"
        },
        checkFixed: { lines in
            if !lines.contains("輸入 90 張：90") { return "輸入 90 張應該是 90" }
            if !lines.contains("再按「＋20」之後：100") {
                return "按「＋20」之後應該被夾在 100：wrappedValue 的 set 要夾住"
            }
            if !lines.contains("還原成 -5 張：1") { return "還原成 -5 張應該被夾在 1" }
            if !lines.contains("允許的範圍（$lots）：1...100") {
                return "$lots 應該回傳允許的範圍 1...100：實作 projectedValue"
            }
            return nil
        }
    )
}
