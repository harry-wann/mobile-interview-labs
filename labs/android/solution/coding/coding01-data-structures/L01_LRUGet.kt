// L01：剛讀過的圖示，下一次放新圖示時反而被淘汰（LRU 的 get 沒有更新使用順序）
// 對應複習站：Coding01DataStructures 第 3 題「LRU 快取怎麼做到 get、put 都很快？」
//
// 情境：圖示快取，容量 3。依序放進 BTC、ETH、XRP；使用者又點開 BTC（get），接著放進 SOL，快取滿了要淘汰一筆。
// Bug 版：get 只查 Map 就回傳，沒有把節點移到串列最前面。BTC 剛被讀過，在串列裡卻還是排在最久沒用的位置，被淘汰的就是它。
// 修正版：get 找到節點後，先從串列拿出來，再放到最前面（代表剛用過）。淘汰的才是真正最久沒用的 ETH。
package tw.harry.labs.solution.coding01datastructures

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface L01Cache {
    fun get(key: String): Int?
    fun put(key: String, value: Int)

    /** 由「最近」到「最久」的 key */
    val keys: List<String>
}

private fun show(value: Int?): String = value?.toString() ?: "找不到"

/** 演練流程（兩個版本共用）：放三筆 → 讀 BTC → 再放 SOL */
private fun scenario(log: LabLog, cache: L01Cache) {
    cache.put("BTC", 1)
    cache.put("ETH", 2)
    cache.put("XRP", 3)
    log("放進 BTC、ETH、XRP（容量 3），順序（最近 → 最久）：${c01Text(cache.keys)}")
    log("get(BTC) → ${show(cache.get("BTC"))}")
    cache.put("SOL", 4)
    log("放進 SOL 之後，順序：${c01Text(cache.keys)}")
    val evicted = listOf("BTC", "ETH", "XRP").filter { it !in cache.keys }
    log("最後：快取裡有 ${c01Text(cache.keys)}，被淘汰的是 ${evicted.joinToString("、")}")
}

// MARK: - Bug 版
private object L01Bug {
    class LRUCache(private val capacity: Int) : L01Cache {
        private val map = HashMap<String, C01Node>()
        private val list = C01RecencyList()

        override fun get(key: String): Int? {
            return map[key]?.value                           // 只查 Map，沒有更新使用順序
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
            if (map.size > capacity) {
                list.removeLast()?.let { map.remove(it.key) }    // 淘汰串列最後面的那一筆
            }
        }

        override val keys: List<String> get() = list.keys
    }

    suspend fun run(log: LabLog) = scenario(log, LRUCache(3))
}

// MARK: - 修正版
private object L01Fixed {
    class LRUCache(private val capacity: Int) : L01Cache {
        private val map = HashMap<String, C01Node>()
        private val list = C01RecencyList()

        override fun get(key: String): Int? {
            val node = map[key] ?: return null
            list.unlink(node)                                // 讀取也算「最近使用」：拿出來
            list.insertAfterHead(node)                       // 再放到最前面
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
            if (map.size > capacity) {
                list.removeLast()?.let { map.remove(it.key) }    // 淘汰串列最後面的那一筆
            }
        }

        override val keys: List<String> get() = list.keys
    }

    suspend fun run(log: LabLog) = scenario(log, LRUCache(3))
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "剛讀過的資料，下一個就被淘汰",
    question = "第 3 題：LRU 快取怎麼做到 get、put 都很快？",
    goal = "LRU 的 get 也算「最近使用」。讀過的資料要移到串列最前面，快取滿了才不會淘汰剛用過的。",
    steps = listOf(
        "選「Bug 版」執行，看「放進 SOL 之後」的順序，以及最後被淘汰的是誰（BTC 剛被 get 過）。",
        "選「修正版」執行，確認被淘汰的是 ETH，順序變成 SOL、BTC、XRP。",
        "想一想：get 要多做的事，成本是 O(1) 嗎？（unlink 與 insertAfterHead 各改幾個指標？）",
    ),
    expectBug = "get(BTC) 之後 BTC 還在最後面；放進 SOL 時被淘汰：「最後：快取裡有 [SOL, XRP, ETH]，被淘汰的是 BTC」。",
    expectFixed = "get(BTC) 把 BTC 移到最前面；被淘汰的是 ETH：「最後：快取裡有 [SOL, BTC, XRP]，被淘汰的是 ETH」。",
    file = "coding01-data-structures/L01_LRUGet.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "get(BTC) → 1" !in lines -> "get 還沒寫：要用 Map 找到 BTC 的節點，回傳它的 value"
            "最後：快取裡有 [SOL, XRP, ETH]，被淘汰的是 BTC" !in lines ->
                "Bug 還沒重現：get 只能查 Map、不能動串列，BTC 才會一直排在最久沒用的位置"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "get(BTC) → 1" !in lines -> "get 還沒寫：要用 Map 找到 BTC 的節點，回傳它的 value"
            "最後：快取裡有 [SOL, BTC, XRP]，被淘汰的是 ETH" !in lines ->
                "get 找到節點後要 list.unlink(node) 再 list.insertAfterHead(node)，BTC 才會移到最前面"
            else -> null
        }
    },
)
