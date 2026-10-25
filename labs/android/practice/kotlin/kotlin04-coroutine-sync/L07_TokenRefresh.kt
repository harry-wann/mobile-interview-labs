// L07：10 個請求同時收到 401，token 被刷新了 10 次，
// 使用者被登出（single-flight）
// 對應複習站：Kotlin04CoroutineSync 第 4 題「多個請求同時收到 401，怎麼讓
// token 只刷新一次？」
//
// 情境：畫面同時送出 10 個請求，access token 剛好過期，10 個都收到 HTTP 401。
// 每個請求都要刷新 token 再重送。
//        伺服器的 refresh token 是一次性的：用過一次就作廢，換回新的 access
// token 與新的 refresh token。
//
// 你要寫：
//   ① Bug 版（L07Bug）：每個 401 都直接拿目前的 refresh token 去刷新。10
// 個同時送出同一個 refresh token，只有第一個成功。
//   ② 修正版（L07Fixed）：用 Mutex 把「比對舊 token、必要時才刷新」放在同一個
// withLock 裡。進鎖之後發現 token 已經換新，就直接用新的。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin04-coroutine-sync/L07_TokenRefresh.kt
package tw.harry.labs.practice.kotlin04coroutinesync

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 刷新結果：新的 access token 與新的 refresh token */
private class TokenPair(val access: String, val refresh: String)

/** 假的伺服器：refresh token 是一次性的，用過就作廢；每次刷新約 100 ms */
private class AuthServer {
    val refreshCalls = AtomicInteger(0)
    private var validRefresh = "refresh-0"
    private var version = 0
    private val lock = Any()

    suspend fun refresh(refreshToken: String): TokenPair {
        refreshCalls.incrementAndGet()
        delay(100)
        synchronized(lock) {
            if (refreshToken !=
                validRefresh
            ) {
                throw IOException("refresh token 已經作廢")
            }
            version++
            validRefresh = "refresh-$version"
            return TokenPair("access-$version", validRefresh)
        }
    }
}

/** 演練流程（兩個版本共用）：10 個請求都用舊的 access-0 送出，都收到 401 */
private suspend fun scenario(
    log: LabLog,
    server: AuthServer,
    refreshAfter401: suspend (stale: String) -> String,
) {
    val results = coroutineScope {
        List(10) {
            async(Dispatchers.Default) {
                runCatching { refreshAfter401("access-0") }
            }
        }.awaitAll()
    }
    val failed = results.count { it.isFailure }
    log("刷新 API 被呼叫 ${server.refreshCalls.get()} 次")
    log("拿到新 token 的請求 ${results.size - failed} 個，被登出的請求 $failed 個")
}

// MARK: - Bug 版
private object L07Bug {
    class TokenStore(private val server: AuthServer) {
        @Volatile private var access = "access-0"

        @Volatile private var refresh = "refresh-0"

        suspend fun refreshAfter401(stale: String): String {
            // TODO ①：不加鎖，直接 val pair = server.refresh(refresh)，把 pair.
            // access、pair.refresh 存回 access、refresh，回傳 pair.access
            return access
        }
    }

    suspend fun run(log: LabLog) {
        val server = AuthServer()
        val store = TokenStore(server)
        scenario(log, server) { stale -> store.refreshAfter401(stale) }
    }
}

// MARK: - 修正版
private object L07Fixed {
    class TokenStore(private val server: AuthServer) {
        // TODO ②-1：宣告 private val mutex = Mutex()
        @Volatile private var access = "access-0"

        @Volatile private var refresh = "refresh-0"

        suspend fun refreshAfter401(stale: String): String {
            // TODO ②-2：整段放進 mutex.withLock { }。進去之後先比對：如果
            // access != stale，代表前一個等鎖的人已經刷新過，直接回傳 access；
            //          否則才 server.refresh(refresh)，存回 access、refresh，
            // 回傳 pair.access
            return access
        }
    }

    suspend fun run(log: LabLog) {
        val server = AuthServer()
        val store = TokenStore(server)
        scenario(log, server) { stale -> store.refreshAfter401(stale) }
    }
}

// MARK: - 演練設定
private fun refreshCalls(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("刷新 API 被呼叫 ")
}?.removePrefix("刷新 API 被呼叫 ")?.removeSuffix(" 次")?.toIntOrNull()

val l07 = Lab(
    id = "L07",
    title = "10 個請求同時 401，token 刷新了 10 次",
    question = "第 4 題：多個請求同時收到 401，怎麼讓 token 只刷新一次？",
    goal = "10 個請求同時收到 401。refresh token 是一次性的，重複刷新會讓其他請求失敗。先寫出重複刷新的 Bug 版，再讓刷新只發生一次。",
    steps = listOf(
        "打開 practice/kotlin/kotlin04-coroutine-sync/L07_TokenRefresh.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看刷新 API 被呼叫幾次、幾個請求被登出。",
        "寫 TODO ②-1、②-2（修正版），選「修正版」執行，確認只刷新 1 次，10 個請求都拿到新 token。",
        "想一想：為什麼是比對 token，而不是比對過期時間 expiresAt？（不依賴手機的時鐘，也能處理伺服器提早撤銷 token）",
    ),
    expectBug = "「刷新 API 被呼叫 10 次」；只有 1 個請求拿到新 token，「被登出的請求 9 個」。",
    expectFixed = "「刷新 API 被呼叫 1 次」；「拿到新 token 的請求 10 個，被登出的請求 0 個」。",
    file = "kotlin04-coroutine-sync/L07_TokenRefresh.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            refreshCalls(
                lines,
            ) == null -> "演練沒有跑完：要用 scenario(log, server, …) 執行"

            refreshCalls(
                lines,
            ) != 10 -> "Bug 版每個請求都要去刷新（共 10 次）：refreshAfter401 直接呼叫 server.refresh(refresh)，不要加鎖"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            refreshCalls(
                lines,
            ) == null -> "演練沒有跑完：要用 scenario(log, server, …) 執行"

            refreshCalls(
                lines,
            ) != 1 -> "刷新了 ${refreshCalls(
                lines,
            )} 次，應該只有 1 次：用 mutex.withLock，進去之後先比對 access != stale"

            "拿到新 token 的請求 10 個，被登出的請求 0 個" !in lines -> "10 個請求都要拿到新 token：等鎖的人進來之後，直接回傳目前的 access"

            else -> null
        }
    },
)
