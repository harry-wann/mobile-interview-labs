// L04：沒選網路也能出金、XRP 沒填 tag 被自動補成 0（網路不設預設值、必填的 tag
// 不能自動補）
// 對應複習站：Exchange12Wallet 第 3 題「memo／destination tag 和網路選擇，
// 為什麼最容易出事？」
//
// 情境：出金表單有兩個容易出事的欄位：網路（同一個資產可能在多條鏈上，
// 選錯就送到錯的鏈）和 tag（XRP 這類網路入金要帶 tag，
// 漏帶資產會進交易所的共用地址）。
//        規則：網路不設預設值，沒選就不能送出；該網路必填 tag 時，
// 沒填就不能送出，除非使用者明確勾選「我確認不需要 tag」；
//        勾選後送出時 tag 是空的（null），不能自動補 0（tag 0
// 本身也是合法的值，補 0 會改變意義）；不需要 tag 的網路，tag 也是 null。
//        prepare 把表單整理成「可以送出的內容」或「問題清單」。五種表單：
// ①沒選網路 ②XRP 沒填 tag 也沒勾「不需要」③XRP 沒填 tag 但勾了「不需要」
//        ④XRP 填了 tag 5555 ⑤Bitcoin（不需要 tag）。
//
// 你要寫：
//   ① Bug 版（L04Bug.prepare）：network = form.network ?: L04Network.Bitcoin；
// tag = form.tag 是空的就用 "0"，否則用 form.tag；
//      回傳 L04Prepared.Ok(network, tag)（所有網路都帶 tag）。
//   ② 修正版（L04Fixed.prepare）：form.network 是 null →
// Problems(listOf("請選擇網路"))；network.requiresTag 而且 tag
// 去掉空白後是空的、
//      又沒有 form.tagNotNeededConfirmed → Problems(listOf("缺少 tag"))；否則
// Ok(network, 去掉空白後的 tag，空的就是 null)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange12-wallet/L04_TagRequired.kt
package tw.harry.labs.practice.exchange12wallet

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class L04Network(val label: String, val requiresTag: Boolean) {
    Bitcoin("Bitcoin", false),
    Xrp("XRP Ledger", true),
}

private class L04Form(
    val network: L04Network?,
    val tag: String = "",
    // 使用者明確勾選「我確認這筆不需要 tag」
    val tagNotNeededConfirmed: Boolean = false,
)

private sealed interface L04Prepared {
    data class Ok(val network: L04Network, val tag: String?) : L04Prepared
    data class Problems(val list: List<String>) : L04Prepared
}

private fun text(p: L04Prepared): String = when (p) {
    is L04Prepared.Ok -> "可以送出（${p.network.label}，tag ${p.tag?.let {
        "\"$it\""
    } ?: "沒有"}）"

    is L04Prepared.Problems -> "不能送出（${p.list.joinToString("、")}）"
}

private class L04Case(
    val name: String,
    val form: L04Form,
    val expected: L04Prepared,
)

private val cases = listOf(
    L04Case("沒選網路", L04Form(null), L04Prepared.Problems(listOf("請選擇網路"))),
    L04Case(
        "XRP，沒填 tag 也沒勾「不需要」",
        L04Form(L04Network.Xrp),
        L04Prepared.Problems(listOf("缺少 tag")),
    ),
    L04Case(
        "XRP，沒填 tag 但勾了「不需要」",
        L04Form(L04Network.Xrp, tagNotNeededConfirmed = true),
        L04Prepared.Ok(L04Network.Xrp, null),
    ),
    L04Case(
        "XRP，tag 5555",
        L04Form(L04Network.Xrp, tag = "5555"),
        L04Prepared.Ok(L04Network.Xrp, "5555"),
    ),
    L04Case(
        "Bitcoin，不需要 tag",
        L04Form(L04Network.Bitcoin),
        L04Prepared.Ok(L04Network.Bitcoin, null),
    ),
)

/** 演練流程（兩個版本共用）：五種表單逐一整理，和正確答案比對 */
private fun scenario(log: LabLog, prepare: (L04Form) -> L04Prepared) {
    var wrong = 0
    for (c in cases) {
        val result = prepare(c.form)
        if (result == c.expected) {
            log("${c.name} → ${text(result)} ✓")
        } else {
            wrong++
            log("${c.name} → ${text(result)} ✗（應該是：${text(c.expected)}）")
        }
    }
    log("5 種表單，整理結果錯的有 $wrong 種")
}

// MARK: - Bug 版
private object L04Bug {
    fun prepare(form: L04Form): L04Prepared {
        // TODO ①：network = form.network ?: L04Network.Bitcoin；tag = form.tag
        // 是空的就用 "0"，否則用 form.tag；回傳 L04Prepared.Ok(network, tag)
        return L04Prepared.Problems(emptyList())
    }

    suspend fun run(log: LabLog) = scenario(log) { prepare(it) }
}

// MARK: - 修正版
private object L04Fixed {
    fun prepare(form: L04Form): L04Prepared {
        // TODO ②：form.network 是 null → Problems(listOf("請選擇網路"))；tag =
        // form.tag.trim()；
        //        network.requiresTag 而且 tag 是空的、form.
        // tagNotNeededConfirmed 又是 false → Problems(listOf("缺少 tag"))；
        //        否則 Ok(network, tag.ifEmpty { null })
        return L04Prepared.Problems(emptyList())
    }

    suspend fun run(log: LabLog) = scenario(log) { prepare(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("5 種表單")
}?.let { Regex("(\\d+) 種$").find(it)?.groupValues?.get(1)?.toInt() }

val l04 = Lab(
    id = "L04",
    title = "沒選網路也能出金，XRP 沒填 tag 被補成 0",
    question = "第 3 題：memo／destination tag 和網路選擇，為什麼最容易出事？",
    goal = "網路不設預設值，沒選不能送出；必填 tag 的網路，沒填也沒明確確認時不能送出，而且不能自動補 0。",
    steps = listOf(
        "打開 practice/exchange/exchange12-wallet/L04_TagRequired.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 5 種表單：沒選網路、XRP 沒填 tag 時，Bug 版讓它們變成什麼？",
        "想一想：tag 0 是合法的值嗎？把「沒填」補成 0，伺服器和交易所收到後會當成什麼？",
        "寫 TODO ②（修正版），選「修正版」執行，5 種表單都要和規則一致。",
        "進階：地址欄和確認頁都要顯示網路名稱；地址簿的每個項目要記錄資產、網路、地址、tag 四樣；地址格式和網路明顯不相容（例如 Tron 的 T 開頭地址不可能是 EVM 地址）時直接擋下。",
    ),
    expectBug = "沒選網路、XRP 沒填 tag 也沒勾「不需要」、XRP 勾了「不需要」都變成「可以送出」並帶 tag \"0\" ✗，連 Bitcoin 也帶 tag \"0\" ✗，只有填了 5555 的是對的：「5 種表單，整理結果錯的有 4 種」。",
    expectFixed = "5 種表單都符合規則：沒選網路、XRP 缺 tag 會被擋下，勾了「不需要」的 tag 是「沒有」，不是 \"0\"：「5 種表單，整理結果錯的有 0 種」。",
    file = "exchange12-wallet/L04_TagRequired.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { form -> … } 執行"
            "XRP，tag 5555 → 可以送出（XRP Ledger，tag \"5555\"） ✓" !in lines -> "Bug 版有填的 tag 要原樣送出（5555）"
            n != 4 -> "Bug 還沒重現：網路沒選就用 Bitcoin、tag 空白就補 \"0\"，會有 4 種表單結果錯（目前 $n 種）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { form -> … } 執行"
            n != 0 -> "還有 $n 種表單結果錯：沒選網路要擋、必填 tag 沒填也沒確認要擋，勾了「不需要」時 tag 是 null（不補 0）"
            else -> null
        }
    },
)
