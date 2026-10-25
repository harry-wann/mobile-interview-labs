// L07：載入設定失敗後，每次讀取都再打一次伺服器（lazy 初始化丟例外）
// 對應複習站：Kotlin02Functions 第 5 題「lazy 的三種模式差在哪？初始化丟例外會怎樣？」
//
// 情境：畫面一次繪製會讀 3 次 config（手續費率、最小下單量、維護公告都在裡面）。config 用 by lazy 向伺服器要，
//        這時伺服器連不上，初始化器丟出 IOException。
// Bug 版：by lazy { loadFromServer() }。初始化丟例外時，lazy 不會把失敗記起來，下次讀取又重新執行初始化器，
//        所以畫面一次繪製就向伺服器要了 3 次；每次又都失敗，持續浪費流量與時間。
// 修正版：初始化器裡自己接住例外，回傳 Result，成功或失敗都被快取起來；要不要重試由呼叫端明確決定。
package tw.harry.labs.solution.kotlin02functions

import java.io.IOException
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 向伺服器要設定：這次一定連不上 */
private class RemoteConfig(private val log: LabLog) {
    var attempts = 0
        private set

    fun loadFromServer(): String {
        attempts++
        log("向伺服器要設定（第 $attempts 次）")
        throw IOException("連不上伺服器")
    }
}

// MARK: - Bug 版
private object L07Bug {
    class ConfigHolder(remote: RemoteConfig) {
        val config by lazy { remote.loadFromServer() }               // 丟例外：lazy 不記得失敗
    }

    fun run(log: LabLog) {
        val remote = RemoteConfig(log)
        val holder = ConfigHolder(remote)
        repeat(3) { i ->                                             // 畫面一次繪製讀 3 次
            try {
                holder.config
            } catch (e: IOException) {
                log("第 ${i + 1} 次讀取失敗：${e.message}")
            }
        }
        log("共向伺服器要了 ${remote.attempts} 次")
    }
}

// MARK: - 修正版
private object L07Fixed {
    class ConfigHolder(remote: RemoteConfig) {
        val config by lazy { runCatching { remote.loadFromServer() } }   // 成功或失敗都存起來
    }

    fun run(log: LabLog) {
        val remote = RemoteConfig(log)
        val holder = ConfigHolder(remote)
        repeat(3) { i ->
            holder.config.onFailure { log("第 ${i + 1} 次讀取失敗：${it.message}") }
        }
        log("共向伺服器要了 ${remote.attempts} 次")
    }
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "載入設定失敗後，每次讀取都再打一次伺服器",
    question = "第 5 題：lazy 的三種模式差在哪？初始化丟例外會怎樣？",
    goal = "lazy 的初始化器丟出例外時，lazy 不會記住失敗。看畫面一次繪製讀 3 次會向伺服器要幾次。",
    steps = listOf(
        "選「Bug 版」執行，數「向伺服器要設定」出現幾次。",
        "選「修正版」執行，確認只要一次，3 次讀取都拿到同一個失敗結果。",
        "想一想：修正版如果要「過 30 秒再重試一次」，holder 該怎麼改？（記下失敗的時間，到了才重新建立）",
    ),
    expectBug = "「向伺服器要設定」出現 3 次，最後一行是「共向伺服器要了 3 次」。",
    expectFixed = "只出現 1 次「向伺服器要設定」，3 次讀取都印出「讀取失敗」，最後一行是「共向伺服器要了 1 次」。",
    file = "kotlin02-functions/L07_LazyException.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("共向伺服器要了 ") } -> "演練沒有跑完：要讀 3 次 holder.config"
            "共向伺服器要了 3 次" !in lines -> "Bug 版每次讀取都要重新向伺服器要（共 3 次）：用 by lazy { remote.loadFromServer() }"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("共向伺服器要了 ") } -> "演練沒有跑完：要讀 3 次 holder.config"
            "共向伺服器要了 1 次" !in lines -> "只應該向伺服器要 1 次：lazy 的初始化器改用 runCatching 接住例外，連失敗一起快取"
            lines.count { it.contains("次讀取失敗") } != 3 -> "3 次讀取都要印出失敗：讀 holder.config 時用 onFailure"
            else -> null
        }
    },
)
