// L02：下單那一瞬間，資產頁的總額閃過少了 300,000
// 円（可用餘額與凍結餘額分開更新、分開通知）
// 對應複習站：Design03OrderAssets 第 6 題「資產的數字要怎麼算才不會出錯？」
// （所有畫面共用 BalanceStore，而且一次換一份完整的資產）
//
// 情境：資產頁顯示「可用」「凍結」和「總額 = 可用 + 凍結」。BalanceStore
// 一開始是可用 1,000,000、凍結 0。
//       使用者下了 300,000 円的限價買單之後，查詢資產的回覆是一份清單，
// 每一筆是「類型＋數量」：
//       trade（可用）700,000 在前，frozen（凍結）300,000 在後（Huobi 系 API
// 的資產查詢常見的形式：可用和凍結分成不同筆，順序和格式以交易所的 API
// 文件為準）。
//       BalanceStore 每次有變動，就通知畫面更新。
// 看畫面在這個過程中出現過哪些總額。
// Bug 版：Store 有 available、frozen 兩個欄位，各自一改就通知畫面。
// 清單一筆一筆套用：先把可用改成 700,000（這時凍結還是 0，總額 700,000），
//        再把凍結改成 300,000（總額 1,000,000）。畫面看到總額先掉到 700,000，
// 再彈回 1,000,000。
// 修正版：先把整份清單套用到一份新的資產上（available、frozen 一起），
// 全部套用完才換掉 Store 裡的資產，通知畫面一次。
package tw.harry.labs.solution.design03orderassets

import kotlin.properties.Delegates
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L02Item(val kind: Kind, val amount: Int) {
    enum class Kind { TRADE, FROZEN }
}

/** BalanceStore：收到一份資產清單 */
private interface L02BalanceStore {
    fun apply(items: List<L02Item>)
}

/** 演練流程（兩個版本共用）：Store 一通知，資產頁就記下當時的總額 */
private fun scenario(
    log: LabLog,
    makeStore: (
        onPublish: (available: Int, frozen: Int) -> Unit,
    ) -> L02BalanceStore,
) {
    val seen = mutableListOf<Int>()
    val store = makeStore { available, frozen ->
        seen += available + frozen
        log(
            "資產頁更新：可用 ${d03Yen(
                available,
            )} ＋ 凍結 ${d03Yen(frozen)} ＝ 總額 ${d03Yen(available + frozen)}",
        )
    }
    log("查詢資產的回覆：trade（可用）700,000、frozen（凍結）300,000；真正的總額一直是 1,000,000")
    store.apply(
        listOf(
            L02Item(L02Item.Kind.TRADE, 700_000),
            L02Item(L02Item.Kind.FROZEN, 300_000),
        ),
    )
    log(
        "最後：資產頁一共更新 ${seen.size} 次，看到的總額依序是 ${if (seen.isEmpty()) {
            "（沒有）"
        } else {
            seen.joinToString(
                " → ",
            ) {
                d03Yen(it)
            }
        }}",
    )
}

// MARK: - Bug 版
private object L02Bug {
    class Store(private val onPublish: (Int, Int) -> Unit) : L02BalanceStore {
        // 欄位一改就通知畫面
        private var available: Int by Delegates.observable(1_000_000) {
            _,
            _,
            _,
            ->
            onPublish(available, frozen)
        }

        // 欄位一改就通知畫面
        private var frozen: Int by Delegates.observable(0) { _, _, _ ->
            onPublish(available, frozen)
        }

        override fun apply(items: List<L02Item>) {
            // 一筆一筆套用
            for (item in items) {
                when (item.kind) {
                    L02Item.Kind.TRADE -> available = item.amount
                    L02Item.Kind.FROZEN -> frozen = item.amount
                }
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { Store(it) }
}

// MARK: - 修正版
private object L02Fixed {
    class Store(private val onPublish: (Int, Int) -> Unit) : L02BalanceStore {
        // 一份完整的資產：(可用, 凍結)
        private var balance = 1_000_000 to 0

        override fun apply(items: List<L02Item>) {
            // 先在一份新的資產上套用
            var available = balance.first
            var frozen = balance.second
            for (item in items) {
                when (item.kind) {
                    L02Item.Kind.TRADE -> available = item.amount
                    L02Item.Kind.FROZEN -> frozen = item.amount
                }
            }
            // 全部套用完，一次換掉
            balance = available to frozen
            // 通知畫面一次
            onPublish(balance.first, balance.second)
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { Store(it) }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "下單那一瞬間，總資產閃過少了 30 萬",
    question = "第 6 題：資產的數字要怎麼算才不會出錯？",
    goal = "可用和凍結是同一份資產的兩個部分，要一起換、一起通知畫面。分開更新，畫面會看到「可用已經扣掉、凍結還沒加上」的中間狀態，總額短暫少一截。",
    steps = listOf(
        "選「Bug 版」執行，看資產頁更新了幾次、每次的總額是多少。",
        "想一想：真正的總額從頭到尾都是 1,000,000，為什麼畫面看到過 700,000？（可用先改、凍結後改，中間畫面就被通知了。）",
        "選「修正版」執行：只更新 1 次，總額 1,000,000。",
        "想一想：用兩個 StateFlow 分別放可用和凍結，再用 combine 組成總額，會有一樣的問題嗎？（可能會：兩個 Flow 各自更新時，combine 各算一次，中間就可能出現沒對齊的組合。放在同一個 data class 的一個 StateFlow 裡，一次換一份就不會。）",
    ),
    expectBug = "資產頁更新 2 次，總額先掉到 700,000 再彈回 1,000,000：「最後：資產頁一共更新 2 次，看到的總額依序是 700,000 → 1,000,000」。",
    expectFixed = "資產頁只更新 1 次，總額一直是 1,000,000：「最後：資產頁一共更新 1 次，看到的總額依序是 1,000,000」。",
    file = "design03-order-assets/L02_AtomicBalance.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> {
                "演練沒有跑完：要用 scenario(log, makeStore) 執行"
            }

            last.startsWith("最後：資產頁一共更新 0 次") -> {
                "apply 還沒套用清單：一筆一筆把 TRADE 寫進 available、FROZEN 寫進 frozen，欄位一改要呼叫 onPublish"
            }

            last != "最後：資產頁一共更新 2 次，看到的總額依序是 700,000 → 1,000,000" -> {
                "Bug 還沒重現：available 與 frozen 各自一改就 onPublish（Delegates.observable），apply 一筆一筆改，畫面會看到總額先掉到 700,000"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> {
                "演練沒有跑完：要用 scenario(log, makeStore) 執行"
            }

            last.startsWith("最後：資產頁一共更新 0 次") -> {
                "apply 還沒套用清單：在區域變數上套用每一筆，換掉 balance 之後呼叫 onPublish 一次"
            }

            last != "最後：資產頁一共更新 1 次，看到的總額依序是 1,000,000" -> {
                "先在區域變數 available、frozen 上套用整份清單，全部套完才 balance = available to frozen，再呼叫一次 onPublish"
            }

            else -> {
                null
            }
        }
    },
)
