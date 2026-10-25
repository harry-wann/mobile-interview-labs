// L06：離開畫面了，行情 listener 還掛在 WebSocket 上（DisposableEffect 的
// onDispose）
// 對應複習站：Kotlin07SideEffects 第 4 題「註冊 listener
// 要用什麼？DisposableEffect 和 SideEffect 差在哪？」
//
// 情境：行情畫面進入時向 WebSocket 註冊 listener（addListener），
// 離開時應該解除（removeListener）。
//        演練流程：進入畫面 → socket 推 3 筆價格 → 離開畫面 → socket 再推 3
// 筆價格。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose
// 在電腦上真的跑 Compose 的組合、重組與 effect。
//
// 你要寫：
//   ① Bug 版（L06Bug）：DisposableEffect(socket) 裡建立 listener 並
// addListener，onDispose { } 卻是空的。離開畫面後 listener 還掛著。
//   ② 修正版（L06Fixed）：onDispose { socket.removeListener(listener) }，
// 註冊了什麼就解除什麼。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin07-side-effects/L06_ListenerLeak.kt
package tw.harry.labs.practice.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.CopyOnWriteArrayList
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/**
 * 假的 WebSocket：用 addListener／removeListener 管理 listener，push
 * 把價格推給目前註冊的 listener
 */
private class L06Socket {
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    val listenerCount: Int get() = listeners.size

    fun addListener(listener: (Int) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Int) -> Unit) {
        listeners -= listener
    }

    fun push(price: Int) {
        listeners.forEach { it(price) }
    }
}

/**
 * 演練流程（兩個版本共用）：進入畫面 → 推 3 筆價格 → 離開畫面 → 再推 3 筆價格
 */
private suspend fun scenario(
    log: LabLog,
    screen: @Composable (socket: L06Socket, onPrice: (Int) -> Unit) -> Unit,
) {
    val socket = L06Socket()
    var received = 0
    withHeadlessCompose {
        val visible = mutableStateOf(true)
        setContent { if (visible.value) screen(socket) { received++ } }
        settle()
        repeat(3) { socket.push(it) }
        log("畫面還在時，收到 $received 筆價格")
        visible.value = false
        settle()
        log("離開畫面了，socket 上還註冊著 ${socket.listenerCount} 個 listener")
        repeat(3) { socket.push(it) }
        log("離開畫面之後，又收到 ${received - 3} 筆價格")
    }
}

// MARK: - Bug 版
private object L06Bug {
    @Composable
    fun SocketListener(socket: L06Socket, onPrice: (Int) -> Unit) {
        DisposableEffect(socket) {
            // TODO ①：建立 listener：val listener: (Int) -> Unit = { price ->
            // onPrice(price) }，再 socket.addListener(listener) 註冊；
            //         onDispose { } 的大括號留空（Bug 版就是忘了解除）
            onDispose { }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { socket, onPrice ->
        SocketListener(socket, onPrice)
    }
}

// MARK: - 修正版
private object L06Fixed {
    @Composable
    fun SocketListener(socket: L06Socket, onPrice: (Int) -> Unit) {
        DisposableEffect(socket) {
            // TODO ②：和 Bug 版一樣建立 listener 並 addListener；onDispose { }
            // 裡要 socket.removeListener(listener)
            onDispose { }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { socket, onPrice ->
        SocketListener(socket, onPrice)
    }
}

// MARK: - 演練設定
private fun count(lines: List<String>, prefix: String, suffix: String): Int? =
    lines.firstOrNull {
        it.startsWith(prefix)
    }?.removePrefix(prefix)?.removeSuffix(suffix)?.toIntOrNull()

private const val BEFORE = "畫面還在時，收到 "
private const val LISTENERS = "離開畫面了，socket 上還註冊著 "
private const val AFTER = "離開畫面之後，又收到 "

val l06 = Lab(
    id = "L06",
    title = "離開畫面了，listener 還掛在 socket 上",
    question = "第 4 題：註冊 listener 要用什麼？DisposableEffect 和 SideEffect 差在哪？",
    goal = "畫面進入時註冊行情 listener，離開時應該解除。看 onDispose 空著時 listener 為什麼還在收價格，再補上解除註冊。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin07-side-effects/L06_ListenerLeak.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「離開畫面了，socket 上還註冊著 N 個 listener」和「離開畫面之後，又收到 M 筆價格」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認離開畫面之後是 0 個 listener、0 筆價格。",
        "想一想：DisposableEffect 的 key（這裡是 socket）換了會怎樣？（先執行舊的 onDispose，再執行新的區塊，不會同時掛著兩個 listener）",
    ),
    expectBug = "「socket 上還註冊著 1 個 listener」，「離開畫面之後，又收到 3 筆價格」。",
    expectFixed = "「socket 上還註冊著 0 個 listener」，「離開畫面之後，又收到 0 筆價格」。",
    file = "kotlin07-side-effects/L06_ListenerLeak.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            (
                count(
                    lines,
                    BEFORE,
                    " 筆價格",
                ) ?: 0
            ) == 0 -> "畫面還在時沒有收到價格：DisposableEffect 裡要 socket.addListener(listener)"

            (
                count(
                    lines,
                    LISTENERS,
                    " 個 listener",
                ) ?: 0
            ) == 0 -> "離開畫面後 listener 已經解除了：Bug 版的 onDispose { } 要留空"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            (
                count(
                    lines,
                    BEFORE,
                    " 筆價格",
                ) ?: 0
            ) == 0 -> "畫面還在時沒有收到價格：DisposableEffect 裡要 socket.addListener(listener)"

            (
                count(
                    lines,
                    LISTENERS,
                    " 個 listener",
                ) ?: 0
            ) != 0 -> "離開畫面後 listener 還在：onDispose { socket.removeListener(listener) }"

            (
                count(
                    lines,
                    AFTER,
                    " 筆價格",
                ) ?: 0
            ) != 0 -> "離開畫面之後還在收價格：onDispose 要解除註冊"

            else -> null
        }
    },
)
