// L06：價格在門檻附近上下跳，同一個提醒連續送了好幾次（去重與重新武裝）
// 對應複習站：Platform03BackgroundPush 第 6 題「價格提醒要怎麼設計？」
//
// 情境：使用者設定「BTC/JPY 漲到 1,000 萬日圓就通知我」。伺服器逐筆比對行情，
// 觸發時發 FCM 推播。
//        演練送進一串價格：在 1,000 萬附近上下跳了一陣，後來跌到 980 萬，
// 再漲到 1,010 萬。看一共發了幾次推播。
//        （價格提醒要由伺服器比對，這是第 1、6 題的結論；
// 這個演練練的是比對邏輯本身，不管 Swift 還是 Kotlin 都一樣。）
// Bug 版：每一筆價格只要 >= 門檻就發一次推播。價格在門檻上下跳，
// 使用者的手機一直響。
// 修正版：只在「從門檻下方穿到上方」的那一刻發一次，之後進入「已觸發」狀態；
// 價格跌到門檻 99% 以下才重新武裝，下次再穿過才再發。
package tw.harry.labs.solution.platform03backgroundpush

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 價格序列（兩個版本共用）：在 1,000 萬附近跳了一陣，跌到 980 萬，再漲到 1,010
 * 萬
 */
private val prices =
    listOf(
        9_900_000,
        9_990_000,
        10_000_000,
        10_010_000,
        9_995_000,
        10_020_000,
        10_030_000,
        9_998_000,
        10_050_000,
        9_800_000,
        10_100_000,
    )
private const val THRESHOLD = 10_000_000

/** 提醒引擎（兩個版本各自實作） */
private interface L06AlertEngine {
    /** 收到一筆價格，回傳「這一筆要不要發推播」 */
    fun onPrice(price: Int): Boolean
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, engine: L06AlertEngine) {
    val sent = prices.filter { engine.onPrice(it) }
    log("門檻 $THRESHOLD，${prices.size} 筆價格，一共發了 ${sent.size} 次推播")
    log("發推播的價格：${sent.joinToString("、")}")
}

// MARK: - Bug 版
private object L06Bug {
    class Engine : L06AlertEngine {
        override fun onPrice(price: Int): Boolean = // 只要在門檻上就發，每一筆都發
            price >= THRESHOLD
    }

    fun run(log: LabLog) = scenario(log, Engine())
}

// MARK: - 修正版
private object L06Fixed {
    class Engine : L06AlertEngine {
        // 已觸發：價格回到門檻 99% 以下之前不再發
        private var triggered = false

        override fun onPrice(price: Int): Boolean {
            if (triggered) {
                // 跌到 99% 以下：重新武裝
                if (price < THRESHOLD / 100 * 99) triggered = false
                return false
            }
            if (price >= THRESHOLD) {
                // 從下方穿過門檻：發一次，進入已觸發
                triggered = true
                return true
            }
            return false
        }
    }

    fun run(log: LabLog) = scenario(log, Engine())
}

// MARK: - 演練設定
private fun sentCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("門檻 ")
}?.substringAfter("一共發了 ")?.removeSuffix(" 次推播")?.toIntOrNull()

val l06 = Lab(
    id = "L06",
    title = "價格在門檻附近跳，同一個提醒響了好幾次",
    question = "第 6 題：價格提醒要怎麼設計？",
    goal = "價格穿過門檻只通知一次；價格在門檻附近上下跳時不重複通知；跌回去之後再漲過門檻，才通知第二次。",
    steps = listOf(
        "選「Bug 版」按執行，看「一共發了幾次推播」。",
        "選「修正版」執行：應該只發 2 次（第一次穿過 1,000 萬、跌到 980 萬重新武裝後再穿過 1,010 萬）。",
        "想一想：真的系統還要加哪些設計？（collapse_key／apns-collapse-id 讓過時的提醒被新的取代、TTL 設短、冷卻時間、伺服器端用 alertId 去重）",
    ),
    expectBug = "11 筆價格一共發了 6 次推播（價格在 1,000 萬以上的每一筆都發）。",
    expectFixed = "一共發了 2 次推播：10000000 與 10100000。",
    file = "platform03-background-push/L06_AlertRepeat.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = sentCount(lines)
        when {
            n == null -> "演練沒有跑完"
            n < 5 -> "Bug 還沒重現（只發了 $n 次）：價格 >= 門檻就回傳 true，每一筆都發"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = sentCount(lines)
        when {
            n == null -> "演練沒有跑完"
            n == 0 -> "一次都沒有發：價格從下方穿過門檻時要回傳 true"
            n > 2 -> "發了 $n 次，太多了：穿過之後要進入「已觸發」，價格跌到門檻 99% 以下才重新武裝"
            n < 2 -> "只發了 $n 次：價格跌到 980 萬（門檻的 99% 以下）要重新武裝，再漲到 1,010 萬才發第二次"
            else -> null
        }
    },
)
