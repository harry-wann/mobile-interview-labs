// L02：價格每秒跳好幾次，連不相干的下單表單也跟著重組（UiState 裡塞了行情）
// 對應複習站：Kotlin09ComposeArchitecture 第 2 題「UDF 和 MVI 是什麼？怎麼取捨？」
//
// 情境：下單畫面有表單（OrderForm，只用到數量 qty）和即時價格（PriceText）。價格更新 10 次（每 30 ms 一次），qty 整個過程都沒變。
//        演練計算 OrderForm 與 PriceText 各重組了幾次。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合與重組。
//
// 你要寫：
//   ① Bug 版（L02Bug.OrderScreen）：vm.state 同時有 qty 和 price，collectAsState 後把整個 state 傳給 OrderForm，再把 state.price 傳給 PriceText。
//   ② 修正版（L02Fixed.OrderScreen、PriceText）：表單只傳 form state；價格是獨立的 StateFlow，PriceText 自己 collectAsState。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin09-compose-architecture/L02_BigUiState.kt
package tw.harry.labs.practice.kotlin09composearchitecture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 記下 OrderForm、PriceText 各執行了幾次（包含第一次組合） */
private class L02Counts {
    val form = AtomicInteger()
    val price = AtomicInteger()
}

/** 演練流程（兩個版本共用）：組合畫面 → 價格每 30 ms 更新一次，共 10 次 → 看兩個 Composable 各重組了幾次 */
private suspend fun scenario(log: LabLog, counts: L02Counts, updatePrice: (Long) -> Unit, screen: @Composable () -> Unit) {
    withHeadlessCompose {
        setContent { screen() }
        settle()
        repeat(10) {
            delay(30)
            updatePrice(100_001L + it)
            settle()
        }
    }
    log("價格更新 10 次：OrderForm 執行了 ${counts.form.get()} 次")
    log("價格更新 10 次：PriceText 執行了 ${counts.price.get()} 次")
}

// MARK: - Bug 版
private object L02Bug {
    /** 整個畫面只有一個 UiState：行情也在裡面 */
    data class OrderUiState(val qty: String, val price: Long)

    class OrderViewModel {
        val state = MutableStateFlow(OrderUiState(qty = "0.5", price = 100_000L))
    }

    @Composable
    fun OrderScreen(vm: OrderViewModel, counts: L02Counts) {
        // TODO ①：val state by vm.state.collectAsState()；OrderForm(state, counts) 把整個 state 傳下去；PriceText(state.price, counts)
    }

    @Composable
    fun OrderForm(state: OrderUiState, counts: L02Counts) {
        SideEffect { counts.form.incrementAndGet() }
        HeadlessText("數量 ${state.qty}")                   // 只用到 qty
    }

    @Composable
    fun PriceText(price: Long, counts: L02Counts) {
        SideEffect { counts.price.incrementAndGet() }
        HeadlessText("價格 $price")
    }

    suspend fun run(log: LabLog) {
        val vm = OrderViewModel()
        val counts = L02Counts()
        scenario(log, counts, { price -> vm.state.value = vm.state.value.copy(price = price) }) { OrderScreen(vm, counts) }
    }
}

// MARK: - 修正版
private object L02Fixed {
    /** 表單的 UiState 只有表單自己的欄位 */
    data class OrderFormState(val qty: String)

    class OrderViewModel {
        val form = MutableStateFlow(OrderFormState(qty = "0.5"))
        val price = MutableStateFlow(100_000L)              // 每秒更新很多次的行情，獨立成自己的 Flow
    }

    @Composable
    fun OrderScreen(vm: OrderViewModel, counts: L02Counts) {
        // TODO ②-1：val form by vm.form.collectAsState()；OrderForm(form, counts) 只傳表單的 state
        // TODO ②-2：PriceText(vm.price, counts) 只傳 Flow，不要在這裡讀價格
    }

    @Composable
    fun OrderForm(form: OrderFormState, counts: L02Counts) {
        SideEffect { counts.form.incrementAndGet() }
        HeadlessText("數量 ${form.qty}")
    }

    @Composable
    fun PriceText(price: StateFlow<Long>, counts: L02Counts) {
        // TODO ②-3：val value by price.collectAsState()（讀價格只發生在這個小函式裡）；
        //          SideEffect { counts.price.incrementAndGet() }；HeadlessText("價格 $value")
    }

    suspend fun run(log: LabLog) {
        val vm = OrderViewModel()
        val counts = L02Counts()
        scenario(log, counts, { price -> vm.price.value = price }) { OrderScreen(vm, counts) }
    }
}

// MARK: - 演練設定
private fun count(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.removeSuffix(" 次")?.toIntOrNull()

private const val FORM = "價格更新 10 次：OrderForm 執行了 "
private const val PRICE = "價格更新 10 次：PriceText 執行了 "

val l02 = Lab(
    id = "L02",
    title = "價格一跳，不相干的表單也重組",
    question = "第 2 題：UDF 和 MVI 是什麼？怎麼取捨？",
    goal = "UiState 裡如果放了每秒更新很多次的行情，整個 state 傳給表單，表單就會跟著重組。看它重組幾次，再把行情拆成獨立的 Flow。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin09-compose-architecture/L02_BigUiState.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「OrderForm 執行了 N 次」的 N（它沒有用到價格）。",
        "寫 TODO ②（修正版），選「修正版」執行，確認 OrderForm 只執行 1 次，PriceText 仍然跟著價格更新。",
        "想一想：MVI 的單一狀態有什麼好處？為什麼這裡要例外？（好處是容易重現與測試；但每秒更新很多次的欄位放進去，會拖累整個畫面）",
    ),
    expectBug = "「OrderForm 執行了 11 次」：第一次組合，加上價格更新 10 次各 1 次。",
    expectFixed = "「OrderForm 執行了 1 次」，「PriceText 執行了 11 次」。",
    file = "kotlin09-compose-architecture/L02_BigUiState.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val form = count(lines, FORM) ?: 0
        when {
            form == 0 -> "OrderForm 沒有執行：OrderScreen 要呼叫 OrderForm(state, counts)"
            form < 5 -> "OrderForm 只執行了 $form 次，沒有跟著價格重組：Bug 版要把整個 state（含 price）傳給 OrderForm"
            else -> null
        }
    },
    checkFixed = { lines ->
        val form = count(lines, FORM) ?: 0
        val price = count(lines, PRICE) ?: 0
        when {
            form == 0 -> "OrderForm 沒有執行：OrderScreen 要呼叫 OrderForm(form, counts)"
            price < 5 -> "PriceText 沒有跟著價格更新（只執行了 $price 次）：PriceText 要自己用 collectAsState 收集 price"
            form > 1 -> "OrderForm 重組了 ${form - 1} 次：價格要放在獨立的 Flow，不要放進表單的 state"
            else -> null
        }
    },
)
