// L04：把 value class 放進 List 存 K 線，記憶體比預期大好幾倍（boxing）
// 對應複習站：Kotlin01Types 第 4 題「value class 是什麼？它是零成本嗎？」
//
// 情境：行情畫面要把最近 30 萬筆成交價存在記憶體裡畫 K 線。
// 為了防止價格和數量傳錯，價格包成 value class TickPrice。
// Bug 版：用 List<TickPrice> 存。value class 平常會還原成底層的 Long，
// 但放進泛型（List<T>）就必須變成真的物件，
//        每一筆都多配置一個 TickPrice 物件，再加上清單裡的參考，每筆遠大於
// Long 本身的 8 bytes（電腦上的 JVM 實測約 31 bytes）。
// 修正版：熱路徑（每秒大量資料的地方）內部用 LongArray 存原始的 Long，對外的
// get 才包成 TickPrice，
//        型別安全還在，只有取出來的那一刻才包，資料本身不會 boxing。
package tw.harry.labs.solution.kotlin01types

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 價格（最小單位的整數）。包成 value class 的好處：
 * 編譯器擋得住「把數量當價格傳」
 */
@JvmInline
private value class TickPrice(val raw: Long)

private const val COUNT = 300_000

/** 強制 GC 之後的已使用記憶體（bytes） */
private fun usedBytes(): Long {
    repeat(3) { System.gc() }
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

/**
 * 建立資料並量它佔了多少記憶體，回傳資料本身（讓它留在記憶體裡直到量完）
 * 與每筆約幾 bytes
 */
private fun <T : Any> measure(build: () -> T): Pair<T, Double> {
    val before = usedBytes()
    val data = build()
    val after = usedBytes()
    return data to (after - before).toDouble() / COUNT
}

/** 兩個版本共用：先看 value class 轉成 Any 時會不會變成物件 */
private fun showBoxing(log: LabLog) {
    val p = TickPrice(1)
    // 轉成 Any：這裡才真的配置一個 TickPrice 物件
    val a: Any = p
    // 再轉一次：又配置一個
    val b: Any = p
    log("同一個價格轉成 Any 兩次：${if (a === b) "同一個物件" else "兩個不同的物件（每次都 boxing）"}")
}

// MARK: - Bug 版
private object L04Bug {
    fun run(log: LabLog) {
        showBoxing(log)
        val (history, bytes) = measure {
            // List<T> 是泛型：每個 TickPrice 都被包成物件
            List(COUNT) { TickPrice(1_000_000L + it) }
        }
        log("第 123 筆價格 = ${runCatching { history[123].raw }.getOrNull()}")
        log("存 $COUNT 筆，每筆約 ${bytes.toInt()} bytes")
    }
}

// MARK: - 修正版
private object L04Fixed {
    /** 內部用 LongArray 存原始的 Long；對外的 get 才包成 TickPrice */
    class PriceSeries(private val raw: LongArray) {
        operator fun get(index: Int) = TickPrice(raw[index])
    }

    fun run(log: LabLog) {
        showBoxing(log)
        val (history, bytes) = measure {
            // 資料是原始的 Long，沒有物件
            PriceSeries(LongArray(COUNT) { 1_000_000L + it })
        }
        log("第 123 筆價格 = ${runCatching { history[123].raw }.getOrNull()}")
        log("存 $COUNT 筆，每筆約 ${bytes.toInt()} bytes")
    }
}

// MARK: - 演練設定
private fun bytesPerItem(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("存 $COUNT 筆，每筆約 ")
}?.substringAfter("每筆約 ")?.substringBefore(" bytes")?.toIntOrNull()

val l04 = Lab(
    id = "L04",
    title = "value class 放進 List，記憶體大好幾倍",
    question = "第 4 題：value class 是什麼？它是零成本嗎？",
    goal = "把 30 萬筆價格存起來，比較 List<TickPrice> 和 LongArray 各佔多少記憶體。",
    steps = listOf(
        "選「Bug 版」執行，看「同一個價格轉成 Any 兩次」的結果，以及「每筆約 N bytes」。",
        "選「修正版」執行，比較每筆的 bytes。",
        "想一想：在手機上跑，數字會和電腦上的 JVM 一樣嗎？（不一定；物件的大小因系統而異，要以 Android Studio 的 Memory Profiler 為準）",
    ),
    expectBug = "兩次轉成 Any 是兩個不同的物件；每筆 16 bytes 以上（電腦上的 JVM 實測約 31 bytes，Android 模擬器 API 29 約 20 bytes）。",
    expectFixed = "每筆約 8–10 bytes（電腦上的 JVM 實測約 10，Android 模擬器約 8，就是一個 Long），第 123 筆還是 1000123。",
    file = "kotlin01-types/L04_ValueClassBoxing.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val bytes = bytesPerItem(lines)
        when {
            "第 123 筆價格 = 1000123" !in lines -> "資料沒存起來：用 List(COUNT) { TickPrice(1_000_000L + it) } 建立"
            (bytes ?: 0) < 16 -> "每筆只有 ${bytes ?: 0} bytes：Bug 版要用 List<TickPrice>，才會有 boxing"
            else -> null
        }
    },
    checkFixed = { lines ->
        val bytes = bytesPerItem(lines)
        when {
            "第 123 筆價格 = 1000123" !in lines -> "資料沒存起來：PriceSeries 要用 LongArray 存，get 才包成 TickPrice"
            (bytes ?: 99) > 14 -> "每筆還有 ${bytes ?: 0} bytes：內部要用 LongArray，不要放進 List"
            else -> null
        }
    },
)
