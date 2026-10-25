// L02：剛更新過價格的資料讀不到，容量 2 的快取卻存了 3 筆（LRU 的 put 遇到已經存在的 key）
// 對應複習站：Coding01DataStructures 第 3 題「LRU 快取怎麼做到 get、put 都很快？」
//
// 情境：價格快取，容量 2。放進 BTC、ETH，BTC 的價格有更新，再放進 XRP。
// Bug 版：put 不管 key 存不存在，一律建立新節點。BTC 的舊節點還留在串列上，Map 卻指向新節點；
//        放進 XRP 超過容量時，淘汰了串列最後面那個舊的 BTC 節點，同時把 Map 裡「BTC」這個 key 也刪了，剛更新的 BTC 就讀不到。
// 修正版：key 已經存在就更新 value、移到最前面，不建立新節點，串列和 Map 的筆數永遠一樣。
package tw.harry.labs.solution.coding01datastructures

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface L02Cache {
    fun get(key: String): Int?
    fun put(key: String, value: Int)

    /** 串列上由「最近」到「最久」的 key */
    val keys: List<String>
}

private fun show(value: Int?): String = value?.toString() ?: "找不到"

/** 演練流程（兩個版本共用）：放 BTC、ETH → 更新 BTC → 放 XRP */
private fun scenario(log: LabLog, cache: L02Cache, capacity: Int) {
    cache.put("BTC", 1)
    cache.put("ETH", 2)
    cache.put("BTC", 3)
    log("放進 BTC=1、ETH=2，再把 BTC 更新成 3")
    cache.put("XRP", 4)
    log("放進 XRP 之後，串列由最近到最久：${c01Text(cache.keys)}")
    log("串列上有 ${cache.keys.size} 個節點（容量 $capacity）")
    log("get(BTC) → ${show(cache.get("BTC"))}")
}

// MARK: - Bug 版
private object L02Bug {
    class LRUCache(private val capacity: Int) : L02Cache {
        private val map = HashMap<String, C01Node>()
        private val list = C01RecencyList()

        override fun get(key: String): Int? {
            val node = map[key] ?: return null
            list.unlink(node)
            list.insertAfterHead(node)
            return node.value
        }

        override fun put(key: String, value: Int) {
            val node = C01Node(key, value)                  // 不管 key 存不存在，一律建立新節點
            map[key] = node
            list.insertAfterHead(node)
            if (map.size > capacity) {
                list.removeLast()?.let { map.remove(it.key) }
            }
        }

        override val keys: List<String> get() = list.keys
    }

    suspend fun run(log: LabLog) = scenario(log, LRUCache(2), 2)
}

// MARK: - 修正版
private object L02Fixed {
    class LRUCache(private val capacity: Int) : L02Cache {
        private val map = HashMap<String, C01Node>()
        private val list = C01RecencyList()

        override fun get(key: String): Int? {
            val node = map[key] ?: return null
            list.unlink(node)
            list.insertAfterHead(node)
            return node.value
        }

        override fun put(key: String, value: Int) {
            val existing = map[key]
            if (existing != null) {                         // key 已經存在：更新值、移到最前面，數量不變
                existing.value = value
                list.unlink(existing)
                list.insertAfterHead(existing)
                return
            }
            val node = C01Node(key, value)
            map[key] = node
            list.insertAfterHead(node)
            if (map.size > capacity) {
                list.removeLast()?.let { map.remove(it.key) }
            }
        }

        override val keys: List<String> get() = list.keys
    }

    suspend fun run(log: LabLog) = scenario(log, LRUCache(2), 2)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "剛更新的價格讀不到，快取還超過容量",
    question = "第 3 題：LRU 快取怎麼做到 get、put 都很快？",
    goal = "put 遇到已經存在的 key，要更新值、移到最前面，不能當成新的一筆加進去。",
    steps = listOf(
        "選「Bug 版」執行，看串列上有幾個節點（容量只有 2），以及 get(BTC) 讀得到嗎？",
        "選「修正版」執行，確認串列只有 2 個節點，get(BTC) 是更新後的 3。",
        "想一想：Bug 版淘汰舊 BTC 節點時，為什麼連 Map 裡「BTC」這個 key 也被刪掉？",
    ),
    expectBug = "串列上有 3 個節點（容量 2），順序 [XRP, BTC, ETH]；剛更新成 3 的 BTC 讀不到：「get(BTC) → 找不到」。",
    expectFixed = "串列上只有 2 個節點 [XRP, BTC]，「get(BTC) → 3」。",
    file = "coding01-data-structures/L02_LRUPut.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("串列上有") } -> "演練沒有跑完：要用 scenario(log, cache, capacity) 執行"
            "串列上有 3 個節點（容量 2）" !in lines -> "Bug 還沒重現：put 要一律建立新節點，舊的 BTC 節點才會留在串列上"
            "get(BTC) → 找不到" !in lines -> "Bug 還沒重現：淘汰舊 BTC 節點時，也會把 Map 裡的 BTC 刪掉，剛更新的 BTC 就讀不到"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("串列上有") } -> "演練沒有跑完：要用 scenario(log, cache, capacity) 執行"
            "串列上有 2 個節點（容量 2）" !in lines -> "key 已經存在時不能建立新節點：更新 value、unlink 再 insertAfterHead 就好"
            "get(BTC) → 3" !in lines -> "更新過的 BTC 應該讀得到 3：put 已存在的 key 要更新 node.value"
            else -> null
        }
    },
)
