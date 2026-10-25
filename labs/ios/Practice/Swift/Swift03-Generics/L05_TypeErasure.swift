// L05：選了訂單 1001，成交 1001 也被打勾了（用編號當 key，丟掉了型別資訊；改用
// AnyHashable）
// 對應複習站：Swift03Generics 第 5 題「type erasure 是什麼？」
//
// 情境：交易紀錄畫面同時列出訂單（OrderID）和成交（TradeID）。兩種編號都是 Int
// 包起來的 struct，數字可能一樣。
//       使用者可以勾選要匯出的紀錄，勾選狀態存在同一個 Set 裡。
//
// 你要寫：
//   ① Bug 版：用 Set<Int> 存勾選狀態，只存編號的數字（id.raw），再分別查訂單
// 1001 和成交 1001 有沒有被勾選。
//   ② 修正版：用 Set<AnyHashable> 存勾選狀態，直接放 OrderID，再用 OrderID、
// TradeID 查有沒有被勾選。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift03-Generics/L05_TypeErasure.
// swift
import LabKit

/// 訂單編號與成交編號：同樣用 Int 包起來，但是不同的型別（兩個版本共用）
private struct OrderID: Hashable { let raw: Int }
private struct TradeID: Hashable { let raw: Int }

// MARK: - Bug 版
private enum L05Bug {
    static func run(_ log: LabLog) async {
        // 已寫好：只存編號的數字
        var selected = Set<Int>()
        // TODO ①-1：把訂單 1001 的 raw 放進 selected（使用者勾選訂單 1001）
        log("勾選了訂單 1001")
        // TODO ①-2：用 selected.contains 查訂單 1001（用 raw），log("訂單
        // 1001：已勾選" 或 "訂單 1001：未勾選")
        // TODO ①-3：同樣查成交 1001（用 TradeID 的 raw），log("成交 1001：
        // 已勾選" 或 "成交 1001：未勾選")
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func run(_ log: LabLog) async {
        // TODO ②-1：宣告 selected 為 Set<AnyHashable>（Set<any Hashable>
        // 無法編譯，可以試試看錯誤訊息）
        var selected = Set<Int>()
        // TODO ②-2：把 OrderID(raw: 1001) 本身（不是 raw）放進 selected
        log("勾選了訂單 1001")
        // TODO ②-3：用 selected.contains 查 OrderID(raw: 1001)，log("訂單
        // 1001：已勾選" 或 "訂單 1001：未勾選")
        // TODO ②-4：同樣查 TradeID(raw: 1001)，log("成交 1001：已勾選" 或
        // "成交 1001：未勾選")
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
            "寫 TODO ①，選「練習」「Bug 版」執行，只勾了訂單 1001，看成交 1001 是否也顯示已勾選，出現 ✓。",
            "想一想：Set<Int> 只存了編號的數字，OrderID 和 TradeID 的型別資訊去哪了？",
            "寫 TODO ②，選「修正版」執行，成交 1001 應該是未勾選。",
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
            guard lines.contains("訂單 1001：已勾選") else {
                return "勾選的訂單 1001 應該顯示已勾選"
            }
            return lines.contains("成交 1001：已勾選")
                ? nil : "Bug 還沒重現：成交 1001 應該（錯誤地）也被勾選，Set 只存編號的數字"
        },
        checkFixed: { lines in
            guard lines.contains("訂單 1001：已勾選") else {
                return "勾選的訂單 1001 應該顯示已勾選"
            }
            return lines.contains("成交 1001：未勾選")
                ? nil
                : "成交 1001 不該被勾選：Set 的元素要是 AnyHashable，保留 OrderID 和 TradeID 的型別"
        }
    )
}
