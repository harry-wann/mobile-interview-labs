// L04：價格每跳一次，連沒用到價格的表單也重組（staticCompositionLocalOf
// 放了會頻繁變動的值）
// 對應複習站：Kotlin09ComposeArchitecture 第 4 題「CompositionLocal
// 是什麼？什麼時候不該用？」
//
// 情境：畫面用 CompositionLocal 把「目前價格」提供給整棵樹。PriceBadge 會讀它，
// OrderForm 完全沒有讀。價格更新 10 次（每 30 ms 一次）。
//        演練計算 PriceBadge 與 OrderForm 各執行了幾次。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose
// 在電腦上真的跑 Compose 的組合與重組。
// Bug 版：val LocalPrice = staticCompositionLocalOf { … }。static
// 版本不追蹤誰讀了它，值一改變，提供範圍裡面全部重組，
//        包含沒讀它的 OrderForm。
// 修正版：compositionLocalOf。Compose 會追蹤讀取，值改變時，
// 只有讀它的地方重組。
//        （staticCompositionLocalOf 只適合幾乎不變的值，例如 App 設定。）
package tw.harry.labs.solution.kotlin09composearchitecture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 記下 PriceBadge、OrderForm 各執行了幾次（包含第一次組合） */
private class L04Counts {
    val badge = AtomicInteger()
    val form = AtomicInteger()
}

/**
 * 演練流程（兩個版本共用）：組合畫面 → 價格每 30 ms 更新一次，共 10 次 →
 * 看兩個 Composable 各執行了幾次
 */
private suspend fun scenario(
    log: LabLog,
    counts: L04Counts,
    screen: @Composable (price: Long) -> Unit,
) {
    withHeadlessCompose {
        val price = mutableLongStateOf(100_000L)
        setContent { screen(price.longValue) }
        settle()
        repeat(10) {
            delay(30)
            price.longValue++
            settle()
        }
    }
    log("價格更新 10 次：PriceBadge 執行了 ${counts.badge.get()} 次")
    log("價格更新 10 次：OrderForm 執行了 ${counts.form.get()} 次")
}

// MARK: - Bug 版
private object L04Bug {
    // static：不追蹤讀取，值一變，範圍內全部重組
    val LocalPrice = staticCompositionLocalOf { 0L }

    @Composable
    fun TradeScreen(price: Long, counts: L04Counts) {
        CompositionLocalProvider(LocalPrice provides price) {
            PriceBadge(counts)
            OrderForm(counts)
        }
    }

    @Composable
    fun PriceBadge(counts: L04Counts) {
        val price = LocalPrice.current // 只有它讀了價格
        SideEffect { counts.badge.incrementAndGet() }
        HeadlessText("價格 $price")
    }

    @Composable
    fun OrderForm(counts: L04Counts) {
        SideEffect { counts.form.incrementAndGet() } // 沒有讀價格
        HeadlessText("下單表單")
    }

    suspend fun run(log: LabLog) {
        val counts = L04Counts()
        scenario(log, counts) { price -> TradeScreen(price, counts) }
    }
}

// MARK: - 修正版
private object L04Fixed {
    // 追蹤讀取：值一變，只有讀它的地方重組
    val LocalPrice = compositionLocalOf { 0L }

    @Composable
    fun TradeScreen(price: Long, counts: L04Counts) {
        CompositionLocalProvider(LocalPrice provides price) {
            PriceBadge(counts)
            OrderForm(counts)
        }
    }

    @Composable
    fun PriceBadge(counts: L04Counts) {
        val price = LocalPrice.current
        SideEffect { counts.badge.incrementAndGet() }
        HeadlessText("價格 $price")
    }

    @Composable
    fun OrderForm(counts: L04Counts) {
        SideEffect { counts.form.incrementAndGet() }
        HeadlessText("下單表單")
    }

    suspend fun run(log: LabLog) {
        val counts = L04Counts()
        scenario(log, counts) { price -> TradeScreen(price, counts) }
    }
}

// MARK: - 演練設定
private fun count(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull {
        it.startsWith(prefix)
    }?.removePrefix(prefix)?.removeSuffix(" 次")?.toIntOrNull()

private const val BADGE = "價格更新 10 次：PriceBadge 執行了 "
private const val FORM = "價格更新 10 次：OrderForm 執行了 "

val l04 = Lab(
    id = "L04",
    title = "價格一跳，沒用到價格的表單也重組",
    question = "第 4 題：CompositionLocal 是什麼？什麼時候不該用？",
    goal = "用 CompositionLocal 提供每秒會變的價格。看 staticCompositionLocalOf 讓沒讀價格的表單也重組，再改成 compositionLocalOf。",
    steps = listOf(
        "選「Bug 版」執行，看「OrderForm 執行了 N 次」的 N（它沒有讀價格）。",
        "選「修正版」執行，確認 OrderForm 只執行 1 次，PriceBadge 仍然跟著價格更新。",
        "想一想：CompositionLocal 適合放什麼？（主題、LocalContext 這種整棵樹都要的資料；業務資料與 ViewModel 用明確的參數傳，因為它會隱藏依賴）",
    ),
    expectBug = "「OrderForm 執行了 11 次」：第一次組合，加上價格更新 10 次各 1 次，雖然它沒有讀價格。",
    expectFixed = "「OrderForm 執行了 1 次」，「PriceBadge 執行了 11 次」：只有讀價格的 PriceBadge 重組。",
    file = "kotlin09-compose-architecture/L04_StaticLocal.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val badge = count(lines, BADGE) ?: 0
        val form = count(lines, FORM) ?: 0
        when {
            badge < 5 -> "PriceBadge 沒有跟著價格更新（只執行了 $badge 次）：要用 LocalPrice.current 讀價格，並用 CompositionLocalProvider 提供"
            form < 5 -> "OrderForm 只執行了 $form 次：Bug 版要用 staticCompositionLocalOf，值一變範圍內全部重組"
            else -> null
        }
    },
    checkFixed = { lines ->
        val badge = count(lines, BADGE) ?: 0
        val form = count(lines, FORM) ?: 0
        when {
            badge < 5 -> "PriceBadge 沒有跟著價格更新（只執行了 $badge 次）：要用 LocalPrice.current 讀價格，並用 CompositionLocalProvider 提供"
            form > 1 -> "OrderForm 重組了 ${form - 1} 次：改用 compositionLocalOf，Compose 才會只重組有讀它的地方"
            else -> null
        }
    },
)
