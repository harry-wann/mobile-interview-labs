// L08：按了送出、馬上返回，訂單卻沒有送出去（rememberCoroutineScope 與
// ViewModel）
// 對應複習站：Kotlin07SideEffects 第 5 題「使用者點按鈕時要啟動 coroutine，
// 為什麼要用 rememberCoroutineScope？」
//
// 情境：下單畫面按「送出」會呼叫伺服器（約 400 ms）。使用者按下送出、20 ms
// 後就返回上一頁。
//        演練裡的 OrderViewModel 代表 ViewModel：它的 scope（viewModelScope）
// 撐過畫面離開。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose
// 在電腦上真的跑 Compose 的組合、重組與 effect。
//
// 你要寫：
//   ① Bug 版（L08Bug）：用 rememberCoroutineScope() 的 scope.launch { vm.api.
// submit() } 送單。離開畫面時 scope 被取消，單送不完。
//   ② 修正版（L08Fixed）：onSubmit 改成呼叫 vm.submit()，送單交給 ViewModel 的
// scope。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin07-side-effects/L08_ScopeCancelled.kt
package tw.harry.labs.practice.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 假的伺服器：送單要 400 ms，送完才算「伺服器收到訂單」 */
private class L08Api(private val log: LabLog) {
    var received = false
        private set

    suspend fun submit() {
        log("開始送出訂單")
        delay(400)
        received = true
        log("伺服器收到訂單")
    }
}

/** 假的 ViewModel：scope 代表 viewModelScope，不會因為畫面離開而取消 */
private class L08OrderViewModel(
    val api: L08Api,
    private val scope: CoroutineScope,
) {
    fun submit() {
        scope.launch { api.submit() }
    }
}

/**
 * 演練流程（兩個版本共用）：按送出 → 20 ms 後離開畫面 → 再等 800 ms，
 * 看伺服器有沒有收到訂單
 */
private suspend fun scenario(
    log: LabLog,
    screen: @Composable (
        vm: L08OrderViewModel,
        expose: (click: () -> Unit) -> Unit,
    ) -> Unit,
) {
    val api = L08Api(log)
    val viewModelJob = Job()
    val vm = L08OrderViewModel(api, CoroutineScope(viewModelJob))
    var click: (() -> Unit)? = null
    withHeadlessCompose {
        val visible = mutableStateOf(true)
        setContent { if (visible.value) screen(vm) { click = it } }
        settle()
        log("按下送出")
        click?.invoke()
        delay(20)
        visible.value = false
        settle()
        log("使用者離開畫面")
        delay(800)
        log(if (api.received) "伺服器有收到訂單" else "伺服器沒有收到訂單")
    }
    viewModelJob.cancel()
}

// MARK: - Bug 版
private object L08Bug {
    @Composable
    fun OrderScreen(
        vm: L08OrderViewModel,
        expose: (click: () -> Unit) -> Unit,
    ) {
        // TODO ①：用 rememberCoroutineScope() 取得 scope，把下面 onSubmit
        // 的內容換成 scope.launch { vm.api.submit() }
        val onSubmit: () -> Unit = { }
        // 演練用：把按鈕的 onClick 交出去
        SideEffect { expose(onSubmit) }
    }

    suspend fun run(log: LabLog) = scenario(log) { vm, expose ->
        OrderScreen(vm, expose)
    }
}

// MARK: - 修正版
private object L08Fixed {
    @Composable
    fun OrderScreen(
        vm: L08OrderViewModel,
        expose: (click: () -> Unit) -> Unit,
    ) {
        // TODO ②：把下面 onSubmit 的內容換成 vm.submit()（交給 ViewModel：
        // 離開畫面不會取消）
        val onSubmit: () -> Unit = { }
        SideEffect { expose(onSubmit) }
    }

    suspend fun run(log: LabLog) = scenario(log) { vm, expose ->
        OrderScreen(vm, expose)
    }
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "按了送出馬上返回，訂單沒送出去",
    question = "第 5 題：使用者點按鈕時要啟動 coroutine，為什麼要用 rememberCoroutineScope？",
    goal = "按「送出」後 20 ms 就離開畫面，但送單要 400 ms。看 rememberCoroutineScope 的 scope 在離開畫面時被取消，再把必須做完的工作交給 ViewModel。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin07-side-effects/L08_ScopeCancelled.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 log：「開始送出訂單」之後有沒有出現「伺服器收到訂單」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認離開畫面之後，伺服器還是收到訂單。",
        "想一想：那 rememberCoroutineScope 適合做什麼？（只跟畫面有關的事，例如點按鈕後捲動列表的動畫；離開畫面就不需要做完）",
    ),
    expectBug = "「開始送出訂單」之後沒有「伺服器收到訂單」，最後一行是「伺服器沒有收到訂單」：scope 隨畫面一起被取消。",
    expectFixed = "離開畫面之後仍然出現「伺服器收到訂單」，最後一行是「伺服器有收到訂單」。",
    file = "kotlin07-side-effects/L08_ScopeCancelled.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "開始送出訂單" !in lines -> "按下送出之後沒有開始送單：onSubmit 要在 rememberCoroutineScope() 的 scope 裡呼叫 vm.api.submit()"
            "伺服器有收到訂單" in lines -> "訂單還是送出去了：Bug 版要用 rememberCoroutineScope() 的 scope.launch，不要交給 ViewModel"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "開始送出訂單" !in lines -> "按下送出之後沒有開始送單：onSubmit 要呼叫 vm.submit()"
            "伺服器有收到訂單" !in lines -> "離開畫面之後，訂單沒有送完：送單要交給 ViewModel（vm.submit()），不要用畫面的 scope"
            else -> null
        }
    },
)
