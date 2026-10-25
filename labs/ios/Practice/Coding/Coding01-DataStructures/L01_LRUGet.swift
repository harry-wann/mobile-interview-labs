// L01：剛讀過的圖示，下一次放新圖示時反而被淘汰（LRU 的 get 沒有更新使用順序）
// 對應複習站：Coding01DataStructures 第 3 題「LRU 快取怎麼做到 get、put 都很快？」
//
// 情境：圖示快取，容量 3。依序放進 BTC、ETH、XRP；使用者又點開 BTC（get），接著放進 SOL，快取滿了要淘汰一筆。
//
// 你要寫：
//   ① Bug 版（L01Bug.LRUCache.get）：只查字典 map，回傳 value，不更新串列的順序。跑起來會看到剛讀過的 BTC 被淘汰。
//   ② 修正版（L01Fixed.LRUCache.get）：找到節點後，先 list.unlink 再 list.insertAfterHead，讀取也算「最近使用」。
// 只改有 TODO 的地方（put 已經寫好）。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding01-DataStructures/L01_LRUGet.swift
import LabKit

private protocol Cache: AnyObject {
    func get(_ key: String) -> Int?
    func put(_ key: String, _ value: Int)
    /// 由「最近」到「最久」的 key
    var keys: [String] { get }
}

private func show(_ value: Int?) -> String {
    value.map(String.init) ?? "找不到"
}

/// 演練流程（兩個版本共用）：放三筆 → 讀 BTC → 再放 SOL
private func scenario(_ log: LabLog, cache: Cache) {
    cache.put("BTC", 1)
    cache.put("ETH", 2)
    cache.put("XRP", 3)
    log("放進 BTC、ETH、XRP（容量 3），順序（最近 → 最久）：\(c01Text(cache.keys))")
    log("get(BTC) → \(show(cache.get("BTC")))")
    cache.put("SOL", 4)
    log("放進 SOL 之後，順序：\(c01Text(cache.keys))")
    let evicted = ["BTC", "ETH", "XRP"].filter { !cache.keys.contains($0) }
    log("最後：快取裡有 \(c01Text(cache.keys))，被淘汰的是 \(evicted.joined(separator: "、"))")
}

// MARK: - Bug 版
private enum L01Bug {
    final class LRUCache: Cache {
        private let capacity: Int
        private var map: [String: C01Node] = [:]
        private let list = C01RecencyList()

        init(capacity: Int) {
            self.capacity = capacity
        }

        func get(_ key: String) -> Int? {
            // TODO ①：只用字典 map 找到 key 對應的節點，回傳它的 value（找不到回傳 nil）。不要動串列，也就是不更新使用順序
            return nil
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
                map[oldest.key] = nil                        // 淘汰串列最後面的那一筆
            }
        }

        var keys: [String] { list.keys }
    }

    static func run(_ log: LabLog) async {
        scenario(log, cache: LRUCache(capacity: 3))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class LRUCache: Cache {
        private let capacity: Int
        private var map: [String: C01Node] = [:]
        private let list = C01RecencyList()

        init(capacity: Int) {
            self.capacity = capacity
        }

        func get(_ key: String) -> Int? {
            // TODO ②-1：用字典 map 找到節點，找不到回傳 nil
            // TODO ②-2：讀取也算「最近使用」：先 list.unlink(node) 把它拿出來，再 list.insertAfterHead(node) 放到最前面
            // TODO ②-3：回傳 node.value
            return nil
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
                map[oldest.key] = nil                        // 淘汰串列最後面的那一筆
            }
        }

        var keys: [String] { list.keys }
    }

    static func run(_ log: LabLog) async {
        scenario(log, cache: LRUCache(capacity: 3))
    }
}

// MARK: - 演練設定
extension Coding01DataStructures {
    static let l01 = Lab(
        id: "L01",
        title: "剛讀過的資料，下一個就被淘汰",
        question: "第 3 題：LRU 快取怎麼做到 get、put 都很快？",
        goal: "LRU 的 get 也算「最近使用」。讀過的資料要移到串列最前面，快取滿了才不會淘汰剛用過的。先寫出 get 不更新順序的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Coding/Coding01-DataStructures/L01_LRUGet.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看「放進 SOL 之後」的順序，以及最後被淘汰的是誰（BTC 剛被 get 過）。",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，確認被淘汰的是 ETH，順序變成 SOL、BTC、XRP。",
            "想一想：get 要多做的事，成本是 O(1) 嗎？（unlink 與 insertAfterHead 各改幾個指標？）",
        ],
        expectBug: "get(BTC) 之後 BTC 還在最後面；放進 SOL 時被淘汰：「最後：快取裡有 [SOL, XRP, ETH]，被淘汰的是 BTC」。",
        expectFixed: "get(BTC) 把 BTC 移到最前面；被淘汰的是 ETH：「最後：快取裡有 [SOL, BTC, XRP]，被淘汰的是 ETH」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("get(BTC) → 1") { return "get 還沒寫：要用字典找到 BTC 的節點，回傳它的 value" }
            return lines.contains("最後：快取裡有 [SOL, XRP, ETH]，被淘汰的是 BTC")
                ? nil : "Bug 還沒重現：get 只能查字典、不能動串列，BTC 才會一直排在最久沒用的位置"
        },
        checkFixed: { lines in
            if !lines.contains("get(BTC) → 1") { return "get 還沒寫：要用字典找到 BTC 的節點，回傳它的 value" }
            return lines.contains("最後：快取裡有 [SOL, BTC, XRP]，被淘汰的是 ETH")
                ? nil : "get 找到節點後要 list.unlink(node) 再 list.insertAfterHead(node)，BTC 才會移到最前面"
        }
    )
}
