// L02：只成交了一部分，畫面卻顯示「已成交」，取消時還多算了已經用掉的錢（部分約定）
// 對應複習站：Exchange01Orders 第 2 題「訂單簿是什麼？「約定」「部分約定」「maker／taker」是什麼意思？」
//
// 情境：使用者送出指値買單：買 0.5 BTC，價格 10,005,000 円。賣板最低的 0.2 BTC 賣 10,000,000 円，次低的 0.3 BTC 賣 10,010,000 円（比委託價貴）。
//        所以立刻成交 0.2 BTC，剩下的 0.3 BTC 以 10,005,000 円掛在買板上等人來賣。使用者看了一下，按「取消」。
// Bug 版：只要成交過一點點，就把狀態當成「已成交」（Filled）；取消時預估釋放的金額用「整張單的數量 × 委託價」算，連已經成交用掉的部分也算進去。
// 修正版：成交量等於委託量才是 Filled，有成交但還沒滿是 PartiallyFilled；取消只會取消「剩餘數量」，釋放的金額只算剩餘數量 × 委託價。
package tw.harry.labs.solution.exchange01orders

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class Status(val text: String) {
    NEW("New"),
    PARTIALLY_FILLED("PartiallyFilled"),
    FILLED("Filled"),
}

private class Order(
    val qty: BigDecimal,                    // 委託數量
    val price: BigDecimal,                  // 委託價
    var filled: BigDecimal = BigDecimal.ZERO,   // 已成交數量
)

/** 演練流程（兩個版本共用）：送單、立刻成交一部分、顯示狀態、按取消 */
private fun scenario(log: LabLog, status: (Order) -> Status, releaseOnCancel: (Order) -> BigDecimal) {
    val book = E01Book(listOf(E01Level(e01("10000000"), e01("0.2")), E01Level(e01("10010000"), e01("0.3"))))
    val order = Order(e01("0.5"), e01("10005000"))
    log("送出指値買單：${e01Text(order.qty)} BTC，委託價 ${e01Text(order.price)}")
    val fills = book.takeAsks(order.qty, limit = order.price)
    order.filled = fills.fold(BigDecimal.ZERO) { sum, fill -> sum + fill.qty }
    log("立刻成交 ${e01Text(order.filled)} BTC，成交價是板上的價格 ${fills.joinToString("、") { e01Text(it.price) }}")
    log("狀態：${status(order).text}，已成交 ${e01Text(order.filled)}／剩餘 ${e01Text(order.qty - order.filled)}")
    log("按下取消，取消後釋放 ${e01Text(releaseOnCancel(order))} 円")
}

// MARK: - Bug 版
private object L02Bug {
    fun status(order: Order): Status =
        if (order.filled.signum() > 0) Status.FILLED else Status.NEW     // 有成交就當成「已成交」

    fun release(order: Order): BigDecimal =
        order.qty * order.price                                          // 整張單的數量 × 委託價

    suspend fun run(log: LabLog) = scenario(log, ::status, ::release)
}

// MARK: - 修正版
private object L02Fixed {
    fun status(order: Order): Status = when {
        order.filled.signum() == 0 -> Status.NEW
        order.filled < order.qty -> Status.PARTIALLY_FILLED              // 成交量滿了才是 Filled
        else -> Status.FILLED
    }

    fun release(order: Order): BigDecimal =
        (order.qty - order.filled) * order.price                         // 只有還沒成交的剩餘數量會被取消

    suspend fun run(log: LabLog) = scenario(log, ::status, ::release)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "成交一半的單顯示「已成交」，取消時多算了錢",
    question = "第 2 題：訂單簿是什麼？「約定」「部分約定」「maker／taker」是什麼意思？",
    goal = "一張單可能只成交一部分。狀態要顯示 PartiallyFilled，取消只會取消剩餘數量，釋放的金額也只算剩餘的部分。",
    steps = listOf(
        "選「Bug 版」執行，看狀態和「取消後釋放」的金額。",
        "想一想：0.2 BTC 已經成交、錢已經花掉了，取消時還能拿回來嗎？狀態是「已成交」嗎？",
        "選「修正版」執行，比較狀態和釋放金額。",
        "進階：成交價是板上的 10,000,000 円，不是委託價 10,005,000 円（委託價只是上限）。",
    ),
    expectBug = "「狀態：Filled，已成交 0.2／剩餘 0.3」「取消後釋放 5002500 円」（把已經成交的 0.2 BTC 也算進去）。",
    expectFixed = "「狀態：PartiallyFilled，已成交 0.2／剩餘 0.3」「取消後釋放 3001500 円」（只有剩餘 0.3 × 10,005,000）。",
    file = "exchange01-orders/L02_PartialFill.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("立刻成交 0.2 BTC") } -> "演練沒有跑完：要用 scenario(log, ::status, ::release) 執行"
            "狀態：Filled，已成交 0.2／剩餘 0.3" !in lines -> "Bug 還沒重現：只要有成交（filled > 0），狀態就回傳 FILLED"
            "按下取消，取消後釋放 5002500 円" !in lines -> "Bug 還沒重現：釋放金額用整張單的 qty × price 算（5002500）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("立刻成交 0.2 BTC") } -> "演練沒有跑完：要用 scenario(log, ::status, ::release) 執行"
            "狀態：PartiallyFilled，已成交 0.2／剩餘 0.3" !in lines -> "狀態應該是 PartiallyFilled：有成交但 filled 還小於 qty"
            "按下取消，取消後釋放 3001500 円" !in lines -> "釋放金額只算剩餘數量：(qty - filled) × price = 3001500"
            else -> null
        }
    },
)
