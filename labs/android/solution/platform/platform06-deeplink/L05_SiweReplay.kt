// L05：簽章被偷走之後，攻擊者原封不動重送也能登入（簽名訊息要帶 nonce
// 與到期時間）
// 對應複習站：Platform06DeepLink 第 6 題「錢包 App 回跳（WalletConnect）
// 怎麼運作？」
//
// 情境：使用者用錢包簽一段訊息，證明自己擁有某個錢包地址，
// 伺服器驗證簽章後讓他登入。
//        簽章曾經外洩（網路紀錄、釣魚網站…），攻擊者拿到「同一份訊息和簽章」，
// 原封不動送給伺服器。
//        （真實的錢包簽章是橢圓曲線簽章，伺服器只需要錢包地址就能驗證；演練用
// HMAC 代替簽章，重點是「簽的訊息裡有什麼」。）
//        劇本：1. 使用者簽名登入；2. 攻擊者 1 分鐘後重放同一份簽章；3.
// 另一份簽好、但使用者還沒送出的訊息，10 分鐘後才被攻擊者送出。
// Bug 版：伺服器每次都要使用者簽同一段固定的文字。簽章對得上就讓登入，
// 所以同一份簽章可以無限次重放，也永遠不會過期。
// 修正版：伺服器每次發一個 SIWE（Sign-In with Ethereum）風格的訊息：網域、
// 錢包地址、一次性的 nonce、簽發時間與到期時間（5 分鐘）。
//        驗證時依序檢查：簽章、nonce 是不是伺服器發的、有沒有過期、nonce
// 有沒有用過；通過後讓 nonce 作廢。
package tw.harry.labs.solution.platform06deeplink

import java.util.UUID
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 登入伺服器（兩個版本各自實作）：challenge 產生要簽的訊息，login
 * 驗證並回傳結果文字。時間用「秒」
 */
private interface LoginServer {
    fun challenge(address: String, nowSeconds: Long): String
    fun login(message: String, signature: String, nowSeconds: Long): String
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, server: LoginServer) {
    val now = 1_800_000_000L

    val message = server.challenge(Wallet.ADDRESS, now)
    val signature = Wallet.sign(message)
    log("使用者簽名登入：${server.login(message, signature, now)}")
    log("攻擊者 1 分鐘後重放同一份簽章：${server.login(message, signature, now + 60)}")

    val unused = server.challenge(Wallet.ADDRESS, now)
    val stolen = Wallet.sign(unused)
    log("另一份簽好但沒送出的簽章，10 分鐘後被攻擊者送出：${server.login(unused, stolen, now + 600)}")
}

// MARK: - Bug 版
private object L05Bug {
    class Server : LoginServer {
        override fun challenge(address: String, nowSeconds: Long): String =
            // 每次都是同一段固定的文字
            "Sign in to Example Exchange"

        override fun login(
            message: String,
            signature: String,
            nowSeconds: Long,
        ): String = // 只驗簽章
            if (Wallet.verify(message, signature)) "成功" else "拒絕：簽章不正確"
    }

    suspend fun run(log: LabLog) = scenario(log, Server())
}

// MARK: - 修正版
private object L05Fixed {
    class Server : LoginServer {
        // 伺服器發出去的 nonce
        private val issuedNonces = mutableSetOf<String>()

        // 已經用過的 nonce
        private val usedNonces = mutableSetOf<String>()

        override fun challenge(address: String, nowSeconds: Long): String {
            val nonce = UUID.randomUUID().toString()
            issuedNonces += nonce
            return """
                www.example.com wants you to sign in with your wallet:
                $address
                Nonce: $nonce
                Issued At: $nowSeconds
                Expiration Time: ${nowSeconds + 300}
                """.trimIndent()
        }

        override fun login(
            message: String,
            signature: String,
            nowSeconds: Long,
        ): String {
            if (!Wallet.verify(message, signature)) return "拒絕：簽章不正確"
            val nonce = Siwe.field("Nonce", message)
            if (nonce == null ||
                nonce !in issuedNonces
            ) {
                return "拒絕：nonce 不是伺服器發的"
            }
            val expires = Siwe.field("Expiration Time", message)?.toLongOrNull()
            if (expires == null || nowSeconds > expires) return "拒絕：訊息已過期"
            // 第一次用才通過，並讓它作廢
            if (!usedNonces.add(nonce)) return "拒絕：nonce 已經用過"
            return "成功"
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Server())
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "簽章被偷走，攻擊者原封不動重送也能登入",
    question = "第 6 題：錢包 App 回跳（WalletConnect）怎麼運作？",
    goal = "簽名的訊息要防重放：同一份簽章只能用一次，而且過了時限就不能用。伺服器發的訊息裡要放什麼？",
    steps = listOf(
        "選「Bug 版」執行，看攻擊者重放簽章、以及 10 分鐘後才送出的結果。",
        "想一想：伺服器怎麼分辨「使用者本人剛簽的」和「攻擊者拿舊的簽章來送」？簽的訊息每次一樣嗎？",
        "選「修正版」執行，重放要被擋下，而且要說出被擋的原因。",
        "進階：簽章的驗證一定要在伺服器做，不能只在 App 裡驗；deep link 只負責切換 App，簽章資料不放在連結裡。",
    ),
    expectBug = "三行結果都是「成功」：包含「攻擊者 1 分鐘後重放同一份簽章：成功」與 10 分鐘後送出：成功。",
    expectFixed = "使用者簽名登入：成功；攻擊者重放：「拒絕：nonce 已經用過」；10 分鐘後送出：「拒絕：訊息已過期」。",
    file = "platform06-deeplink/L05_SiweReplay.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "使用者簽名登入：成功" !in lines -> "使用者簽名登入要成功：login 驗簽章對得上就回傳「成功」"
            "攻擊者 1 分鐘後重放同一份簽章：成功" !in lines -> "Bug 還沒重現：伺服器只驗簽章，不看 nonce、也不看時間，同一份簽章重放要成功"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "使用者簽名登入：成功" !in lines -> {
                "使用者簽名登入要成功"
            }

            "攻擊者 1 分鐘後重放同一份簽章：拒絕：nonce 已經用過" !in lines -> {
                "重放要被擋下：驗證通過後把 nonce 記進 usedNonces，第二次用同一個 nonce 就拒絕"
            }

            "另一份簽好但沒送出的簽章，10 分鐘後被攻擊者送出：拒絕：訊息已過期" !in lines -> {
                "超過 Expiration Time 的訊息要拒絕：challenge 把到期時間（簽發時間加 300 秒）放進訊息，login 比對現在時間"
            }

            else -> {
                null
            }
        }
    },
)
