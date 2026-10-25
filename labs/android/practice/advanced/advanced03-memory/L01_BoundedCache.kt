// L01：頭像一路滑下去，快取越來越大，收到 onTrimMemory 也不放（沒有上限的 HashMap 快取，對照 LruCache）
// 對應複習站：Advanced03Memory 第 1 題「iOS 和 Android 是怎麼因為記憶體不足而殺掉 App 的？收到警告時該做什麼？」
//
// 情境：行情與社群頁會顯示很多幣種與用戶的頭像。每張解碼後的頭像約 100 KB，使用者一路往下滑，總共經過 200 張（約 20 MB）。
//        接著系統呼叫 ComponentCallbacks2.onTrimMemory(TRIM_MEMORY_UI_HIDDEN)：畫面已經看不到了，要釋放畫面相關的資源。
//        Java heap 超過 App 的上限會 OutOfMemoryError；系統整體吃緊時 LMK 會殺背景行程，都不會有一般的 crash log，
//        所以要平常就讓用量有上限，收到通知時馬上放掉「可以重建」的東西（圖片快取）。
//
// 你要寫：
//   ① Bug 版 MapCache：用 HashMap 存，沒有上限，也不處理 onTrimMemory。
//   ② 修正版 BoundedCache：LruCache（maxSize 以 KB 為單位，設 5 * 1024，sizeOf 回傳 value.size / 1024）；
//      onTrimMemory 的 level 大於等於 TRIM_MEMORY_UI_HIDDEN 時 evictAll()。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced03-memory/L01_BoundedCache.kt
package tw.harry.labs.practice.advanced03memory

import android.content.ComponentCallbacks2
import androidx.collection.LruCache
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val IMAGE_COUNT = 200
private const val IMAGE_BYTES = 100 * 1024

/** 頭像快取的共同介面（兩個版本共用）；onTrimMemory 就是 ComponentCallbacks2 的那個函式 */
private interface AvatarCache {
    fun put(key: String, bytes: ByteArray)
    fun contains(key: String): Boolean
    fun onTrimMemory(level: Int)
}

/** 演練流程（兩個版本共用）：滑過 200 張頭像 → 看快取留了幾張 → 系統通知 UI 看不到了 → 再看一次 */
private fun scenario(log: LabLog, cache: AvatarCache) {
    val keys = List(IMAGE_COUNT) { "avatar-$it" }
    for (key in keys) cache.put(key, ByteArray(IMAGE_BYTES))              // 解碼後的頭像，約 100 KB
    val before = keys.count { cache.contains(it) }
    log("滑過 $IMAGE_COUNT 張頭像，快取裡還留著 $before 張（約 ${before * IMAGE_BYTES / 1_048_576} MB）")
    cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    val after = keys.count { cache.contains(it) }
    log("收到 onTrimMemory 之後，快取裡還有 $after 張")
}

// MARK: - Bug 版
private object L01Bug {
    class MapCache : AvatarCache {
        private val map = HashMap<String, ByteArray>()                    // 沒有上限，也不會自己清

        override fun put(key: String, bytes: ByteArray) {
            // TODO ①-1：map[key] = bytes
        }

        override fun contains(key: String): Boolean {
            // TODO ①-2：key 在 map 裡就是 true
            return false
        }

        override fun onTrimMemory(level: Int) {
            // 沒有處理
        }
    }

    fun run(log: LabLog) = scenario(log, MapCache())
}

// MARK: - 修正版
private object L01Fixed {
    class BoundedCache : AvatarCache {
        // TODO ②-1：maxSize 以 KB 為單位，設 5 * 1024（最多約 5 MB）；override sizeOf，回傳 value.size / 1024（這一筆占幾 KB）
        private val cache = object : LruCache<String, ByteArray>(Int.MAX_VALUE) {
            override fun sizeOf(key: String, value: ByteArray): Int = 0
        }

        override fun put(key: String, bytes: ByteArray) {
            // TODO ②-2：cache.put(key, bytes)
        }

        override fun contains(key: String) = cache.get(key) != null

        override fun onTrimMemory(level: Int) {
            // TODO ②-3：level 大於等於 ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN 時，呼叫 cache.evictAll()：可以重建的全放掉
        }
    }

    fun run(log: LabLog) = scenario(log, BoundedCache())
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "頭像一路滑下去，快取越來越大，收到通知也不放",
    question = "第 1 題：iOS 和 Android 是怎麼因為記憶體不足而殺掉 App 的？收到警告時該做什麼？",
    goal = "圖片快取要有大小上限，收到 onTrimMemory 時立刻清掉可以重建的東西。先寫出沒有上限的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced03-memory/L01_BoundedCache.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看兩行 log：滑完 200 張之後快取留了幾張、收到 onTrimMemory 之後還有幾張。",
        "寫 TODO ②，選「修正版」執行：快取最多只留約 5 MB（51 張左右），收到 onTrimMemory 之後是 0 張。",
        "進階：真的 App 在 Application 或 Activity 實作 ComponentCallbacks2.onTrimMemory；用 `adb shell am send-trim-memory <package> RUNNING_CRITICAL` 可以對跑著的 App 送出這個通知。要看 App 為什麼被殺，用 `ApplicationExitInfo`（Android 11 起，REASON_LOW_MEMORY）與 Android vitals。",
        "想一想：為什麼不能只靠 onTrimMemory？（LMK 殺背景行程時常常沒有任何通知，見這個單元的第 1 題）",
    ),
    expectBug = "滑過 200 張頭像，快取裡還留著 200 張（約 19 MB）；收到 onTrimMemory 之後，快取裡還有 200 張。",
    expectFixed = "滑過 200 張頭像，快取裡還留著 51 張（約 4 MB）左右；收到 onTrimMemory 之後，快取裡還有 0 張。",
    file = "advanced03-memory/L01_BoundedCache.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val before = a03Number(lines, "滑過 200 張頭像，快取裡還留著 ")
        val after = a03Number(lines, "收到 onTrimMemory 之後，快取裡還有 ")
        when {
            before == null || after == null -> "演練沒有跑完"
            before < 200 -> "快取只留了 ${before.toInt()} 張：Bug 版用沒有上限的 HashMap，200 張都要留著"
            after < 200 -> "onTrimMemory 之後只剩 ${after.toInt()} 張：Bug 版不處理通知，快取要原封不動"
            else -> null
        }
    },
    checkFixed = { lines ->
        val before = a03Number(lines, "滑過 200 張頭像，快取裡還留著 ")
        val after = a03Number(lines, "收到 onTrimMemory 之後，快取裡還有 ")
        when {
            before == null || after == null -> "演練沒有跑完"
            before == 0.0 -> "快取裡一張都沒有：put 要呼叫 cache.put(key, bytes)"
            before > 60 -> "快取留了 ${before.toInt()} 張，沒有上限：LruCache 的 maxSize 設 5 * 1024（KB），sizeOf 回傳 value.size / 1024"
            after != 0.0 -> "收到 onTrimMemory 之後還有 ${after.toInt()} 張：level 大於等於 TRIM_MEMORY_UI_HIDDEN 時要 cache.evictAll()"
            else -> null
        }
    },
)
