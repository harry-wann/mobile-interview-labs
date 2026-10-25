// L06：算出來的缺口多包了一根已經載入的 K 線，相鄰的兩段還被當成有缺口（區間的
// +1、-1 寫錯）
// 對應複習站：Coding01DataStructures 第 5 題「已經載入幾段 K 線，
// 怎麼算出還缺哪幾段？」
//
// 情境：K 線分段載入，區間用 K 線的索引表示，兩端都包含（閉區間），`3..4`
// 代表第 3、4 根。
//       使用者要看一個範圍，只該請求「還沒載入」的部分。演練用 5
// 組固定的資料檢查答案。
// Bug 版：先排序，游標從範圍起點往右掃。遇到空隙時，
// 缺口的終點寫成這一段的起點（少減 1，把已經載入的那一根也算進去），
//        掃過一段之後，游標只移到這一段的終點（少加 1）。結果缺口多包一根，
// 相鄰的 `1..3` 與 `4..6` 還被判成中間有缺口。
// 修正版：缺口 = 游標  這一段的起點 - 1；掃過之後游標移到終點 + 1。
// 已經超出範圍終點的區間不用看。
package tw.harry.labs.solution.coding01datastructures

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L06Case(
    val title: String,
    val loaded: List<IntRange>,
    val request: IntRange,
    val expected: List<IntRange>,
)

private val cases = listOf(
    L06Case("中間缺兩段", listOf(6..8, 3..4), 1..10, listOf(1..2, 5..5, 9..10)),
    L06Case("相鄰的兩段", listOf(1..3, 4..6), 1..10, listOf(7..10)),
    L06Case("什麼都沒載入", emptyList(), 0..99, listOf(0..99)),
    L06Case("已經全部載入", listOf(0..200), 10..20, emptyList()),
    L06Case("重疊與範圍外", listOf(6..9, 1..7, 20..30), 5..15, listOf(10..15)),
)

/** 演練流程（兩個版本共用）：5 組資料各算一次，和標準答案比對 */
private fun scenario(
    log: LabLog,
    missing: (List<IntRange>, IntRange) -> List<IntRange>,
) {
    var right = 0
    cases.forEachIndexed { index, item ->
        val got = missing(item.loaded, item.request)
        val ok = got == item.expected
        if (ok) right++
        log(
            "案例 ${index + 1}（${item.title}）：得到 ${c01RangesText(
                got,
            )}，期待 ${c01RangesText(item.expected)} ${if (ok) "✓" else "✗"}",
        )
    }
    log("答對 $right/${cases.size}")
}

// MARK: - Bug 版
private object L06Bug {
    fun missing(loaded: List<IntRange>, request: IntRange): List<IntRange> {
        val gaps = ArrayList<IntRange>()
        var cursor = request.first
        for (range in loaded.sortedBy { it.first }) {
            if (range.first > cursor) {
                // 少減 1：缺口多包了這一段的第一根
                gaps.add(cursor..range.first)
            }
            // 少加 1：游標停在已經載入的最後一根
            cursor = maxOf(cursor, range.last)
        }
        if (cursor < request.last) {
            gaps.add(cursor..request.last)
        }
        return gaps
    }

    suspend fun run(log: LabLog) = scenario(log, ::missing)
}

// MARK: - 修正版
private object L06Fixed {
    fun missing(loaded: List<IntRange>, request: IntRange): List<IntRange> {
        val gaps = ArrayList<IntRange>()
        var cursor = request.first
        for (range in loaded.sortedBy { it.first }) {
            // 這一段已經在範圍外面了
            if (range.first > request.last) break
            if (range.first > cursor) {
                // 缺口停在這一段的前一根
                gaps.add(cursor..(range.first - 1))
            }
            // 游標移到這一段的下一根
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor <= request.last) {
            // 最後剩下的尾巴
            gaps.add(cursor..request.last)
        }
        return gaps
    }

    suspend fun run(log: LabLog) = scenario(log, ::missing)
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "缺口多包一根，相鄰區間被當成有缺口",
    question = "第 5 題：已經載入幾段 K 線，怎麼算出還缺哪幾段？",
    goal = "閉區間的缺口是「游標 .. 起點 − 1」，掃過一段後游標移到「終點 + 1」；相鄰的區間中間沒有缺口。",
    steps = listOf(
        "選「Bug 版」執行，看案例 1 與案例 2 得到什麼，和期待的差在哪裡（每一段都差 1）。",
        "選「修正版」執行，確認 5 個案例全部答對，包含相鄰的兩段與範圍外的區間。",
        "想一想：開始寫之前，要先向面試官確認哪兩件事？（區間兩端包不包含、相鄰算不算連續）",
    ),
    expectBug = "案例 1 得到 [1...3, 4...6, 8...10]，案例 2 得到 [3...4, 6...10]；5 組裡答對 2 組。",
    expectFixed = "5 組全部答對：「答對 5/5」。",
    file = "coding01-data-structures/L06_MissingRanges.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("答對") } -> {
                "演練沒有跑完：要用 scenario(log, missing) 執行"
            }

            "案例 1（中間缺兩段）：得到 [1...3, 4...6, 8...10]，期待 [1...2, 5...5, 9...10] ✗" !in
                lines -> {
                "Bug 還沒重現：缺口寫成 cursor..range.first（少減 1），游標只移到 range.last（少加 1）"
            }

            "案例 2（相鄰的兩段）：得到 [3...4, 6...10]，期待 [7...10] ✗" !in lines -> {
                "Bug 還沒重現：游標少加 1，相鄰的 1..3 與 4..6 才會被當成中間有缺口"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val wrong = lines.firstOrNull {
            it.startsWith("案例") && it.endsWith("✗")
        }
        when {
            lines.none {
                it.startsWith("答對")
            } -> "演練沒有跑完：要用 scenario(log, missing) 執行"

            wrong != null -> "還有案例答錯：$wrong"

            "答對 5/5" !in lines -> "5 個案例都要答對：缺口 = cursor..(first - 1)，游標 = last + 1"

            else -> null
        }
    },
)
