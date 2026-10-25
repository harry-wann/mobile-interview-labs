// L01：限制同時 3 個，總共卻花了 600 毫秒（分批執行，沒有在完成後補位）
// 對應複習站：Coding04Async 第 1 題「怎麼限制最多只有 N 個同時進行？」
//
// 情境：行情頁要載入 9 個交易對的 K 線，最多同時 3 個。每個請求花的時間不一樣：
// 第 1、4、7 個慢（200 毫秒），其他快（20 毫秒）。
//       回傳的結果要和輸入的順序一樣。（所有請求一口氣全部送出的寫法，見
// Kotlin04 L04。）
// Bug 版：把 9 個切成 3 批（chunked），一批 3 個、一批做完才開始下一批。
// 同時確實不超過 3 個，但每一批都要等最慢的那個，
//        快的請求做完之後空出來的位子沒有人補，3 批各等 200 毫秒，共 600 毫秒。
// 修正版：Semaphore(3)，每個請求在 async 裡面用 withPermit 包起來。
// 快的做完立刻歸還通行證、下一個接手，
//        慢的請求不會拖住別人，約 260 毫秒做完；awaitAll 會照原本的順序回傳。
package tw.harry.labs.solution.coding04async

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val jobs = listOf(200, 20, 20, 200, 20, 20, 200, 20, 20).mapIndexed {
    index,
    ms,
    ->
    C04Job("COIN${index + 1}", ms)
}
private const val LIMIT = 3

/** 假的 K 線 API：花 job.ms 毫秒，並讓 meter 記下同時有幾個在進行 */
private suspend fun fetch(job: C04Job, meter: C04Meter): String {
    meter.enter()
    try {
        delay(job.ms.toLong())
        return job.name
    } finally {
        meter.leave()
    }
}

/** 從「…總共花了 N 毫秒」取出 N（自動檢查用） */
private fun elapsedMs(lines: List<String>): Int? =
    lines.firstOrNull { it.contains("總共花了 ") && it.endsWith(" 毫秒") }
        ?.substringAfter("總共花了 ")?.removeSuffix(" 毫秒")?.toIntOrNull()

/**
 * 演練流程（兩個版本共用）：9 個請求、同時最多 3 個，量時間、
 * 同時進行的最高紀錄與結果順序
 */
private suspend fun scenario(
    log: LabLog,
    mapConcurrently: suspend (List<C04Job>, Int, C04Meter) -> List<String>,
) {
    val meter = C04Meter()
    val start = System.nanoTime()
    val results = mapConcurrently(jobs, LIMIT, meter)
    val ms = (System.nanoTime() - start) / 1_000_000
    log("${jobs.size} 個請求、同時最多 $LIMIT 個：總共花了 $ms 毫秒")
    log("同時進行的請求最多 ${meter.peak} 個")
    log("結果和輸入的順序相同：${if (results == jobs.map { it.name }) "是" else "否"}")
}

// MARK: - Bug 版
private object L01Bug {
    suspend fun mapConcurrently(
        jobs: List<C04Job>,
        limit: Int,
        meter: C04Meter,
    ): List<String> = // 切成一批一批
        jobs.chunked(limit).flatMap { batch ->
            coroutineScope {
                // 這一批全部做完，才開始下一批
                batch.map { job -> async { fetch(job, meter) } }.awaitAll()
            }
        }

    suspend fun run(log: LabLog) = scenario(log, ::mapConcurrently)
}

// MARK: - 修正版
private object L01Fixed {
    suspend fun mapConcurrently(
        jobs: List<C04Job>,
        limit: Int,
        meter: C04Meter,
    ): List<String> = coroutineScope {
        // 只有 limit 張通行證
        val semaphore = Semaphore(limit)
        jobs.map { job ->
            // 在 async 裡面拿通行證，做完就歸還
            async { semaphore.withPermit { fetch(job, meter) } }
            // awaitAll 照原本的順序回傳
        }.awaitAll()
    }

    suspend fun run(log: LabLog) = scenario(log, ::mapConcurrently)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "限制同時 3 個，卻比預期慢一倍多",
    question = "第 1 題：怎麼限制最多只有 N 個同時進行？",
    goal = "用 Semaphore 讓每完成一個就有人補位。分批執行雖然不會超過上限，但每批都要等最慢的那個。",
    steps = listOf(
        "選「Bug 版」執行，看「總共花了幾毫秒」，以及同時進行的最高紀錄（有沒有超過 3？）。",
        "選「修正版」執行，比較花的時間（結果順序一樣要正確）。",
        "想一想：withPermit 寫在 async 外面會怎麼樣？（拿通行證和執行分開了，限制失效）",
    ),
    expectBug = "同時最多 3 個，結果順序正確，但總共花了約 600 毫秒（3 批，每批都等 200 毫秒的慢請求）。",
    expectFixed = "同時最多 3 個，結果順序正確，只花約 260 毫秒（快的做完立刻有人接手）。",
    file = "coding04-async/L01_SlidingWindow.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val ms = elapsedMs(lines)
        when {
            ms == null -> "演練沒有跑完：要用 scenario(log, mapConcurrently) 執行"
            "結果和輸入的順序相同：是" !in lines -> "mapConcurrently 還沒寫對：9 個結果要照輸入的順序回傳"
            "同時進行的請求最多 3 個" !in lines -> "同時進行的請求應該剛好最多 3 個：一批 3 個"
            ms < 500 -> "Bug 還沒重現：要用 chunked(limit) 切成一批一批，一批全部做完才開始下一批"
            else -> null
        }
    },
    checkFixed = { lines ->
        val ms = elapsedMs(lines)
        when {
            ms == null -> "演練沒有跑完：要用 scenario(log, mapConcurrently) 執行"
            "結果和輸入的順序相同：是" !in lines -> "mapConcurrently 還沒寫對：每個 async 回傳結果，awaitAll 會照原本的順序"
            "同時進行的請求最多 3 個" !in lines -> "同時進行的請求應該剛好最多 3 個：Semaphore(limit)，在 async 裡面 withPermit"
            ms >= 450 -> "花了 $ms 毫秒：要用 Semaphore，快的做完立刻歸還通行證讓下一個接手，不要等一批做完"
            else -> null
        }
    },
)
