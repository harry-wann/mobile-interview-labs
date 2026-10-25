// L02：大小寫混用的 bc1 地址被當成合法（Bech32 地址不能大小寫混用）
// 對應複習站：Exchange12Wallet 第 2 題「加密資產的地址怎麼驗證？Base58Check、
// Bech32、EIP-55 是什麼？」
//
// 情境：Bech32（SegWit）與 Bech32m（Taproot）地址以 bc1 開頭，有多項式校驗碼。
// 規則：全小寫或全大寫都合法（QR code 常用全大寫），
//        **大小寫混用則無效**。校驗碼的計算（E12.bech32Kind，
// 要傳入全小寫的字串，同時支援 Bech32 與 Bech32m）已經寫好在 Exchange12Support.
// kt，
//        這題只看「大小寫」。演練檢查 6 個地址（都是 BIP 173／350
// 文件的公開測試地址）：全小寫的合法地址、全大寫的同一個地址、
//        Taproot 的 Bech32m 地址、第一個字母改成大寫的混用地址、
// 中間一個字母改成大寫的混用地址、校驗碼被改掉的地址。
// Bug 版：先 lowercase() 再驗證校驗碼：看起來很周到（「大小寫都接受」），
// 卻把大小寫混用的地址也當成合法了
//        （轉成小寫之後，校驗碼是對的）。
// 修正版：先檢查「全小寫」或「全大寫」，混用就直接無效；
// 通過了再轉小寫驗證校驗碼。
package tw.harry.labs.solution.exchange12wallet

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L02Case(
    val name: String,
    val address: String,
    val expected: Boolean,
)

private val cases = listOf(
    L02Case("全小寫的合法地址", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", true),
    L02Case("同一個地址，全大寫", "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", true),
    L02Case(
        "Taproot 的 Bech32m 地址",
        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0",
        true,
    ),
    L02Case("第一個字母改成大寫", "Bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", false),
    L02Case("中間一個字母改成大寫", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8F3t4", false),
    L02Case("校驗碼被改掉", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5", false),
)

/** 演練流程（兩個版本共用）：6 個地址逐一驗證，和正確答案比對 */
private fun scenario(log: LabLog, isValid: (String) -> Boolean) {
    var wrong = 0
    for (c in cases) {
        val result = isValid(c.address)
        val text = if (result) "合法" else "無效"
        if (result == c.expected) {
            log("${c.name}：$text ✓")
        } else {
            wrong++
            log("${c.name}：$text ✗（應該${if (c.expected) "合法" else "無效"}）")
        }
    }
    log("6 個地址，驗證結果錯的有 $wrong 個")
}

// MARK: - Bug 版
private object L02Bug {
    fun isValid(address: String): Boolean {
        // 轉小寫就驗證：大小寫混用的也過了
        return E12.bech32Kind(address.lowercase()) != null
    }

    suspend fun run(log: LabLog) = scenario(log) { isValid(it) }
}

// MARK: - 修正版
private object L02Fixed {
    fun isValid(address: String): Boolean {
        // 大小寫混用：無效
        if (address != address.lowercase() &&
            address != address.uppercase()
        ) {
            return false
        }
        return E12.bech32Kind(address.lowercase()) != null
    }

    suspend fun run(log: LabLog) = scenario(log) { isValid(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("6 個地址")
}?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l02 = Lab(
    id = "L02",
    title = "大小寫混用的 bc1 地址被當成合法",
    question = "第 2 題：加密資產的地址怎麼驗證？Base58Check、Bech32、EIP-55 是什麼？",
    goal = "Bech32 地址全小寫或全大寫都合法，大小寫混用則無效；不能先轉小寫再驗證校驗碼，就把混用的地址放行。",
    steps = listOf(
        "選「Bug 版」執行，看 6 個地址哪些判斷錯了。",
        "想一想：大小寫混用的地址，轉成小寫之後校驗碼為什麼是對的？規格為什麼還要禁止混用？",
        "選「修正版」執行，6 個地址都要判斷對（全大寫和 Taproot 的 Bech32m 都要合法）。",
        "進階：EIP-55 的 Ethereum 地址剛好相反，大小寫混合是校驗碼；全小寫代表沒有校驗碼。兩種地址不能用同一套「大小寫」邏輯。",
    ),
    expectBug = "「第一個字母改成大寫：合法 ✗」「中間一個字母改成大寫：合法 ✗」，其他四個判斷對（全大寫、Taproot 都合法，校驗碼被改掉的無效）：「6 個地址，驗證結果錯的有 2 個」。",
    expectFixed = "6 個地址都判斷對：「6 個地址，驗證結果錯的有 0 個」。",
    file = "exchange12-wallet/L02_Bech32Case.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { isValid(it) } 執行"
            "同一個地址，全大寫：合法 ✓" !in lines -> "Bug 版要把地址轉成小寫再驗證，所以全大寫的合法地址要判成合法"
            n != 2 -> "Bug 還沒重現：轉小寫就驗證，兩個大小寫混用的地址都會被當成合法（目前錯 $n 個）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { isValid(it) } 執行"
            n != 0 -> "還有 $n 個地址判斷錯：混用大小寫先判無效；全小寫或全大寫才轉小寫驗證校驗碼"
            else -> null
        }
    },
)
