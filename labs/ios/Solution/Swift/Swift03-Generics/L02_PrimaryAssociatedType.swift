// L02：成交金額合計居然是 0（any Collection 沒指定元素型別，元素變成 Any）
// 對應複習站：Swift03Generics 第 2 題「Swift 5.7 之後，some 和 any 多了哪些用法？」
//
// 情境：資產畫面要把一批成交金額（円，整數）加總。函式想接受任何集合（Array、Set、ArraySlice…）。
// Bug 版：參數寫 any Collection，沒有指定元素型別。編譯器只知道元素是 Any，只好用 as? Double 轉型，Int 轉不過去，每一筆都被當成 0。
// 修正版：參數寫 some Collection<Int>（primary associated type），元素型別保留下來，直接用 Int 相加。
import LabKit

/// 這次的成交金額（円）（兩個版本共用）
private let amounts = [1_500_500, 1_500_250, 1_500_750]

// MARK: - Bug 版
private enum L02Bug {
    /// 沒有指定 Element：prices 的元素型別對編譯器來說是 Any
    static func totalValue(of amounts: any Collection) -> Double {
        amounts.reduce(0.0) { sum, amount in
            sum + ((amount as? Double) ?? 0)         // Int 不是 Double，轉型失敗 → 當成 0
        }
    }

    static func run(_ log: LabLog) async {
        let collection: any Collection = amounts
        log("any Collection 的 map 結果型別：\(type(of: collection.map { $0 }))")
        log("成交金額合計：\(totalValue(of: amounts))")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    /// some Collection<Int>：指定 Element 是 Int，編譯器知道每個元素的型別
    static func totalValue(of amounts: some Collection<Int>) -> Int {
        amounts.reduce(0, +)
    }

    static func run(_ log: LabLog) async {
        let collection: any Collection<Int> = amounts
        log("any Collection<Int> 的 map 結果型別：\(type(of: collection.map { $0 }))")
        log("成交金額合計：\(totalValue(of: amounts))")
    }
}

// MARK: - 演練設定
extension Swift03Generics {
    static let l02 = Lab(
        id: "L02",
        title: "成交金額合計居然是 0",
        question: "第 2 題：Swift 5.7 之後，some 和 any 多了哪些用法？",
        goal: "一個函式要加總任何集合裡的成交金額（Int）。找出為什麼 any Collection 會得到 0，改用 primary associated type 修好。",
        steps: [
            "選「Bug 版」執行，看兩行 log：map 之後的陣列型別是 Array<Any>，合計是 0.0。",
            "想一想：amount as? Double 為什麼轉不過去？",
            "選「修正版」執行，map 的結果型別是 Array<Int>，合計是 4501500。",
            "進階：試著寫 Set<any Hashable>，看編譯錯誤；再把 some Collection<Int> 的 <Int> 拿掉，看 reduce(0, +) 的編譯錯誤。",
        ],
        expectBug: "map 之後的型別是 Array<Any>，成交金額合計：0.0。",
        expectFixed: "map 之後的型別是 Array<Int>，成交金額合計：4501500。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("any Collection 的 map 結果型別：Array<Any>") else { return "找不到 map 結果型別這一行：totalValue 的參數要是 any Collection（不指定元素型別）" }
            return lines.last == "成交金額合計：0.0"
                ? nil : "Bug 還沒重現：合計應該是 0.0（參數寫 any Collection，用 as? Double 轉型）"
        },
        checkFixed: { lines in
            guard lines.contains("any Collection<Int> 的 map 結果型別：Array<Int>") else { return "找不到 map 結果型別這一行：要指定 Element 是 Int" }
            return lines.last == "成交金額合計：4501500"
                ? nil : "合計應該是 4501500：參數寫 some Collection<Int>，再用 reduce(0, +) 加總"
        }
    )
}
