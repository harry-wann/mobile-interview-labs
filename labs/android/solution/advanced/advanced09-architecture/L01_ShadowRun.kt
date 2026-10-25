// L01：整個換成新的手續費計算，使用者被多收、少收了幾圓，卻沒有人發現（strangler：新舊並存、先比對再切換）
// 對應複習站：Advanced09Architecture 第 1 題「面對已經上線的舊專案，怎麼逐步演進，而不是整個重寫？」
//
// 情境：舊的手續費計算（legacy）是「每一筆成交各自算 0.1%，四捨五入到圓，再加總」。團隊把它重寫成乾淨的新實作，
//       新實作「先把成交金額加總，再算一次 0.1% 並四捨五入」。看起來只是整理程式碼，但四捨五入的時機不同，結果就不同。
//       演練有 6 筆委託，每筆有 1 到 3 筆成交（金額單位日圓）。這個差異沒有測試保護（沒有 characterization test）。
// Bug 版：big bang：直接把呼叫端換成新實作。6 筆裡有 3 筆手續費和舊的不一樣（使用者被多收或少收 1 圓），系統完全沒有記錄，沒人知道。
// 修正版：strangler 的 shadow run：使用者看到的仍然是舊實作的結果，同時在旁邊跑一份新實作，兩邊不一致就記錄下來。
//        先靠記錄找出新實作的差異、修好，不一致降到 0 之後，才把開關切到新實作。
package tw.harry.labs.solution.advanced09architecture

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private typealias FeeFunction = (List<Int>) -> Int
private typealias OnMismatch = (fills: List<Int>, old: Int, new: Int) -> Unit

/** 0.1% 的手續費，四捨五入到圓 */
private fun roundedFee(notional: Int): Int = (notional + 500) / 1000

/** 舊實作：每筆成交各自算、各自四捨五入，再加總 */
private fun legacyFee(fills: List<Int>): Int = fills.sumOf(::roundedFee)

/** 新實作的第一版：先加總，再算一次四捨五入（和舊實作不一樣） */
private fun newFeeV1(fills: List<Int>): Int = roundedFee(fills.sum())

/** 新實作修好之後：行為和舊實作一致 */
private fun newFeeV2(fills: List<Int>): Int = fills.sumOf(::roundedFee)

private val orders = listOf(
    listOf(10_000),
    listOf(4_500, 4_500, 4_500),
    listOf(20_000, 30_000),
    listOf(1_499, 1_499, 1_499),
    listOf(250_000),
    listOf(2_600, 2_600),
)

/** 演練流程（兩個版本共用）：makeCalculator(舊實作, 新實作, 不一致時的記錄函式) 回傳「呼叫端實際使用的計算函式」 */
private fun scenario(log: LabLog, makeCalculator: (FeeFunction, FeeFunction, OnMismatch) -> FeeFunction) {
    val legacy = orders.map(::legacyFee)
    log("舊實作的手續費：$legacy")

    var recorded = 0
    val calculator = makeCalculator(::legacyFee, ::newFeeV1) { _, _, _ -> recorded++ }
    val charged = orders.map(calculator)
    log("使用者被收的手續費：$charged")
    log("和舊實作不一樣的有 ${charged.zip(legacy).count { (a, b) -> a != b }} 筆")
    log("系統記錄到的不一致：$recorded 筆")

    // 修好新實作（V2）之後再比對一輪
    recorded = 0
    val calculatorAfterFix = makeCalculator(::legacyFee, ::newFeeV2) { _, _, _ -> recorded++ }
    orders.forEach { calculatorAfterFix(it) }
    log("修正新實作之後再比對，系統記錄到的不一致：$recorded 筆")
}

// MARK: - Bug 版
private object L01Bug {
    fun makeCalculator(legacy: FeeFunction, candidate: FeeFunction, onMismatch: OnMismatch): FeeFunction =
        candidate                                               // big bang：直接換成新實作，沒有任何比對

    suspend fun run(log: LabLog) = scenario(log, ::makeCalculator)
}

// MARK: - 修正版
private object L01Fixed {
    fun makeCalculator(legacy: FeeFunction, candidate: FeeFunction, onMismatch: OnMismatch): FeeFunction = { fills ->
        val old = legacy(fills)
        val new = candidate(fills)                              // shadow run：新實作在旁邊跑
        if (old != new) onMismatch(fills, old, new)             // 不一致就記錄下來
        old                                                     // 使用者看到的仍然是舊實作的結果
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeCalculator)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "整個換成新實作，手續費悄悄變了",
    question = "第 1 題：面對已經上線的舊專案，怎麼逐步演進，而不是整個重寫？",
    goal = "新舊並存的期間，使用者要繼續拿到舊實作的結果，同時比對新實作；不一致降到 0 才切換。",
    steps = listOf(
        "選「Bug 版」執行，比較「使用者被收的手續費」和舊實作的手續費，以及「系統記錄到的不一致」。",
        "想一想：新實作哪裡和舊的不一樣？如果上線之後才發現，要怎麼知道有多少人受影響？",
        "選「修正版」執行：使用者仍是舊結果，系統記錄到 3 筆不一致；修正新實作之後再比對，是 0 筆。",
        "進階：真正的做法再加上 feature flag 控制切換、記錄不一致的細節（輸入、舊結果、新結果）上傳監控，並在 CI 用 characterization test 鎖住舊行為。",
    ),
    expectBug = "「使用者被收的手續費：[10, 14, 50, 4, 250, 5]」，「和舊實作不一樣的有 3 筆」，但「系統記錄到的不一致：0 筆」：沒有人發現。",
    expectFixed = "「使用者被收的手續費：[10, 15, 50, 3, 250, 6]」（和舊實作一致），「系統記錄到的不一致：3 筆」；修正新實作之後再比對是 0 筆。",
    file = "advanced09-architecture/L01_ShadowRun.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("使用者被收的手續費") } -> "演練沒有跑完：要用 scenario 執行"
            "使用者被收的手續費：[10, 14, 50, 4, 250, 5]" !in lines -> "Bug 還沒重現：makeCalculator 直接回傳新實作 candidate，使用者被收的會是 [10, 14, 50, 4, 250, 5]"
            "系統記錄到的不一致：0 筆" !in lines -> "Bug 版不該有任何比對記錄"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("使用者被收的手續費") } -> "演練沒有跑完：要用 scenario 執行"
            "使用者被收的手續費：[10, 15, 50, 3, 250, 6]" !in lines -> "使用者看到的應該仍是舊實作的結果：回傳 legacy(fills)"
            "系統記錄到的不一致：3 筆" !in lines -> "新舊不一致的 3 筆要記錄下來：legacy 和 candidate 都算，不一樣就呼叫 onMismatch"
            "修正新實作之後再比對，系統記錄到的不一致：0 筆" !in lines -> "新實作修好之後應該是 0 筆不一致"
            else -> null
        }
    },
)
