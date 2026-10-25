// L01：價格不符最小跳動單位的委託，居然通過了下單前檢查（用型別轉換判斷規則，漏了一種）
// 對應複習站：Swift03Generics 第 1 題「some、any 和泛型差在哪？什麼時候用哪個？」
//
// 情境：下單前要檢查三條規則：餘額夠不夠、數量有沒有低於最小單位、價格是不是 1000 円的倍數（tick size，價格最小跳動單位）。
//       三條規則是不同的型別，要放在同一個陣列裡一條一條跑。
// Bug 版：陣列用 [Any]，跑的時候用 as? 轉型一個一個判斷。後來新增的 TickSizeRule 忘了加進判斷，編譯器不會提醒，這條規則被默默略過。
// 修正版：定義 OrderRule 協定，陣列用 [any OrderRule]，每條規則自己檢查自己。同一個陣列放不同型別，而且新增規則不會漏跑。
import LabKit

/// 委託單（兩個版本共用）
private struct Order {
    let price: Int            // 價格（円）
    let quantity: Double      // 數量（BTC）
}

/// 帳戶餘額（円）（兩個版本共用）
private let accountBalance = 100_000_000

// MARK: - Bug 版
private enum L01Bug {
    struct BalanceRule { let balance: Int }
    struct MinQuantityRule { let minimum: Double }
    struct TickSizeRule { let tick: Int }

    /// 規則放在 [Any] 裡，用 as? 轉型一個一個判斷；回傳 nil 代表通過
    static func validate(_ order: Order, rules: [Any]) -> String? {
        for rule in rules {
            if let r = rule as? BalanceRule {
                if Double(order.price) * order.quantity > Double(r.balance) { return "餘額不足" }
            } else if let r = rule as? MinQuantityRule {
                if order.quantity < r.minimum { return "數量低於最小單位" }
            }
            // 後來新增的 TickSizeRule 沒有加在這裡：沒有編譯錯誤，也沒有警告，只是被默默略過
        }
        return nil
    }

    static func run(_ log: LabLog) async {
        let rules: [Any] = [BalanceRule(balance: accountBalance), MinQuantityRule(minimum: 0.0001), TickSizeRule(tick: 1_000)]
        log("委託 A（價格 15000000、數量 0.5）：\(validate(Order(price: 15_000_000, quantity: 0.5), rules: rules) ?? "通過")")
        log("委託 B（價格 15000123、數量 0.5）：\(validate(Order(price: 15_000_123, quantity: 0.5), rules: rules) ?? "通過")")
        log("委託 C（價格 15000000、數量 0.00001）：\(validate(Order(price: 15_000_000, quantity: 0.00001), rules: rules) ?? "通過")")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    /// 每條規則自己檢查自己；回傳 nil 代表通過
    protocol OrderRule {
        func check(_ order: Order) -> String?
    }

    struct BalanceRule: OrderRule {
        let balance: Int
        func check(_ order: Order) -> String? {
            Double(order.price) * order.quantity > Double(balance) ? "餘額不足" : nil
        }
    }

    struct MinQuantityRule: OrderRule {
        let minimum: Double
        func check(_ order: Order) -> String? {
            order.quantity < minimum ? "數量低於最小單位" : nil
        }
    }

    struct TickSizeRule: OrderRule {
        let tick: Int
        func check(_ order: Order) -> String? {
            order.price % tick != 0 ? "價格要是 \(tick) 円的倍數" : nil
        }
    }

    // 為什麼不用 some？some OrderRule 只代表「某一個」具體型別，一個函式不能回傳兩種：
    //   func makeRule(strict: Bool) -> some OrderRule { strict ? TickSizeRule(tick: 1_000) : BalanceRule(balance: 0) }
    //   → 編譯錯誤：function declares an opaque return type, but the return statements in its body do not have matching underlying types
    // 同一個陣列要放不同型別的規則，用 any。

    /// 陣列用 [any OrderRule]：放不同型別，新增規則只要多遵守協定，不用改這個函式
    static func validate(_ order: Order, rules: [any OrderRule]) -> String? {
        for rule in rules {
            if let reason = rule.check(order) { return reason }
        }
        return nil
    }

    static func run(_ log: LabLog) async {
        let rules: [any OrderRule] = [BalanceRule(balance: accountBalance), MinQuantityRule(minimum: 0.0001), TickSizeRule(tick: 1_000)]
        log("委託 A（價格 15000000、數量 0.5）：\(validate(Order(price: 15_000_000, quantity: 0.5), rules: rules) ?? "通過")")
        log("委託 B（價格 15000123、數量 0.5）：\(validate(Order(price: 15_000_123, quantity: 0.5), rules: rules) ?? "通過")")
        log("委託 C（價格 15000000、數量 0.00001）：\(validate(Order(price: 15_000_000, quantity: 0.00001), rules: rules) ?? "通過")")
    }
}

// MARK: - 演練設定
extension Swift03Generics {
    static let l01 = Lab(
        id: "L01",
        title: "價格不符 tick size 的委託居然通過",
        question: "第 1 題：some、any 和泛型差在哪？什麼時候用哪個？",
        goal: "三條下單前規則是不同的型別，要放在同一個陣列裡跑。價格 15000123 不是 1000 円的倍數，必須被擋下來。",
        steps: [
            "選「Bug 版」執行，看委託 B：價格 15000123 不是 1000 的倍數，卻顯示「通過」。",
            "找原因：validate 用 as? 轉型判斷規則，少了哪一種？編譯器有提醒嗎？",
            "選「修正版」執行，委託 B 應該被「價格要是 1000 円的倍數」擋下來。",
            "進階：試著把 validate 改成泛型 [R: OrderRule]，或寫 func makeRule(strict: Bool) -> some OrderRule 在兩個分支回傳不同規則，看編譯錯誤訊息。",
        ],
        expectBug: "委託 A 通過、委託 B「通過」（不該通過）、委託 C 數量低於最小單位。",
        expectFixed: "委託 A 通過、委託 B：價格要是 1000 円的倍數、委託 C：數量低於最小單位。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("委託 C（價格 15000000、數量 0.00001）：數量低於最小單位") else { return "委託 C 數量太小，要被 MinQuantityRule 擋下來：validate 要處理 MinQuantityRule" }
            return lines.contains("委託 B（價格 15000123、數量 0.5）：通過")
                ? nil : "Bug 還沒重現：委託 B 應該（錯誤地）通過，validate 不要處理 TickSizeRule"
        },
        checkFixed: { lines in
            guard lines.contains("委託 A（價格 15000000、數量 0.5）：通過") else { return "委託 A 是合法的委託，應該通過" }
            guard lines.contains("委託 C（價格 15000000、數量 0.00001）：數量低於最小單位") else { return "委託 C 數量太小，要被 MinQuantityRule 擋下來" }
            guard lines.contains("委託 B（價格 15000123、數量 0.5）：價格要是 1000 円的倍數") else { return "委託 B 價格不是 1000 的倍數，要被 TickSizeRule 擋下來" }
            return nil
        }
    )
}
