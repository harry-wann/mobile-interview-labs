// L03：Activity 設了 FLAG_SECURE，彈出來的 Dialog 還是截得到（Dialog 是另一個視窗）
// 對應複習站：Advanced08Security 第 6 題「怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？」（Android：FLAG_SECURE）
//
// 情境：出金確認畫面顯示地址與金額。Android 可以用 FLAG_SECURE 讓整個視窗無法截圖、無法錄影，最近使用的 App 列表（Recents）的縮圖也會變黑。
//       工程師在 Activity 的視窗設了 FLAG_SECURE，但「確認出金」是用 AlertDialog 彈出來的。
//       這個演練需要在模擬器或手機上操作，沒有自動檢查。
// Bug 版：只替 Activity 的視窗加 FLAG_SECURE。AlertDialog 有自己獨立的視窗，不會繼承，彈出的對話框內容仍然可以被截圖與錄影。
// 修正版：Dialog 顯示之前，也替它自己的視窗（dialog.window）加 FLAG_SECURE。
package tw.harry.labs.solution.advanced08security

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Window?.secureText(): String =
    if (this != null && (attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) "有 FLAG_SECURE" else "沒有 FLAG_SECURE"

/** 出金確認的 Dialog（兩個版本共用）：AlertDialog 有自己獨立的視窗 */
private fun buildConfirmDialog(activity: Activity): AlertDialog =
    AlertDialog.Builder(activity)
        .setTitle("確認出金")
        .setMessage("出金 0.5 BTC 到 bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh")
        .setPositiveButton("確認", null)
        .create()

// MARK: - Bug 版
private object L03Bug {
    /** 只有 Activity 的視窗有 FLAG_SECURE */
    fun showConfirmDialog(activity: Activity, log: LabLog) {
        val dialog = buildConfirmDialog(activity)
        dialog.show()                                           // 沒有替 Dialog 自己的視窗加旗標
        log("Activity 視窗：${activity.window.secureText()}；Dialog 視窗：${dialog.window.secureText()}")
    }
}

// MARK: - 修正版
private object L03Fixed {
    fun showConfirmDialog(activity: Activity, log: LabLog) {
        val dialog = buildConfirmDialog(activity)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)     // Dialog 自己的視窗也要加
        dialog.show()
        log("Activity 視窗：${activity.window.secureText()}；Dialog 視窗：${dialog.window.secureText()}")
    }
}

// MARK: - 演練設定
@Composable
private fun L03Demo(variant: Variant, log: LabLog) {
    val activity = LocalContext.current.findActivity()
    // 進入這個演練時，替 Activity 的視窗加 FLAG_SECURE；離開時還原，不影響其他演練
    var activityFlag by remember { mutableStateOf("…") }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activityFlag = activity?.window.secureText()        // 加完旗標再讀，畫面上的文字才正確
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Activity 視窗：$activityFlag")
        Text("按下按鈕彈出「確認出金」的 Dialog，再用模擬器的截圖按鈕（或電源鍵加音量下鍵）截圖。")
        Button(onClick = {
            if (activity == null) return@Button
            when (variant) {
                Variant.BUG -> L03Bug.showConfirmDialog(activity, log)
                Variant.FIXED -> L03Fixed.showConfirmDialog(activity, log)
            }
        }) { Text("彈出確認出金 Dialog") }
    }
}

val l03 = Lab(
    id = "L03",
    title = "Activity 設了 FLAG_SECURE，Dialog 還是截得到",
    question = "第 6 題：怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？（Android：FLAG_SECURE）",
    goal = "FLAG_SECURE 只保護設定它的那個視窗。Dialog、PopupWindow 是另一個視窗，要各自設定。",
    steps = listOf(
        "選「Bug 版」，按「彈出確認出金 Dialog」，看 log：Activity 視窗有 FLAG_SECURE，Dialog 視窗呢？",
        "Dialog 開著的時候截圖（模擬器工具列的相機鈕，或手機的電源鍵加音量下鍵）：Dialog 的內容會不會出現在截圖裡？再按「最近使用」看縮圖。",
        "選「修正版」做一樣的事：Dialog 視窗也有 FLAG_SECURE，截圖時 Dialog 的內容應該被擋住（黑色或出現無法截圖的提示）。",
        "進階：Compose 的 Dialog 預設的 securePolicy 是 Inherit，會跟著 Activity 的視窗；用 View 系統的 AlertDialog、BottomSheetDialog 就要自己設定。iOS 對應的是遮罩（本單元演練 3）。",
    ),
    expectBug = "log：「Activity 視窗：有 FLAG_SECURE；Dialog 視窗：沒有 FLAG_SECURE」。Dialog 開著時截圖，對話框的內容（地址、金額）清楚地出現在截圖裡。",
    expectFixed = "log：「Activity 視窗：有 FLAG_SECURE；Dialog 視窗：有 FLAG_SECURE」。Dialog 開著時截圖，對話框的內容被擋住。",
    file = "advanced08-security/L03_CaptureProtection.kt",
    demo = { variant, log -> L03Demo(variant, log) },
)
