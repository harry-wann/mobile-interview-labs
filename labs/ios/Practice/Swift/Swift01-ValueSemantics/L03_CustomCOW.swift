// L03：自己寫的 struct 包了 class，快照被偷偷改掉（自己實作 COW）
// 對應複習站：Swift01ValueSemantics 第 3 題「COW：Array 是值型別，
// 為什麼複製很快？」
//
// 情境：訂單簿用 struct 包一個 class 存資料，想讓複製很便宜。畫面拿一份「快照」
// 顯示，後面的推播繼續更新 live。
//
// 你要寫：
//   ① Bug 版：set 直接修改 storage。跑起來會看到快照也被推播改掉。
//   ② 修正版：修改前用 isKnownUniquelyReferenced 檢查 storage
// 是不是只有自己在用，有別人在用就先複製一份。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift01-ValueSemantics/L03_CustomCOW.swift
import LabKit

// MARK: - Bug 版
private enum L03Bug {
    /// 存放「價格 → 數量」的 class（已寫好）
    final class Storage {
        var levels: [Int: Int] = [:]
    }

    struct OrderBook {
        private var storage = Storage()
        private(set) var copies = 0

        func qty(at price: Int) -> Int { storage.levels[price, default: 0] }

        mutating func set(_ qty: Int, at price: Int) {
            // TODO ①：把數量寫進 storage.levels[price]（直接改，不管 storage
            // 是不是被共用）
        }
    }

    /// 演練流程（不用改）
    static func run(_ log: LabLog) async {
        var live = OrderBook()
        live.set(5, at: 100)
        log("推播：100 元的數量 = 5")

        let snapshot = live  // 給畫面顯示的快照
        log("存下快照，快照上 100 元的數量：\(snapshot.qty(at: 100))")

        // 後面的推播只更新 live
        live.set(9, at: 100)
        live.set(7, at: 101)
        log("推播：100 元 = 9、101 元 = 7（live 複製了 \(live.copies) 次）")
        log("live 上 100 元的數量：\(live.qty(at: 100))")
        log("快照上 100 元的數量：\(snapshot.qty(at: 100))")
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class Storage {
        var levels: [Int: Int]
        init(levels: [Int: Int] = [:]) { self.levels = levels }
    }

    struct OrderBook {
        private var storage = Storage()
        /// 記錄真的複製了幾次，方便觀察
        private(set) var copies = 0

        func qty(at price: Int) -> Int { storage.levels[price, default: 0] }

        /// 每個會修改資料的方法，都要先呼叫這個
        private mutating func makeUnique() {
            // TODO ②-1：如果 !isKnownUniquelyReferenced(&storage)，就用目前的
            // levels 建立新的 Storage 指定給 storage，並把 copies 加 1
        }

        mutating func set(_ qty: Int, at price: Int) {
            // TODO ②-2：先呼叫 makeUnique()，再把數量寫進 storage.levels[price]
        }
    }

    /// 演練流程（不用改）
    static func run(_ log: LabLog) async {
        var live = OrderBook()
        live.set(5, at: 100)
        log("推播：100 元的數量 = 5")

        let snapshot = live
        log("存下快照，快照上 100 元的數量：\(snapshot.qty(at: 100))")

        live.set(9, at: 100)
        live.set(7, at: 101)
        log("推播：100 元 = 9、101 元 = 7（live 複製了 \(live.copies) 次）")
        log("live 上 100 元的數量：\(live.qty(at: 100))")
        log("快照上 100 元的數量：\(snapshot.qty(at: 100))")
    }
}

// MARK: - 演練設定
extension Swift01ValueSemantics {
    static let l03 = Lab(
        id: "L03",
        title: "快照被推播偷偷改掉",
        question: "第 3 題：COW：Array 是值型別，為什麼複製很快？",
        goal:
            "OrderBook 是 struct，但裡面包了 class。存一份快照後再更新 live，快照應該不變，而且只在必要時複製一次。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行「快照上 100 元的數量」。",
            "寫 TODO ②，選「修正版」執行，注意「live 複製了幾次」：只有第一次修改時複製。",
        ],
        expectBug: "快照上 100 元的數量變成 9：外表是 struct，行為卻像 class。",
        expectFixed: "快照維持 5；live 只在第一次修改時複製 1 次，之後不再複製。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("live 上 100 元的數量：9") {
                return "live 沒有更新成 9：set 要把數量寫進 storage"
            }
            if !lines.contains("快照上 100 元的數量：9") {
                return "快照沒有被改掉：Bug 還沒重現（set 要直接改共用的 storage）"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("live 上 100 元的數量：9") {
                return "live 沒有更新成 9：set 要把數量寫進 storage"
            }
            if !lines.contains("快照上 100 元的數量：5") {
                return "快照被改掉了：修改前要先 makeUnique()"
            }
            if !lines.contains(where: { $0.hasSuffix("（live 複製了 1 次）") }) {
                return "應該只複製 1 次：只有 storage 被共用時才複製"
            }
            return nil
        }
    )
}
