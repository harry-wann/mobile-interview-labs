// L05：輸入到一半的數量，旋轉螢幕後不見了（remember 與 rememberSaveable）
// 對應複習站：Kotlin06Recomposition 第 6 題「remember、rememberSaveable 和 key
// 怎麼用？」
//
// 情境：下單畫面有一個「下單數量」輸入欄。使用者輸入到一半旋轉螢幕，Activity
// 會被銷毀再重建。
//        這個演練的按鈕「模擬旋轉螢幕」會呼叫 Activity.recreate()，
// 和真的旋轉一樣；也可以直接旋轉模擬器（Ctrl+左/右方向鍵）。
// Bug 版：用 remember { mutableStateOf("") } 保存輸入的文字。remember
// 只活過重組，Activity 重建後就消失了。
// 修正版：改用 rememberSaveable { mutableStateOf("") }。它把值存進 saved
// instance state，旋轉螢幕、
//        行程被系統殺掉後重開都還在。（只適合小量資料，例如輸入中的文字；
// 大型資料放 ViewModel）
package tw.harry.labs.solution.kotlin06recomposition

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 演練畫面（兩個版本共用）：[qty] 是輸入欄的文字，[onQtyChange]
 * 是使用者輸入時要做的事
 */
@Composable
private fun OrderForm(qty: String, onQtyChange: (String) -> Unit, log: LabLog) {
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(Unit) { log("畫面建立：下單數量欄位是「$qty」") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = qty, onValueChange = onQtyChange, label = {
            Text("下單數量")
        })
        Button(onClick = {
            log("模擬旋轉螢幕：Activity.recreate()")
            activity?.recreate()
        }) { Text("模擬旋轉螢幕") }
    }
}

// MARK: - Bug 版
@Composable
private fun L05BugScreen(log: LabLog) {
    var qty by remember { mutableStateOf("") } // 只活過重組
    OrderForm(qty, { qty = it }, log)
}

// MARK: - 修正版
@Composable
private fun L05FixedScreen(log: LabLog) {
    // 存進 saved instance state：旋轉後還在
    var qty by rememberSaveable { mutableStateOf("") }
    OrderForm(qty, { qty = it }, log)
}

// MARK: - 演練設定
@Composable
private fun L05Demo(variant: Variant, log: LabLog) {
    when (variant) {
        Variant.BUG -> L05BugScreen(log)
        Variant.FIXED -> L05FixedScreen(log)
    }
}

val l05 = Lab(
    id = "L05",
    title = "輸入到一半的數量，旋轉螢幕後不見了",
    question = "第 6 題：remember、rememberSaveable 和 key 怎麼用？",
    goal = "在「下單數量」輸入 0.5 後旋轉螢幕。看 remember 為什麼撐不過 Activity 重建，再改用 rememberSaveable。",
    steps = listOf(
        "選「Bug 版」，在「下單數量」輸入 0.5，按「模擬旋轉螢幕」（或旋轉模擬器），看欄位和 log「畫面建立：下單數量欄位是…」。",
        "選「修正版」，一樣輸入 0.5 再旋轉，確認數量還在。",
        "想一想：換成 ViewModel 也能撐過旋轉，什麼時候該用 rememberSaveable、什麼時候用 ViewModel？（小量輸入用 rememberSaveable；大型資料或要共用的狀態放 ViewModel）",
    ),
    expectBug = "旋轉後欄位變回空的；log 印出「畫面建立：下單數量欄位是「」」。",
    expectFixed = "旋轉後欄位仍然是 0.5；log 印出「畫面建立：下單數量欄位是「0.5」」。",
    file = "kotlin06-recomposition/L05_RememberSaveable.kt",
    demo = { variant, log -> L05Demo(variant, log) },
)
