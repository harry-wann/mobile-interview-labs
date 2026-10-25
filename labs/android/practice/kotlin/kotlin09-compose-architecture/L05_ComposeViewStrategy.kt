// L05：舊的 View 暫時被拆下再裝回，ComposeView 裡的狀態歸零了（ViewCompositionStrategy）
// 對應複習站：Kotlin09ComposeArchitecture 第 5 題「既有的 XML／Fragment 專案，怎麼嵌入 Compose？反過來呢？」
//
// 情境：舊的 View 系統裡嵌了一個 ComposeView，裡面有一個「點了 N 次」的按鈕（用 remember 保存次數）。
//        按「移出畫面」「放回畫面」模擬 View 被暫時拆下再裝回（轉場動畫、重新排列版面時都可能發生）。
//        ComposeView 是用程式建立的，寫法和 Fragment 的 onCreateView 裡建立 ComposeView 一樣。
//
// 你要寫：
//   ① Bug 版（L05Bug.createComposeView）：ComposeView(context).apply { setContent { L05Counter(log) } }，不設定 strategy（用預設）。
//   ② 修正版（L05Fixed.createComposeView）：同樣，但先 setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)。
// 只改有 TODO 的地方。寫完在 App 選「練習」「Bug 版」「修正版」，照步驟操作，看 log 和畫面是不是照預期。
// 解答：solution/kotlin/kotlin09-compose-architecture/L05_ComposeViewStrategy.kt
package tw.harry.labs.practice.kotlin09composearchitecture

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** ComposeView 裡的內容（兩個版本共用）：remember 保存的點擊次數，加上 composition 建立與釋放的 log */
@Composable
private fun L05Counter(log: LabLog) {
    var taps by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        log("ComposeView 的 composition 建立")
        onDispose { log("ComposeView 的 composition 釋放") }
    }
    Button(onClick = { taps++ }) { Text("點了 $taps 次（用 remember 保存）") }
}

// MARK: - Bug 版
private object L05Bug {
    // TODO ①：建立 ComposeView，setContent { L05Counter(log) }；不要設定 strategy（預設是 view 從 window 拆下就釋放 composition）
    fun createComposeView(context: Context, log: LabLog) = ComposeView(context).apply {
        setContent { Text("（還沒寫）") }
    }
}

// MARK: - 修正版
private object L05Fixed {
    // TODO ②：同樣建立 ComposeView 並 setContent { L05Counter(log) }，但先呼叫 setViewCompositionStrategy(…)，
    //         用 ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed（跟著 view tree 的 lifecycle 釋放）
    fun createComposeView(context: Context, log: LabLog) = ComposeView(context).apply {
        setContent { Text("（還沒寫）") }
    }
}

// MARK: - 演練設定
@Composable
private fun L05Demo(variant: Variant, log: LabLog) {
    val context = LocalContext.current
    val host = remember { FrameLayout(context) }                        // 代表舊的 View 系統的容器
    val composeView = remember(variant) {
        when (variant) {
            Variant.BUG -> L05Bug.createComposeView(context, log)
            Variant.FIXED -> L05Fixed.createComposeView(context, log)
        }
    }
    DisposableEffect(composeView) {
        host.addView(composeView)
        onDispose {
            host.removeView(composeView)
            composeView.disposeComposition()                            // 離開演練時一定要釋放
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                host.removeView(composeView)
                log("把 ComposeView 從容器移出畫面（removeView）")
            }) { Text("移出畫面") }
            OutlinedButton(onClick = {
                if (composeView.parent == null) host.addView(composeView)
                log("把 ComposeView 放回容器（addView）")
            }) { Text("放回畫面") }
        }
        AndroidView(factory = { host }, modifier = Modifier.fillMaxWidth().height(64.dp))
    }
}

val l05 = Lab(
    id = "L05",
    title = "View 暫時被拆下再裝回，狀態歸零",
    question = "第 5 題：既有的 XML／Fragment 專案，怎麼嵌入 Compose？反過來呢？",
    goal = "舊的 View 系統裡的 ComposeView 被暫時拆下再裝回。看預設的 composition strategy 為什麼讓 remember 的狀態歸零，再改成跟著 view tree 的 lifecycle 釋放。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin09-compose-architecture/L05_ComposeViewStrategy.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」，點按鈕 3 次（顯示「點了 3 次」），按「移出畫面」，再按「放回畫面」，看按鈕上的次數和 log 的「composition 釋放／建立」。",
        "寫 TODO ②（修正版），選「修正版」，同樣操作，確認次數還是 3 次，而且 log 沒有出現「composition 釋放」。",
        "想一想：為什麼 Fragment 裡要用 DisposeOnViewTreeLifecycleDestroyed？（Fragment 的 view 有自己的 lifecycle；預設策略看的是 window，兩者時機不一致，行為難以預期）",
    ),
    expectBug = "按「移出畫面」時 log 出現「ComposeView 的 composition 釋放」；按「放回畫面」出現「composition 建立」，按鈕上的次數回到 0 次。",
    expectFixed = "移出再放回之後，按鈕上的次數仍然是 3 次，log 沒有出現「composition 釋放」或第二次「composition 建立」。",
    file = "kotlin09-compose-architecture/L05_ComposeViewStrategy.kt",
    demo = { variant, log -> L05Demo(variant, log) },
)
