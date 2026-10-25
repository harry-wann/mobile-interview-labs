// L03：每來一筆行情，列表上每一列都重新綁定一次（notifyDataSetChanged 與 DiffUtil 加 payload）
// 對應複習站：Advanced02Jank 第 5 題「Android 列表捲動卡頓的常見原因是什麼？」
//
// 情境：行情列表同時顯示 12 列。每次行情更新，只有前 3 檔的價格文字變了，其餘 17 檔不變。共更新 10 次。
//        RecyclerView 每次通知「這一列要重新綁定」，就會呼叫一次 onBindViewHolder（在 UI thread 上）。
//        演練用 FakeRecycler 實作 ListUpdateCallback，記下 adapter 通知了多少次「重新綁定」，等於 onBindViewHolder 被呼叫的次數。
//
// 你要寫：
//   ① Bug 版：每次更新都呼叫 recycler.notifyDataSetChanged()。
//   ② 修正版：用 DiffUtil.calculateDiff（DiffUtil.Callback）比對新舊清單，再 dispatchUpdatesTo(recycler)：
//      areItemsTheSame 看交易對代號 symbol，areContentsTheSame 看整份內容，getChangePayload 在「只有價格文字不同」時回傳 PRICE。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced02-jank/L03_DiffPayload.kt
package tw.harry.labs.practice.advanced02jank

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val SYMBOLS = 20
private const val VISIBLE = 12
private const val TICKS = 10
private const val PRICE = "price"

/** 畫面用的資料：交易對、價格文字、漲跌文字（兩個版本共用） */
private data class TickUi(val symbol: String, val priceText: String, val changeText: String)

private fun initialList(): List<TickUi> = List(SYMBOLS) { TickUi("COIN$it", "1,000,%03d".format(it), "+0.0%") }

/** 第 tick 次更新：前 3 檔的價格文字變了，其餘不變（兩個版本共用） */
private fun update(list: List<TickUi>, tick: Int): List<TickUi> =
    list.mapIndexed { i, ui -> if (i < 3) ui.copy(priceText = "1,%03d,%03d".format(tick, i)) else ui }

/** 縮小版的 RecyclerView：記下 onBindViewHolder 被呼叫幾次（兩個版本共用） */
private class FakeRecycler : ListUpdateCallback {
    var binds = 0
        private set
    var partialBinds = 0          // 其中只更新價格欄位（有 payload）的次數
        private set
    var structural = 0            // 新增、移除、移動的次數
        private set

    /** notifyDataSetChanged()：RecyclerView 不知道哪裡變了，所有可見的項目全部重新綁定 */
    fun notifyDataSetChanged() {
        binds += VISIBLE
    }

    override fun onChanged(position: Int, count: Int, payload: Any?) {
        binds += count
        if (payload != null) partialBinds += count
    }

    override fun onInserted(position: Int, count: Int) {
        structural += count
    }

    override fun onRemoved(position: Int, count: Int) {
        structural += count
    }

    override fun onMoved(fromPosition: Int, toPosition: Int) {
        structural++
    }
}

/** 演練流程（兩個版本共用）：行情更新 10 次，每次把新清單交給 adapter */
private fun scenario(log: LabLog, recycler: FakeRecycler, submit: (old: List<TickUi>, new: List<TickUi>) -> Unit) {
    var list = initialList()
    repeat(TICKS) { i ->
        val next = update(list, i + 1)
        submit(list, next)
        list = next
    }
    log("行情更新 $TICKS 次，列表一共重新綁定 ${recycler.binds} 列，其中只更新價格欄位的有 ${recycler.partialBinds} 列")
    log("新增、移除、移動：${recycler.structural} 次")
}

// MARK: - Bug 版
private object L03Bug {
    fun run(log: LabLog) {
        val recycler = FakeRecycler()
        scenario(log, recycler) { _, _ ->
            // TODO ①：呼叫 recycler.notifyDataSetChanged()：不知道哪裡變了，全部重新綁定
        }
    }
}

// MARK: - 修正版
private object L03Fixed {
    fun run(log: LabLog) {
        val recycler = FakeRecycler()
        scenario(log, recycler) { old, new ->
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = new.size
                // TODO ②-1：是不是同一檔？比較 old[o].symbol 和 new[n].symbol
                override fun areItemsTheSame(o: Int, n: Int) = false
                // TODO ②-2：內容有沒有變？比較 old[o] 和 new[n]
                override fun areContentsTheSame(o: Int, n: Int) = false
                // TODO ②-3：只有價格文字不同、其他欄位都相同（old[o].copy(priceText = new[n].priceText) == new[n]）回傳 PRICE，否則 null
                override fun getChangePayload(o: Int, n: Int): Any? = null
            })
            // TODO ②-4：result.dispatchUpdatesTo(recycler)：只通知有變的那幾列
        }
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "每來一筆行情，每一列都重新綁定",
    question = "第 5 題：Android 列表捲動卡頓的常見原因是什麼？",
    goal = "高頻更新時，只重新綁定真的有變的列，而且只更新變的欄位（payload），不要 notifyDataSetChanged()。先寫出全部重新綁定的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced02-jank/L03_DiffPayload.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看「列表一共重新綁定幾列」：10 次更新、每次可見 12 列，共 120 次。",
        "寫 TODO ②，選「修正版」執行：只有變的 3 列被重新綁定，10 次共 30 次，而且 30 次都只更新價格欄位，沒有新增、移除、移動。",
        "進階：真的 App 用 Perfetto 錄捲動，選一格超時的畫面，在 UI thread 的 Choreographer#doFrame 底下找 `RV OnBindView` 的次數與耗時；Bug 版會看到每一格畫面都有一整排。",
        "想一想：把 areItemsTheSame 改成永遠回傳 false 會怎樣？（列表會被當成全部移除再全部新增，動畫和捲動位置都會壞掉）",
    ),
    expectBug = "行情更新 10 次，列表一共重新綁定 120 列，其中只更新價格欄位的有 0 列。",
    expectFixed = "行情更新 10 次，列表一共重新綁定 30 列，其中只更新價格欄位的有 30 列；新增、移除、移動：0 次。",
    file = "advanced02-jank/L03_DiffPayload.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val binds = a02Number(lines, "行情更新 10 次，列表一共重新綁定 ")?.toInt()
        when {
            binds == null -> "演練沒有跑完"
            binds == 0 -> "沒有任何列被重新綁定：每次更新都要呼叫 recycler.notifyDataSetChanged()"
            binds < 100 -> "只重新綁定了 $binds 列：Bug 版每次都 notifyDataSetChanged()，10 次 × 12 列 = 120"
            else -> null
        }
    },
    checkFixed = { lines ->
        val binds = a02Number(lines, "行情更新 10 次，列表一共重新綁定 ")?.toInt()
        val structural = a02Number(lines, "新增、移除、移動：")?.toInt()
        val partial = lines.firstOrNull { it.contains("其中只更新價格欄位的有 ") }
            ?.substringAfter("其中只更新價格欄位的有 ")?.takeWhile { it.isDigit() }?.toIntOrNull()
        when {
            binds == null || structural == null || partial == null -> "演練沒有跑完"
            binds == 0 -> "沒有任何列被重新綁定：calculateDiff 之後要 dispatchUpdatesTo(recycler)"
            structural > 0 -> "出現了 $structural 次新增、移除或移動：areItemsTheSame 要用交易對代號（symbol）判斷是不是同一檔"
            binds != 30 -> "重新綁定了 $binds 列，應該只有變的 3 列 × 10 次 = 30：areContentsTheSame 要比整份內容（old == new）"
            partial != 30 -> "30 列都應該只更新價格欄位：getChangePayload 在只有價格不同時要回傳 PRICE（不是 null）"
            else -> null
        }
    },
)
