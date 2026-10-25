// L03：快取容量變 10 倍，每次 get 的成本也變 10 倍（用陣列記使用順序，不是
// O(1)）
// 對應複習站：Coding01DataStructures 第 3 題「LRU 快取怎麼做到 get、put
// 都很快？」（也是第 2 題「時間複雜度怎麼用白話講」的實測）
//
// 情境：圖示快取。容量 100 與容量 1000 各做 1000 次 get（每次都讀得到），
// 數「每次 get 平均碰了幾個元素」，看容量變大時成本怎麼變。
//
// 你要寫：
//   ① Bug 版（L03Bug.ArrayCache.touch）：用陣列 order 把 key 移到最前面：
// 從頭找 key、remove(at:)、insert(at: 0)，
//      每一步依註解把碰到的元素數加進 cost。跑起來會看到容量變 10 倍，成本也變
// 10 倍。
//   ② 修正版（L03Fixed.ListCache.get）：用字典找到節點，list.unlink 再 list.
// insertAfterHead。成本（改指標的次數）不隨容量變大。
// 只改有 TODO 的地方（put、命中計數、成本統計已經寫好）。寫完在 App 選「練習」
// 執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding01-DataStructures/L03_LRUCost.swift
import Foundation
import LabKit

private protocol Cache: AnyObject {
    func get(_ key: String) -> Int?
    func put(_ key: String, _ value: Int)
    /// 到目前為止碰過幾個元素（陣列：比對、搬移的元素；串列：改的指標）
    var cost: Int { get }
}

/// 演練流程（兩個版本共用）：把快取放滿，再做 1000 次 get，數平均成本
private func averageCost(
    _ log: LabLog, capacity: Int, make: (Int) -> Cache
) -> Double {
    let cache = make(capacity)
    for i in 0..<capacity { cache.put("K\(i)", i) }
    let before = cache.cost
    var hits = 0
    for i in 0..<1000 {
        if cache.get("K\((i * 37) % capacity)") != nil { hits += 1 }
    }
    let average = Double(cache.cost - before) / 1000
    log("容量 \(capacity)：1000 次 get 命中 \(hits) 次，平均每次碰 \(Int(average)) 個元素")
    return average
}

private func scenario(_ log: LabLog, make: (Int) -> Cache) {
    let small = averageCost(log, capacity: 100, make: make)
    let large = averageCost(log, capacity: 1000, make: make)
    let ratio = small > 0 ? large / small : 0
    log("容量變 10 倍，每次 get 的成本變成 \(String(format: "%.1f", ratio)) 倍")
}

// MARK: - Bug 版
private enum L03Bug {
    final class ArrayCache: Cache {
        private let capacity: Int
        private var values: [String: Int] = [:]
        // 由「最近」到「最久」
        private var order: [String] = []
        private(set) var cost = 0

        init(capacity: Int) {
            self.capacity = capacity
        }

        /// 把 key 移到最前面
        private func touch(_ key: String) {
            // TODO ①-1：用 order.firstIndex(of: key) 找到 key 的位置
            // index（找不到當 0）。一個一個找，碰了 index + 1 個元素：cost +=
            // index + 1
            // TODO ①-2：order.remove(at: index)。後面的元素往前搬：cost +=
            // order.count - index（移除之後的 order.count）
            // TODO ①-3：order.insert(key, at: 0)。全部元素往後搬：cost +=
            // order.count（插入之後的 order.count）
        }

        func get(_ key: String) -> Int? {
            guard let value = values[key] else { return nil }
            touch(key)
            return value
        }

        func put(_ key: String, _ value: Int) {
            if values[key] != nil {
                values[key] = value
                touch(key)
                return
            }
            values[key] = value
            order.insert(key, at: 0)
            if order.count > capacity { values[order.removeLast()] = nil }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { ArrayCache(capacity: $0) }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class ListCache: Cache {
        private let capacity: Int
        private var map: [String: C01Node] = [:]
        private let list = C01RecencyList()

        init(capacity: Int) {
            self.capacity = capacity
        }

        var cost: Int { list.pointerWrites }

        func get(_ key: String) -> Int? {
            guard let node = map[key] else { return nil }
            // TODO ②：讀取也算「最近使用」：list.unlink(node) 拿出來，再 list.
            // insertAfterHead(node) 放到最前面（共改 6 個指標，不隨容量變大）
            return node.value
        }

        func put(_ key: String, _ value: Int) {
            if let node = map[key] {
                node.value = value
                list.unlink(node)
                list.insertAfterHead(node)
                return
            }
            let node = C01Node(key: key, value: value)
            map[key] = node
            list.insertAfterHead(node)
            if map.count > capacity, let oldest = list.removeLast() {
                map[oldest.key] = nil
            }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { ListCache(capacity: $0) }
    }
}

// MARK: - 演練設定
extension Coding01DataStructures {
    static let l03 = Lab(
        id: "L03",
        title: "容量變 10 倍，每次 get 也慢 10 倍",
        question: "第 3 題：LRU 快取怎麼做到 get、put 都很快？（第 2 題：時間複雜度怎麼用白話講）",
        goal:
            "用「資料變 10 倍，成本變幾倍」判斷複雜度：用陣列記順序是 O(n)，字典加雙向鏈結串列是 O(1)。先寫出用陣列記順序的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Coding/Coding01-DataStructures/L03_LRUCost.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看容量 100 與 1000 各自「平均每次碰幾個元素」，以及最後那行成本變成幾倍。",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，比較兩個容量的數字。",
            "對照第 2 題的表格：O(1)、O(n) 在這裡各是什麼樣子？",
        ],
        expectBug:
            "容量 100 約碰 200 個元素，容量 1000 約碰 2000 個：「容量變 10 倍，每次 get 的成本變成 10.0 倍」。",
        expectFixed: "不管容量多大，每次都只改 6 個指標：「容量變 10 倍，每次 get 的成本變成 1.0 倍」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: {
                $0.hasPrefix("容量 1000：1000 次 get 命中 1000 次")
            }) {
                return "get 還沒寫好：1000 次 get 應該全部命中"
            }
            return lines.contains("容量變 10 倍，每次 get 的成本變成 10.0 倍")
                ? nil
                : "Bug 還沒重現：要用陣列記順序，get 時從頭找 key、remove(at:)、insert(at: 0)，成本才會跟著容量成長"
        },
        checkFixed: { lines in
            if !lines.contains(where: {
                $0.hasPrefix("容量 1000：1000 次 get 命中 1000 次")
            }) {
                return "get 還沒寫好：1000 次 get 應該全部命中"
            }
            return lines.contains("容量變 10 倍，每次 get 的成本變成 1.0 倍")
                ? nil
                : "get 要用字典找到節點，再 list.unlink、list.insertAfterHead：成本不能跟著容量成長"
        }
    )
}
