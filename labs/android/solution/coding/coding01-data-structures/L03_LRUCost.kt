// L03：快取容量變 10 倍，每次 get 的成本也變 10 倍（用 List 記使用順序，不是 O(1)）
// 對應複習站：Coding01DataStructures 第 3 題「LRU 快取怎麼做到 get、put 都很快？」（也是第 2 題「時間複雜度怎麼用白話講」的實測）
//
// 情境：圖示快取。容量 100 與容量 1000 各做 1000 次 get（每次都讀得到），數「每次 get 平均碰了幾個元素」，看容量變大時成本怎麼變。
// Bug 版：用 List 記使用順序。get 要先在 List 裡一個一個找到 key（O(n)），拿出來時後面的元素往前搬，再放到最前面時全部元素往後搬。
//        容量變 10 倍，每次 get 碰到的元素也變 10 倍。
// 修正版：Map 加雙向鏈結串列。Map 直接找到節點，串列上拿出來、放到最前面只改 6 個指標，容量再大成本都一樣。
package tw.harry.labs.solution.coding01datastructures

import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface L03Cache {
    fun get(key: String): Int?
    fun put(key: String, value: Int)

    /** 到目前為止碰過幾個元素（List：比對、搬移的元素；串列：改的指標） */
    val cost: Int
}

/** 演練流程（兩個版本共用）：把快取放滿，再做 1000 次 get，數平均成本 */
private fun averageCost(log: LabLog, capacity: Int, make: (Int) -> L03Cache): Double {
    val cache = make(capacity)
    for (i in 0 until capacity) cache.put("K$i", i)
    val before = cache.cost
    var hits = 0
    for (i in 0 until 1000) {
        if (cache.get("K${(i * 37) % capacity}") != null) hits++
    }
    val average = (cache.cost - before).toDouble() / 1000
    log("容量 $capacity：1000 次 get 命中 $hits 次，平均每次碰 ${average.toInt()} 個元素")
    return average
}

private fun scenario(log: LabLog, make: (Int) -> L03Cache) {
    val small = averageCost(log, 100, make)
    val large = averageCost(log, 1000, make)
    val ratio = if (small > 0) large / small else 0.0
    log("容量變 10 倍，每次 get 的成本變成 ${String.format(Locale.ROOT, "%.1f", ratio)} 倍")
}

// MARK: - Bug 版
private object L03Bug {
    class ListCache(private val capacity: Int) : L03Cache {
        private val values = HashMap<String, Int>()
        private val order = ArrayList<String>()           // 由「最近」到「最久」
        override var cost = 0
            private set

        /** 把 key 移到最前面 */
        private fun touch(key: String) {
            val index = maxOf(order.indexOf(key), 0)       // 從頭一個一個找
            cost += index + 1
            order.removeAt(index)                          // 後面的元素往前搬
            cost += order.size - index
            order.add(0, key)                              // 全部元素往後搬
            cost += order.size
        }

        override fun get(key: String): Int? {
            val value = values[key] ?: return null
            touch(key)
            return value
        }

        override fun put(key: String, value: Int) {
            if (values.containsKey(key)) {
                values[key] = value
                touch(key)
                return
            }
            values[key] = value
            order.add(0, key)
            if (order.size > capacity) values.remove(order.removeAt(order.size - 1))
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { ListCache(it) }
}

// MARK: - 修正版
private object L03Fixed {
    class LinkedCache(private val capacity: Int) : L03Cache {
        private val map = HashMap<String, C01Node>()
        private val list = C01RecencyList()

        override val cost: Int get() = list.pointerWrites

        override fun get(key: String): Int? {
            val node = map[key] ?: return null
            list.unlink(node)                              // 改 2 個指標
            list.insertAfterHead(node)                     // 改 4 個指標
            return node.value
        }

        override fun put(key: String, value: Int) {
            val existing = map[key]
            if (existing != null) {
                existing.value = value
                list.unlink(existing)
                list.insertAfterHead(existing)
                return
            }
            val node = C01Node(key, value)
            map[key] = node
            list.insertAfterHead(node)
            if (map.size > capacity) list.removeLast()?.let { map.remove(it.key) }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { LinkedCache(it) }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "容量變 10 倍，每次 get 也慢 10 倍",
    question = "第 3 題：LRU 快取怎麼做到 get、put 都很快？（第 2 題：時間複雜度怎麼用白話講）",
    goal = "用「資料變 10 倍，成本變幾倍」判斷複雜度：用 List 記順序是 O(n)，Map 加雙向鏈結串列是 O(1)。",
    steps = listOf(
        "選「Bug 版」執行，看容量 100 與 1000 各自「平均每次碰幾個元素」，以及最後那行成本變成幾倍。",
        "選「修正版」執行，比較兩個容量的數字。",
        "對照第 2 題的表格：O(1)、O(n) 在這裡各是什麼樣子？",
    ),
    expectBug = "容量 100 約碰 200 個元素，容量 1000 約碰 2000 個：「容量變 10 倍，每次 get 的成本變成 10.0 倍」。",
    expectFixed = "不管容量多大，每次都只改 6 個指標：「容量變 10 倍，每次 get 的成本變成 1.0 倍」。",
    file = "coding01-data-structures/L03_LRUCost.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("容量 1000：1000 次 get 命中 1000 次") } -> "get 還沒寫好：1000 次 get 應該全部命中"
            "容量變 10 倍，每次 get 的成本變成 10.0 倍" !in lines ->
                "Bug 還沒重現：要用 List 記順序，get 時從頭找 key、removeAt、add(0, key)，成本才會跟著容量成長"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("容量 1000：1000 次 get 命中 1000 次") } -> "get 還沒寫好：1000 次 get 應該全部命中"
            "容量變 10 倍，每次 get 的成本變成 1.0 倍" !in lines ->
                "get 要用 Map 找到節點，再 list.unlink、list.insertAfterHead：成本不能跟著容量成長"
            else -> null
        }
    },
)
