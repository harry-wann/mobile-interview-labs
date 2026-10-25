// L08：出庫送出去才被伺服器退回（地址還在審核中，餘額也不夠付手續費）
// 對應複習站：Exchange01Orders 第 6 題「積立、貸暗号資産、
// 入出金和入出庫（含トラベルルール）是什麼？」
//
// 情境：出庫（把幣轉到外部地址）要先登錄地址並通過審核（承認）才能用；
// 轉帳還要付網路手續費。
//        使用者有 0.1 BTC 可用，網路手續費 0.0005 BTC。演練依序試六筆出庫：
//        ① 已承認的地址、0.05 BTC（可以）② 已承認的地址、0.1
// BTC（餘額不夠付手續費）③ 審査中的地址 ④ 否認的地址
//        ⑤ 沒有登錄過的地址 ⑥ 已承認的地址、0.5 BTC（超過餘額）。
//        App 要在送出前先預檢，擋得住的就不要送到伺服器，並說明原因。
// （地址的審核與手續費是演練的假設；實際規則以交易所為準。）
// Bug 版：預檢只看「地址有沒有登錄過」和「數量不超過可用餘額」。⑤⑥ 擋得下來，
// 但審査中、否認的地址都放行，0.1 BTC 也放行（沒算手續費），三筆被伺服器退回。
// 修正版：地址的狀態必須是承認，而且「數量 + 手續費」不能超過可用餘額；
// 不符合在 App 端擋下，不送出。
package tw.harry.labs.solution.exchange01orders

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class Review(val text: String) {
    REVIEWING("審査中"),
    APPROVED("承認"),
    REJECTED("否認"),
}

private val addresses = mapOf(
    "bc1q-approved" to Review.APPROVED,
    "bc1q-reviewing" to Review.REVIEWING,
    "bc1q-rejected" to Review.REJECTED,
)
private val available = e01("0.1")
private val networkFee = e01("0.0005")

/** 假伺服器：不管 App 預檢有沒有擋，伺服器一定會再驗證一次 */
private fun serverCheck(address: String, amount: BigDecimal): String? = when {
    addresses[address] != Review.APPROVED -> "地址還沒通過審核"
    amount + networkFee > available -> "餘額不足以支付手續費"
    else -> null
}

/** 演練流程（兩個版本共用）：六筆出庫依序預檢、送出 */
private fun scenario(log: LabLog, precheck: (String, BigDecimal) -> String?) {
    val requests = listOf(
        "bc1q-approved" to "0.05",
        "bc1q-approved" to "0.1",
        "bc1q-reviewing" to "0.01",
        "bc1q-rejected" to "0.01",
        "bc1q-unknown" to "0.01",
        "bc1q-approved" to "0.5",
    )
    var sent = 0
    var refused = 0
    for ((address, text) in requests) {
        val amount = e01(text)
        val reason = precheck(address, amount)
        if (reason != null) {
            log("出庫 ${e01Text(amount)} BTC → $address：App 預檢擋下（$reason）")
            continue
        }
        sent++
        val rejected = serverCheck(address, amount)
        if (rejected != null) {
            refused++
            log("出庫 ${e01Text(amount)} BTC → $address：預檢放行，伺服器退回（$rejected）")
        } else {
            log("出庫 ${e01Text(amount)} BTC → $address：預檢放行，伺服器受理")
        }
    }
    log("送到伺服器的出庫 $sent 筆，被伺服器退回 $refused 筆")
}

// MARK: - Bug 版
private object L08Bug {
    fun precheck(address: String, amount: BigDecimal): String? {
        // 只看有沒有登錄過
        if (address !in addresses) return "地址沒有登錄過"
        // 沒有把手續費算進去
        if (amount > available) return "餘額不足"
        return null
    }

    suspend fun run(log: LabLog) = scenario(log, ::precheck)
}

// MARK: - 修正版
private object L08Fixed {
    fun precheck(address: String, amount: BigDecimal): String? {
        val review = addresses[address] ?: return "地址沒有登錄過"
        if (review !=
            Review.APPROVED
        ) {
            return "地址是「${review.text}」，要通過審核（承認）才能出庫"
        }
        if (amount + networkFee >
            available
        ) {
            return "數量加手續費 ${e01Text(networkFee)} 超過可用餘額"
        }
        return null
    }

    suspend fun run(log: LabLog) = scenario(log, ::precheck)
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "出庫送出去才被退回，地址還在審核中",
    question = "第 6 題：積立、貸暗号資産、入出金和入出庫（含トラベルルール）是什麼？",
    goal = "出庫前，App 先預檢：地址要通過審核、數量加手續費不能超過可用餘額。擋得住的不要送到伺服器，並告訴使用者原因。",
    steps = listOf(
        "選「Bug 版」執行，看六筆出庫各自的結果、有幾筆被伺服器退回。",
        "想一想：地址「審査中」時，預檢只檢查「有登錄過」夠嗎？使用者轉出 0.1 BTC，還剩多少可以付手續費？",
        "選「修正版」執行，只有第一筆送到伺服器。",
        "進階：地址審核是非同步的，畫面要畫出「審査中、承認、否認」三種狀態，並用推播通知結果；預檢只是提早擋錯，最終以伺服器為準。",
    ),
    expectBug = "沒登錄過的地址、超過餘額的那兩筆被擋下，其餘四筆都放行，其中三筆被伺服器退回：「送到伺服器的出庫 4 筆，被伺服器退回 3 筆」。",
    expectFixed = "只有已承認地址的 0.05 BTC 送到伺服器，其餘五筆在 App 就被擋下並說明原因：「送到伺服器的出庫 1 筆，被伺服器退回 0 筆」。",
    file = "exchange01-orders/L08_WithdrawPrecheck.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("送到伺服器的出庫")
            } -> "演練沒有跑完：要用 scenario(log, ::precheck) 執行"

            "送到伺服器的出庫 4 筆，被伺服器退回 3 筆" !in lines -> "Bug 還沒重現：預檢只檢查地址有登錄過、數量不超過 available，審査中、否認的地址和 0.1 BTC 會放行，四筆送到伺服器、三筆被退回"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("送到伺服器的出庫")
            } -> "演練沒有跑完：要用 scenario(log, ::precheck) 執行"

            "送到伺服器的出庫 1 筆，被伺服器退回 0 筆" !in lines -> "預檢要擋下：地址不是承認（APPROVED），以及數量加手續費超過 available"

            else -> null
        }
    },
)
