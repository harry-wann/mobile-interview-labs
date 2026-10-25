// L02：把測試分成 3 份同時跑，卻還是等了 12 分鐘（分片沒有平均）
// 對應複習站：Testing06CI 第 2 題「CI 太慢的時候，怎麼讓它變快？」
//
// 情境：12 個測試檔（T06TestFile.all，每個有上一次量到的耗時，總共 1160 秒）
// 要分成 3 份，在 3 台機器上同時跑。
//       整體要等「最慢的那一份」跑完。shard(tests, count)
// 決定每份分到哪些測試檔。
//       最理想的情況是每份約 387 秒。
// Bug 版：照檔案順序平分數量：每份 4 個檔案。前面的檔案都是慢的，第 1 份 770
// 秒，第 3 份只要 80 秒，整體等了 770 秒。
// 修正版：依耗時分配：先從最慢的檔案開始，每個檔案都放進「目前總耗時最少」
// 的那一份（greedy）。三份都接近 387 秒，整體約 390 秒。
package tw.harry.labs.solution.testing06ci

import kotlin.math.roundToInt
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：分片、確認每個檔案剛好被分到一份、印出每份耗時 */
private fun scenario(
    log: LabLog,
    shard: (List<T06TestFile>, Int) -> List<List<T06TestFile>>,
) {
    val tests = T06TestFile.all
    val shards = shard(tests, 3)
    val eachOnce =
        shards.size == 3 &&
            shards.flatten().map { it.name }.sorted() ==
            tests.map { it.name }.sorted()
    log(
        "${tests.size} 個測試檔分成 ${shards.size} 份，每個檔案剛好一份：${if (eachOnce) "是" else "否"}",
    )
    val totals = shards.map { part -> part.sumOf { it.seconds } }
    log("每份耗時：" + totals.joinToString("、") { "$it 秒" })
    log(
        "整體要等 ${totals.maxOrNull() ?: 0} 秒（最理想約 ${(
            tests.sumOf {
                it.seconds
            } / 3.0
        ).roundToInt()} 秒）",
    )
}

// MARK: - Bug 版
private object L02Bug {
    fun shard(tests: List<T06TestFile>, count: Int): List<List<T06TestFile>> =
        // 照順序平分數量
        tests.chunked((tests.size + count - 1) / count)

    fun run(log: LabLog) = scenario(log, ::shard)
}

// MARK: - 修正版
private object L02Fixed {
    fun shard(tests: List<T06TestFile>, count: Int): List<List<T06TestFile>> {
        val shards = List(count) { mutableListOf<T06TestFile>() }
        val totals = IntArray(count)
        // 從最慢的開始
        for (test in tests.sortedByDescending { it.seconds }) {
            val lightest = totals.indices.minByOrNull { totals[it] }!!
            // 放進目前總耗時最少的那一份
            shards[lightest] += test
            totals[lightest] += test.seconds
        }
        return shards
    }

    fun run(log: LabLog) = scenario(log, ::shard)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "分成 3 份同時跑，還是等了 12 分鐘",
    question = "第 2 題：CI 太慢的時候，怎麼讓它變快？",
    goal = "分片要依測試的耗時平均分配，不是平分檔案數量；整體時間取決於最慢的那一份。",
    steps = listOf(
        "選「Bug 版」執行，看每份的耗時和「整體要等多久」。",
        "想一想：為什麼三台機器同時跑，整體時間卻只比一台機器快一點點？",
        "選「修正版」執行：每個檔案都要剛好分到一份，整體時間要接近最理想的 387 秒。",
    ),
    expectBug = "每份耗時：770 秒、310 秒、80 秒；整體要等 770 秒。",
    expectFixed = "三份耗時都接近 387 秒（解答實測 390、390、380）；整體約 390 秒。",
    file = "testing06-ci/L02_UnbalancedShards.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "12 個測試檔分成 3 份，每個檔案剛好一份：是" !in lines -> "12 個測試檔都要分到 3 份其中一份，而且每個檔案只能出現一次"
            "整體要等 770 秒（最理想約 387 秒）" !in lines -> "Bug 還沒重現：照檔案順序，每份分 4 個檔案（前面的檔案都是慢的），整體要等 770 秒"
            else -> null
        }
    },
    checkFixed = { lines ->
        val seconds = lines.firstOrNull {
            it.startsWith("整體要等 ")
        }?.removePrefix("整體要等 ")?.takeWhile { it.isDigit() }?.toIntOrNull()
        when {
            "12 個測試檔分成 3 份，每個檔案剛好一份：是" !in lines -> "12 個測試檔都要分到 3 份其中一份，而且每個檔案只能出現一次"
            seconds == null -> "找不到整體耗時"
            seconds > 420 -> "整體要等 $seconds 秒，還是不平均：先把檔案依耗時由大到小排序，每個檔案都放進目前總耗時最少的那一份"
            else -> null
        }
    },
)
