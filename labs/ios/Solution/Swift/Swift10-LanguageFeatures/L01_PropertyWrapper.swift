// L01：按了「＋」，下單張數超過上限 100（property wrapper）
// 對應複習站：Swift10LanguageFeatures 第 1 題「property wrapper 是什麼？解決什麼問題？」
//
// 情境：下單頁的「張數」必須落在 1 到 100 之間。這個數字有三條路會被改：使用者輸入、按「＋」、還原上次的設定。
// Bug 版：用普通的 var，在每個會改它的地方自己寫「夾在範圍內」。「＋」那一條漏寫了，張數被加到 110，超過上限的單就送出去了。
// 修正版：寫一個 @Clamped(1...100) 的 property wrapper，所有寫入的路徑都自動夾在範圍內，不用每個地方各記一次。
import Foundation
import LabKit

// MARK: - Bug 版
private enum L01Bug {
    struct QuantityInput {
        var lots = 1

        mutating func type(_ n: Int) { lots = min(max(n, 1), 100) }       // 使用者輸入：有夾
        mutating func plus(_ n: Int) { lots += n }                         // 按「＋」：漏了夾
        mutating func restore(_ n: Int) { lots = min(max(n, 1), 100) }    // 還原設定：有夾
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
            self.value = min(max(wrappedValue, range.lowerBound), range.upperBound)     // 初始值也夾住
        }

        // wrappedValue：屬性本身的值，所有寫入的路徑都走這裡
        var wrappedValue: Value {
            get { value }
            set { value = min(max(newValue, range.lowerBound), range.upperBound) }
        }

        // projectedValue：用 $ 前綴取得的附加資訊，這裡回傳允許的範圍
        var projectedValue: ClosedRange<Value> { range }
    }

    struct QuantityInput {
        @Clamped(1...100) var lots = 1

        mutating func type(_ n: Int) { lots = n }
        mutating func plus(_ n: Int) { lots += n }          // 不用自己夾：wrapper 會處理
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
        goal: "下單張數必須在 1 到 100 之間，改它的地方有三個。先寫出漏了其中一個的 Bug 版，再用 property wrapper 讓每條路徑都自動守住範圍。",
        steps: [
            "選「Bug 版」執行，看 log：「再按＋20 之後」的張數。",
            "選「修正版」執行：張數被夾在 100，最後一行用 $lots 取得允許的範圍。",
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
            lines.contains("再按「＋20」之後：110") ? nil : "Bug 還沒重現：按「＋」的那一條路徑不要夾，張數要變成 110"
        },
        checkFixed: { lines in
            if !lines.contains("輸入 90 張：90") { return "輸入 90 張應該是 90" }
            if !lines.contains("再按「＋20」之後：100") { return "按「＋20」之後應該被夾在 100：wrappedValue 的 set 要夾住" }
            if !lines.contains("還原成 -5 張：1") { return "還原成 -5 張應該被夾在 1" }
            if !lines.contains("允許的範圍（$lots）：1...100") { return "$lots 應該回傳允許的範圍 1...100：實作 projectedValue" }
            return nil
        }
    )
}
