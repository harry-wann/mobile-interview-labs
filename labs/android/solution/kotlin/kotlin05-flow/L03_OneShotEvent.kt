// L03：旋轉螢幕後，又導航了一次（一次性事件不要用 StateFlow 的目前值）
// 對應複習站：Kotlin05Flow 第 3 題「導航、Toast 這種一次性事件怎麼傳才不會掉？」
//
// 情境：下單成功後要導航到訂單詳情，這個事件只能處理一次。使用者下單後馬上旋轉螢幕，畫面被銷毀重建，新的畫面再開始收集。
// Bug 版：ViewModel 用 StateFlow<String?> 傳「要導航到哪張訂單」，收集端導航後沒有清掉。
//        StateFlow 永遠有「目前值」，新的 collector 一進來就先收到它，旋轉後又導航了一次。
// 修正版：把事件放進 state（pendingNavigation），UI 導航完呼叫 onNavigated() 讓 ViewModel 清成 null。
//        新的 collector 進來看到的是 null，不會重複導航。
package tw.harry.labs.solution.kotlin05flow

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

// MARK: - Bug 版
private object L03Bug {
    class OrderViewModel {
        private val _navigateTo = MutableStateFlow<String?>(null)
        val navigateTo: StateFlow<String?> = _navigateTo.asStateFlow()

        fun onOrderPlaced(id: String) {
            _navigateTo.value = id                           // 事件當成 StateFlow 的目前值
        }
    }

    suspend fun run(log: LabLog) {
        val navigations = AtomicInteger(0)
        val vm = OrderViewModel()
        coroutineScope {
            val screenBeforeRotate = launch(start = CoroutineStart.UNDISPATCHED) {
                vm.navigateTo.collect { id ->
                    if (id != null) {
                        navigations.incrementAndGet()
                        log("旋轉前的畫面：導航到訂單 $id")
                    }
                }
            }
            vm.onOrderPlaced("o-1")
            delay(50)
            screenBeforeRotate.cancelAndJoin()               // 旋轉螢幕：畫面被銷毀
            val screenAfterRotate = launch(start = CoroutineStart.UNDISPATCHED) {
                vm.navigateTo.collect { id ->                // 新的畫面一開始收集，先收到目前值 o-1
                    if (id != null) {
                        navigations.incrementAndGet()
                        log("旋轉後的畫面：導航到訂單 $id")
                    }
                }
            }
            delay(50)
            screenAfterRotate.cancelAndJoin()
        }
        log("共導航了 ${navigations.get()} 次")
    }
}

// MARK: - 修正版
private object L03Fixed {
    data class OrderUiState(val pendingNavigation: String? = null)

    class OrderViewModel {
        private val _state = MutableStateFlow(OrderUiState())
        val state: StateFlow<OrderUiState> = _state.asStateFlow()

        fun onOrderPlaced(id: String) {
            _state.update { it.copy(pendingNavigation = id) }   // 把「要導航」存進 state
        }

        fun onNavigated() {
            _state.update { it.copy(pendingNavigation = null) } // UI 導航完，通知 ViewModel 清掉
        }
    }

    suspend fun run(log: LabLog) {
        val navigations = AtomicInteger(0)
        val vm = OrderViewModel()
        coroutineScope {
            val screenBeforeRotate = launch(start = CoroutineStart.UNDISPATCHED) {
                vm.state.collect { state ->
                    state.pendingNavigation?.let { id ->
                        navigations.incrementAndGet()
                        log("旋轉前的畫面：導航到訂單 $id")
                        vm.onNavigated()                     // 處理完就清掉
                    }
                }
            }
            vm.onOrderPlaced("o-1")
            delay(50)
            screenBeforeRotate.cancelAndJoin()               // 旋轉螢幕：畫面被銷毀
            val screenAfterRotate = launch(start = CoroutineStart.UNDISPATCHED) {
                vm.state.collect { state ->
                    state.pendingNavigation?.let { id ->
                        navigations.incrementAndGet()
                        log("旋轉後的畫面：導航到訂單 $id")
                        vm.onNavigated()
                    }
                }
            }
            delay(50)
            screenAfterRotate.cancelAndJoin()
        }
        log("共導航了 ${navigations.get()} 次")
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "旋轉螢幕後，又導航了一次",
    question = "第 3 題：導航、Toast 這種一次性事件怎麼傳才不會掉？",
    goal = "下單成功後導航到訂單詳情，只能導航一次。看旋轉螢幕後 StateFlow 的目前值怎麼讓事件重複，再改成「處理完清掉」。",
    steps = listOf(
        "選「Bug 版」執行，數「導航到訂單」出現幾次，分別是旋轉前還是旋轉後。",
        "選「修正版」執行，確認只導航一次。",
        "想一想：改用 SharedFlow(replay = 1) 會怎樣？背景時沒有訂閱者、replay = 0 又會怎樣？（replay = 1 一樣重複；replay = 0 在背景時事件直接消失）",
    ),
    expectBug = "「旋轉前的畫面：導航到訂單 o-1」和「旋轉後的畫面：導航到訂單 o-1」都出現，最後一行「共導航了 2 次」。",
    expectFixed = "只出現「旋轉前的畫面：導航到訂單 o-1」，最後一行「共導航了 1 次」。",
    file = "kotlin05-flow/L03_OneShotEvent.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "旋轉前的畫面：導航到訂單 o-1" !in lines -> "旋轉前的畫面要導航到 o-1：onOrderPlaced 要把 id 放進 StateFlow"
            "旋轉後的畫面：導航到訂單 o-1" !in lines -> "Bug 版旋轉後的畫面又要導航一次：事件放在 StateFlow 的目前值，導航後不要清掉"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "旋轉前的畫面：導航到訂單 o-1" !in lines -> "旋轉前的畫面要導航到 o-1：onOrderPlaced 要把 id 放進 state 的 pendingNavigation"
            "共導航了 1 次" !in lines -> "只應該導航 1 次：導航完要呼叫 onNavigated()，把 pendingNavigation 清成 null"
            else -> null
        }
    },
)
