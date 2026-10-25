// L03：入金的 Destination Tag 悄悄變成別的數字（把 tag 轉成 Int）
// 對應複習站：Exchange12Wallet 第 3 題「memo／destination tag 和網路選擇，
// 為什麼最容易出事？」
//
// 情境：XRP 的 Destination Tag 是 32 位元的無號整數，範圍 0 到 4294967295；
// Stellar 的 memo 可以是文字，開頭的 0 要保留。
//        API 的 addr-tag 是字串。App 把使用者輸入的 tag
// 整理成「要送出的字串」，不合法的回傳 null（畫面顯示錯誤）。
//        演練檢查 7 筆輸入：XRP 的 123456、3000000000、4294967295、
// 4294967296（超出範圍）、0、12a（不是數字），以及 Stellar 的文字 memo 007。
// Bug 版：tag 轉成 Int（Android 的 Int 是 32 位元有號整數，上限 2147483647）
// 再轉回字串：tag.toLong().toInt() 不會丟例外，
//        3000000000 悄悄溢位成 -1294967296，4294967295 變成 -1，超出範圍的
// 4294967296 變成 0，12a 變成 0（解析失敗被當成 0），
//        memo 007 變成 7。錢就入到錯的地方。
// 修正版：tag 保持字串：XRP 的檢查「只有數字、不超過 4294967295」，
// 通過就原樣送出；Stellar 的 memo 是文字，原樣保留。
package tw.harry.labs.solution.exchange12wallet

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class L03Network(val label: String) {
    Xrp("XRP"),
    Xlm("XLM"),
}

private class L03Case(
    val network: L03Network,
    val text: String,
    val expected: String?,
)

private val cases = listOf(
    L03Case(L03Network.Xrp, "123456", "123456"),
    L03Case(L03Network.Xrp, "3000000000", "3000000000"),
    L03Case(L03Network.Xrp, "4294967295", "4294967295"),
    L03Case(L03Network.Xrp, "4294967296", null),
    L03Case(L03Network.Xrp, "0", "0"),
    L03Case(L03Network.Xrp, "12a", null),
    L03Case(L03Network.Xlm, "007", "007"),
)

private fun shown(value: String?) = if (value == null) "不合法" else "\"$value\""

/** 演練流程（兩個版本共用）：7 筆輸入逐一整理，和正確答案比對 */
private fun scenario(log: LabLog, normalize: (String, L03Network) -> String?) {
    var wrong = 0
    for (c in cases) {
        val result = normalize(c.text, c.network)
        if (result == c.expected) {
            log("${c.network.label} 輸入 \"${c.text}\" → ${shown(result)} ✓")
        } else {
            wrong++
            log(
                "${c.network.label} 輸入 \"${c.text}\" → ${shown(
                    result,
                )} ✗（應該是 ${shown(c.expected)}）",
            )
        }
    }
    log("共 7 筆輸入，結果錯的有 $wrong 筆")
}

// MARK: - Bug 版
private object L03Bug {
    fun normalize(text: String, network: L03Network): String? {
        // 轉成 Int：超過 2147483647 悄悄溢位，不是數字變成 0
        val value = (text.toLongOrNull() ?: 0L).toInt()
        return value.toString()
    }

    suspend fun run(log: LabLog) = scenario(log) { text, network ->
        normalize(text, network)
    }
}

// MARK: - 修正版
private object L03Fixed {
    fun normalize(text: String, network: L03Network): String? = when (network) {
        L03Network.Xrp -> {
            val value = text.toLongOrNull()
            // 只有 0-9 的數字，而且不超過 32 位元無號整數的上限；
            // 通過就保持字串，不轉成數字再轉回來
            if (text.isNotEmpty() && text.all { it in '0'..'9' } &&
                value != null &&
                value <= 4_294_967_295L
            ) {
                text
            } else {
                null
            }
        }

        // memo 是文字：原樣保留（開頭的 0 不能丟）
        L03Network.Xlm -> {
            text
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { text, network ->
        normalize(text, network)
    }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("共 7 筆")
}?.let { Regex("(\\d+) 筆$").find(it)?.groupValues?.get(1)?.toInt() }

val l03 = Lab(
    id = "L03",
    title = "入金的 Destination Tag 悄悄變成別的數字",
    question = "第 3 題：memo／destination tag 和網路選擇，為什麼最容易出事？",
    goal = "tag 和 memo 保持字串，依網路驗證格式與範圍；轉成 32 位元整數會悄悄溢位（不丟例外），資產就入到錯的帳戶。",
    steps = listOf(
        "選「Bug 版」執行，看 7 筆輸入哪些結果錯了：3000000000 變成多少？4294967296、12a 變成多少？",
        "想一想：為什麼這個 bug 不會 crash、也不會顯示錯誤？使用者會在什麼時候才發現？",
        "選「修正版」執行，7 筆都要和正確答案一致。",
        "進階：Kotlin 的 String.toInt() 遇到超出範圍會丟 NumberFormatException，但 toLong().toInt() 不會；iOS 的 Int 在 64 位元平台是 64 位元，但模型若用 Int32／UInt32 就會有同樣問題。",
    ),
    expectBug = "XRP 輸入 \"3000000000\" → \"-1294967296\"，\"4294967295\" → \"-1\"，\"4294967296\"（超出範圍）→ \"0\"，\"12a\" → \"0\"，Stellar 的 memo \"007\" → \"7\"；123456 與 0 剛好對：「共 7 筆輸入，結果錯的有 5 筆」。",
    expectFixed = "7 筆都和正確答案一致（超出範圍與不是數字的回傳「不合法」，memo 007 原樣保留）：「共 7 筆輸入，結果錯的有 0 筆」。",
    file = "exchange12-wallet/L03_TagOverflow.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> {
                "演練沒有跑完：要用 scenario(log) { text, network -> … } 執行"
            }

            "XRP 輸入 \"123456\" → \"123456\" ✓" !in lines -> {
                "Bug 版在小的數字時要算對：123456 轉成 Int 再轉回字串還是 123456"
            }

            "XRP 輸入 \"3000000000\" → \"-1294967296\" ✗（應該是 \"3000000000\"）" !in
                lines -> {
                "Bug 還沒重現：toLong().toInt() 之後，3000000000 會悄悄溢位成 -1294967296"
            }

            n != 5 -> {
                "Bug 版應該有 5 筆結果錯（目前 $n 筆）"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { text, network -> … } 執行"
            n != 0 -> "還有 $n 筆結果錯：XRP 檢查「只有數字、不超過 4294967295」後原樣回傳字串；XLM 的 memo 原樣回傳"
            else -> null
        }
    },
)
