// Coding01DataStructures 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：LRU 用的雙向鏈結串列（頭尾各一個假節點）、前 k 名用的 min-heap、計數器、把結果印成一行文字。
package tw.harry.labs.solution.coding01datastructures

/** 串列上的一個節點：存一筆資料（key 與 value），前後各指向一個節點 */
class C01Node(val key: String, var value: Int) {
    var prev: C01Node? = null
    var next: C01Node? = null
}

/** 雙向鏈結串列：最前面是「最近使用」，最後面是「最久沒用」。頭尾各有一個不存資料的假節點，插入與刪除都不用特別處理空串列。 */
class C01RecencyList {
    private val head = C01Node("", 0)
    private val tail = C01Node("", 0)

    /** 到目前為止改了幾次指標（看每個操作的成本用） */
    var pointerWrites = 0
        private set

    init {
        head.next = tail
        tail.prev = head
    }

    /** 把節點從原本的位置拿出來（改 2 個指標） */
    fun unlink(node: C01Node) {
        node.prev?.next = node.next
        node.next?.prev = node.prev
        pointerWrites += 2
    }

    /** 把節點放到最前面，代表剛用過（改 4 個指標） */
    fun insertAfterHead(node: C01Node) {
        node.next = head.next
        node.prev = head
        head.next?.prev = node
        head.next = node
        pointerWrites += 4
    }

    /** 拿出並回傳最久沒用的節點；串列是空的就回傳 null */
    fun removeLast(): C01Node? {
        val last = tail.prev
        if (last == null || last === head) return null
        unlink(last)
        return last
    }

    /** 串列上現在有幾個節點 */
    val count: Int get() = keys.size

    /** 由「最近」到「最久」的 key */
    val keys: List<String>
        get() {
            val result = ArrayList<String>()
            var node = head.next
            while (node != null && node !== tail) {
                result.add(node.key)
                node = node.next
            }
            return result
        }
}

/** 數「比較了幾次」或「碰了幾個元素」（演練用來看複雜度） */
class C01Counter {
    var count = 0
}

/** 排行榜上的一個幣種 */
data class C01Entry(val symbol: String, val volume: Int)

/** 最小堆積（min-heap）：最上面（[top]）永遠是「最小」的那一個。「誰比較小」由 [isLess] 決定。 */
class C01MinHeap<T>(private val isLess: (T, T) -> Boolean) {
    private val items = ArrayList<T>()

    val count: Int get() = items.size
    val top: T? get() = items.firstOrNull()

    fun push(element: T) {
        items.add(element)
        var child = items.size - 1
        while (child > 0) {
            val parent = (child - 1) / 2
            if (!isLess(items[child], items[parent])) break
            swap(child, parent)
            child = parent
        }
    }

    /** 把最上面的換成新的元素，再往下調整位置 */
    fun replaceTop(element: T) {
        if (items.isEmpty()) return
        items[0] = element
        siftDown(0)
    }

    /** 一個一個拿出最小的，回傳由小到大的結果（拿完之後 heap 是空的） */
    fun drainAscending(): List<T> {
        val result = ArrayList<T>()
        while (items.isNotEmpty()) {
            result.add(items[0])
            val last = items.removeAt(items.size - 1)
            if (items.isNotEmpty()) {
                items[0] = last
                siftDown(0)
            }
        }
        return result
    }

    private fun siftDown(start: Int) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            val right = left + 1
            var smallest = parent
            if (left < items.size && isLess(items[left], items[smallest])) smallest = left
            if (right < items.size && isLess(items[right], items[smallest])) smallest = right
            if (smallest == parent) return
            swap(parent, smallest)
            parent = smallest
        }
    }

    private fun swap(a: Int, b: Int) {
        val t = items[a]
        items[a] = items[b]
        items[b] = t
    }
}

/** 把 key 清單印成一行：[SOL, BTC, XRP] */
fun c01Text(keys: List<String>): String = keys.joinToString(", ", "[", "]")

/** 把區間清單印成一行：[1...2, 5...5, 9...10] */
fun c01RangesText(ranges: List<IntRange>): String =
    ranges.joinToString(", ", "[", "]") { "${it.first}...${it.last}" }
