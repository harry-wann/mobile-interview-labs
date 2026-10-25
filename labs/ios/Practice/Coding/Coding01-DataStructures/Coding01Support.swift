// Coding01DataStructures 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：LRU 用的雙向鏈結串列（頭尾各一個假節點）、前 k 名用的 min-heap、計數器、把結果印成一行文字。
import Foundation

/// 串列上的一個節點：存一筆資料（key 與 value），前後各指向一個節點
final class C01Node {
    let key: String
    var value: Int
    weak var prev: C01Node?
    var next: C01Node?

    init(key: String, value: Int) {
        self.key = key
        self.value = value
    }
}

/// 雙向鏈結串列：最前面是「最近使用」，最後面是「最久沒用」。頭尾各有一個不存資料的假節點，插入與刪除都不用特別處理空串列。
final class C01RecencyList {
    private let head = C01Node(key: "", value: 0)
    private let tail = C01Node(key: "", value: 0)
    /// 到目前為止改了幾次指標（看每個操作的成本用）
    private(set) var pointerWrites = 0

    init() {
        head.next = tail
        tail.prev = head
    }

    /// 把節點從原本的位置拿出來（改 2 個指標）
    func unlink(_ node: C01Node) {
        node.prev?.next = node.next
        node.next?.prev = node.prev
        pointerWrites += 2
    }

    /// 把節點放到最前面，代表剛用過（改 4 個指標）
    func insertAfterHead(_ node: C01Node) {
        node.next = head.next
        node.prev = head
        head.next?.prev = node
        head.next = node
        pointerWrites += 4
    }

    /// 拿出並回傳最久沒用的節點；串列是空的就回傳 nil
    func removeLast() -> C01Node? {
        guard let last = tail.prev, last !== head else { return nil }
        unlink(last)
        return last
    }

    /// 串列上現在有幾個節點
    var count: Int {
        var n = 0
        var node = head.next
        while let current = node, current !== tail {
            n += 1
            node = current.next
        }
        return n
    }

    /// 由「最近」到「最久」的 key
    var keys: [String] {
        var result: [String] = []
        var node = head.next
        while let current = node, current !== tail {
            result.append(current.key)
            node = current.next
        }
        return result
    }
}

/// 數「比較了幾次」或「碰了幾個元素」（演練用來看複雜度）
final class C01Counter {
    var count = 0
}

/// 排行榜上的一個幣種
struct C01Entry: Equatable {
    let symbol: String
    let volume: Int
}

/// 最小堆積（min-heap）：最上面（`top`）永遠是「最小」的那一個。「誰比較小」由 `isLess` 決定。
struct C01MinHeap<Element> {
    private var items: [Element] = []
    private let isLess: (Element, Element) -> Bool

    init(isLess: @escaping (Element, Element) -> Bool) {
        self.isLess = isLess
    }

    var count: Int { items.count }
    var top: Element? { items.first }

    mutating func push(_ element: Element) {
        items.append(element)
        var child = items.count - 1
        while child > 0 {
            let parent = (child - 1) / 2
            guard isLess(items[child], items[parent]) else { break }
            items.swapAt(child, parent)
            child = parent
        }
    }

    /// 把最上面的換成新的元素，再往下調整位置
    mutating func replaceTop(with element: Element) {
        guard !items.isEmpty else { return }
        items[0] = element
        siftDown(from: 0)
    }

    /// 一個一個拿出最小的，回傳由小到大的結果（拿完之後 heap 是空的）
    mutating func drainAscending() -> [Element] {
        var result: [Element] = []
        while let first = items.first {
            result.append(first)
            let last = items.removeLast()
            if !items.isEmpty {
                items[0] = last
                siftDown(from: 0)
            }
        }
        return result
    }

    private mutating func siftDown(from start: Int) {
        var parent = start
        while true {
            let left = parent * 2 + 1
            let right = left + 1
            var smallest = parent
            if left < items.count, isLess(items[left], items[smallest]) { smallest = left }
            if right < items.count, isLess(items[right], items[smallest]) { smallest = right }
            if smallest == parent { return }
            items.swapAt(parent, smallest)
            parent = smallest
        }
    }
}

/// 把 key 清單印成一行：[SOL, BTC, XRP]
func c01Text(_ keys: [String]) -> String {
    "[" + keys.joined(separator: ", ") + "]"
}

/// 把區間清單印成一行：[1...2, 5...5, 9...10]
func c01Text(_ ranges: [ClosedRange<Int>]) -> String {
    "[" + ranges.map { "\($0.lowerBound)...\($0.upperBound)" }.joined(separator: ", ") + "]"
}
