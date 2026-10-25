// L05：用了一陣子就被強制登出，要重新輸入密碼和驗證碼（換新 token
// 之後沒有存下新的 refresh token）
// 對應複習站：Exchange10AccountSecurity 第 5
// 題「怎麼認出新裝置？登入狀態（session）要怎麼管理？」
//
// 情境：登入狀態用「短效的 access token（每次呼叫 API 都帶著）」加上「可輪替的
// refresh token（只用來換新的 access token）」。
//        伺服器每次換新時同時發新的 refresh token，舊的作廢（輪替，rotation）；
// 如果已作廢的舊 refresh token 又被拿來用，
//        代表可能被偷了，伺服器把整組登入狀態作廢，所有裝置都要重新登入。
//        演練用一台假伺服器：登入 → access token 過期 → 呼叫 API 得到 401 → 用
// refresh token 換新 → 存起來 → 再呼叫 API；
//        之後 access token 又過期一次，再重複一次。
// （這題不處理「同時很多請求一起換新」的競爭，只看換新成功之後「存什麼」。）
//
// 你要寫：
//   ① Bug 版（L05Bug.store）：只把 tokens.access 存進新的 session（session.
// copy 只改 access），refresh 不更新。
//   ② 修正版（L05Fixed.store）：access 與 refresh 都換成 tokens 裡的新值。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange10-account-security/L05_RefreshRotation.kt
package tw.harry.labs.practice.exchange10accountsecurity

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L05Tokens(val access: String, val refresh: String)

private data class L05Session(val access: String, val refresh: String)

private sealed interface L05RefreshResult {
    class Success(val tokens: L05Tokens) : L05RefreshResult

    // 已作廢的舊 refresh token 又被拿來用：整組登入作廢
    data object ReuseDetected : L05RefreshResult
    data object Invalid : L05RefreshResult
}

/**
 * 假伺服器：access token 會過期，refresh token 每次換新就輪替，
 * 舊的被重複使用就作廢整組登入
 */
private class L05AuthServer {
    private var sequence = 1
    private var currentRefresh = "refresh-1"
    private var validAccess = "access-1"
    private val usedRefresh = mutableSetOf<String>()
    private var sessionRevoked = false

    fun login() = L05Tokens(validAccess, currentRefresh)

    /** access token 過期（時間到了） */
    fun expireAccess() {
        validAccess = "expired"
    }

    /** 呼叫 API：access token 有效才是 200 */
    fun call(access: String) = !sessionRevoked && access == validAccess

    fun refresh(token: String): L05RefreshResult {
        if (sessionRevoked) return L05RefreshResult.ReuseDetected
        if (token == currentRefresh) {
            usedRefresh += token // 舊的作廢
            sequence++
            currentRefresh = "refresh-$sequence"
            validAccess = "access-$sequence"
            return L05RefreshResult.Success(
                L05Tokens(validAccess, currentRefresh),
            )
        }
        if (token in usedRefresh) {
            // 重複使用已作廢的 token：整組登入作廢
            sessionRevoked = true
            return L05RefreshResult.ReuseDetected
        }
        return L05RefreshResult.Invalid
    }
}

/**
 * 演練流程（兩個版本共用）：登入後，access token 過期兩次，每次都換新再呼叫 API
 */
private fun scenario(
    log: LabLog,
    store: (L05Session, L05Tokens) -> L05Session,
) {
    val server = L05AuthServer()
    val first = server.login()
    var session = L05Session(first.access, first.refresh)
    log("登入：${session.access}、${session.refresh}")
    var succeeded = 0
    var forcedLogout = false

    for (round in 1..2) {
        server.expireAccess()
        if (server.call(session.access)) {
            log("第 $round 輪：access token 還有效（不應該）")
            continue
        }
        log("第 $round 輪：access token 過期，呼叫 API 得到 401")
        when (val result = server.refresh(session.refresh)) {
            is L05RefreshResult.Success -> {
                succeeded++
                session = store(session, result.tokens)
                log(
                    "第 $round 次換新成功，呼叫 API：${if (server.call(
                            session.access,
                        )
                    ) {
                        "成功"
                    } else {
                        "失敗（401）"
                    }}",
                )
            }

            L05RefreshResult.ReuseDetected -> {
                forcedLogout = true
                log("第 $round 次換新：伺服器判定舊的 refresh token 被重複使用，整組登入作廢")
            }

            L05RefreshResult.Invalid -> {
                log("第 $round 次換新：refresh token 無效")
            }
        }
        if (forcedLogout) break
    }
    log("共換新 2 輪，成功 $succeeded 次；使用者被強制登出：${if (forcedLogout) "是" else "否"}")
}

// MARK: - Bug 版
private object L05Bug {
    fun store(session: L05Session, tokens: L05Tokens): L05Session {
        // TODO ①：回傳 session.copy(...)，只把 access 換成 tokens.
        // access（refresh 不更新）
        return session
    }

    suspend fun run(log: LabLog) = scenario(log) { session, tokens ->
        store(session, tokens)
    }
}

// MARK: - 修正版
private object L05Fixed {
    fun store(session: L05Session, tokens: L05Tokens): L05Session {
        // TODO ②：回傳 session.copy(...)，access 與 refresh 都換成 tokens
        // 裡的新值
        return session
    }

    suspend fun run(log: LabLog) = scenario(log) { session, tokens ->
        store(session, tokens)
    }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "用了一陣子就被強制登出",
    question = "第 5 題：怎麼認出新裝置？登入狀態（session）要怎麼管理？",
    goal = "refresh token 每次換新都會輪替：伺服器發新的、舊的作廢。換新成功後，新的 access token 和新的 refresh token 都要存起來；繼續用舊的 refresh token，伺服器會當成被偷。",
    steps = listOf(
        "打開 practice/exchange/exchange10-account-security/L05_RefreshRotation.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看第 1 次換新後 API 成功，第 2 次換新發生什麼事。",
        "想一想：第 2 次拿去換的 refresh token 是哪一個？伺服器為什麼把它當成「被偷」？",
        "寫 TODO ②（修正版），選「修正版」執行，兩輪換新都成功，沒有被強制登出。",
        "進階：這種錯很容易只在「過了一段時間」才出現，開發時 access token 沒過期兩次，測試就看不到；寫單元測試時用假伺服器，連續換新三次以上。",
    ),
    expectBug = "「第 1 次換新成功，呼叫 API：成功」，「第 2 次換新：伺服器判定舊的 refresh token 被重複使用，整組登入作廢」，「共換新 2 輪，成功 1 次；使用者被強制登出：是」。",
    expectFixed = "兩次換新都成功，每次換新後呼叫 API 都成功，「共換新 2 輪，成功 2 次；使用者被強制登出：否」。",
    file = "exchange10-account-security/L05_RefreshRotation.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("共換新 2 輪") } -> {
                "演練沒有跑完：要用 scenario(log) { session, tokens -> … } 執行"
            }

            "第 1 次換新成功，呼叫 API：成功" !in lines -> {
                "Bug 版也要把新的 access token 存起來，第 1 次換新之後呼叫 API 才會成功"
            }

            "第 2 次換新：伺服器判定舊的 refresh token 被重複使用，整組登入作廢" !in lines -> {
                "Bug 還沒重現：refresh token 不更新，第 2 次會拿已經作廢的舊 refresh token 去換"
            }

            "共換新 2 輪，成功 1 次；使用者被強制登出：是" !in lines -> {
                "使用者應該被強制登出"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("共換新 2 輪")
            } -> "演練沒有跑完：要用 scenario(log) { session, tokens -> … } 執行"

            "第 1 次換新成功，呼叫 API：成功" !in lines -> "第 1 次換新之後，呼叫 API 要成功（要把新的 access token 存起來）"

            "第 2 次換新成功，呼叫 API：成功" !in lines -> "第 2 次換新要成功：要把新的 refresh token 一起存起來，舊的已經被作廢"

            "共換新 2 輪，成功 2 次；使用者被強制登出：否" !in lines -> "不應該被強制登出"

            else -> null
        }
    },
)
