// L05：選了訂單 1001，成交 1001 也被打勾了（用編號當 key，丟掉了型別資訊；改用 AnyHashable）
// 對應複習站：Swift03Generics 第 5 題「type erasure 是什麼？」
//
// 情境：交易紀錄畫面同時列出訂單（OrderID）和成交（TradeID）。兩種編號都是 Int 包起來的 struct，數字可能一樣。
//       使用者可以勾選要匯出的紀錄，勾選狀態存在同一個 Set 裡。
// Bug 版：Set 的元素是 Int，用 id.raw 存。訂單 1001 和成交 1001 的 raw 都是 1001，勾了一個，另一個也跟著被勾。
// 修正版：Set<AnyHashable>（type erasure 的標準型別）：把不同型別的 Hashable 包成同一種，比較時型別和值都要一樣。
import LabKit

/// 訂單編號與成交編號：同樣用 Int 包起來，但是不同的型別（兩個版本共用）
private struct OrderID: Hashable { let raw: Int }
private struct TradeID: Hashable { let raw: Int }

// MARK: - Bug 版
private enum L05Bug {
    static func run(_ log: LabLog) async {
        var selected = Set<Int>()                               // 只存編號的數字
        selected.insert(OrderID(raw: 1001).raw)                 // 使用者勾選訂單 1001
        log("勾選了訂單 1001")
        log("訂單 1001：\(selected.contains(OrderID(raw: 1001).raw) ? "已勾選" : "未勾選")")
        log("成交 1001：\(selected.contains(TradeID(raw: 1001).raw) ? "已勾選" : "未勾選")")
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func run(_ log: LabLog) async {
        // Set<any Hashable> 無法編譯（type 'any Hashable' cannot conform to 'Hashable'），
        // 標準函式庫提供的 type erasure 版本是 AnyHashable
        var selected = Set<AnyHashable>()
        selected.insert(OrderID(raw: 1001))                     // 使用者勾選訂單 1001
        log("勾選了訂單 1001")
        log("訂單 1001：\(selected.contains(OrderID(raw: 1001)) ? "已勾選" : "未勾選")")
        log("成交 1001：\(selected.contains(TradeID(raw: 1001)) ? "已勾選" : "未勾選")")
    }
}

// MARK: - 演練設定
extension Swift03Generics {
    static let l05 = Lab(
        id: "L05",
        title: "選了訂單 1001，成交 1001 也被打勾",
        question: "第 5 題：type erasure 是什麼？（比較少考，被問到能答就好）",
        goal: "訂單和成交的編號數字可能一樣，勾選狀態要放在同一個 Set 裡，但兩者必須分得出來。",
        steps: [
            "選「Bug 版」執行，只勾了訂單 1001，看成交 1001 是否也顯示已勾選。",
            "想一想：Set<Int> 只存了編號的數字，OrderID 和 TradeID 的型別資訊去哪了？",
            "選「修正版」執行，成交 1001 應該是未勾選。",
            "進階：把修正版的 Set<AnyHashable> 改成 Set<any Hashable>，看編譯錯誤訊息。",
        ],
        expectBug: "訂單 1001：已勾選；成交 1001：已勾選（不該勾選）。",
        expectFixed: "訂單 1001：已勾選；成交 1001：未勾選。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("訂單 1001：已勾選") else { return "勾選的訂單 1001 應該顯示已勾選" }
            return lines.contains("成交 1001：已勾選")
                ? nil : "Bug 還沒重現：成交 1001 應該（錯誤地）也被勾選，Set 只存編號的數字"
        },
        checkFixed: { lines in
            guard lines.contains("訂單 1001：已勾選") else { return "勾選的訂單 1001 應該顯示已勾選" }
            return lines.contains("成交 1001：未勾選")
                ? nil : "成交 1001 不該被勾選：Set 的元素要是 AnyHashable，保留 OrderID 和 TradeID 的型別"
        }
    )
}
