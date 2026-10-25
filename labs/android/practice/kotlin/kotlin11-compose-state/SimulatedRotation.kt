// L04、L05 共用的工具（不是演練，不用改）：在電腦上模擬「旋轉螢幕」，看 rememberSaveable 存下來的狀態有沒有還原。
package tw.harry.labs.practice.kotlin11composestate

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.SnapshotMutableState
import java.io.Serializable
import tw.harry.labs.kit.HeadlessCompose
import tw.harry.labs.kit.withHeadlessCompose

/**
 * 簡化版的 Android 預設規則：rememberSaveable 的狀態最後要放進 Bundle，所以只收得下
 * String、數字、Boolean、Serializable、Parcelable 這幾類。自訂的 class（例如沒實作 Serializable 的 data class）不行。
 * （真的 Android 用的是 DisposableSaveableStateRegistry 的 canBeSaved，規則相同，這裡只是不用開手機也能重現。）
 */
internal fun canBeSavedToBundle(value: Any): Boolean {
    if (value is SnapshotMutableState<*>) {
        val inner = value.value                                     // MutableState：檢查它裡面裝的東西
        return inner == null || canBeSavedToBundle(inner)
    }
    return value is String || value is Number || value is Boolean || value is Serializable || value is Parcelable
}

/**
 * 把 [screen] 放進 composition，並帶上 [restored]（上一次存起來的資料，第一次是 null），等它跑完，再做 [interact]。
 * 兩次「打開畫面」一定要走同一個 setContent，rememberSaveable 才找得到上次存的資料（它用程式碼的位置當 key）。
 */
private suspend fun <S, R> showScreen(
    restored: Map<String, List<Any?>>?,
    screen: @Composable (onState: (S) -> Unit, onShow: (String) -> Unit) -> Unit,
    onState: (S) -> Unit,
    onShow: (String) -> Unit,
    interact: suspend HeadlessCompose.(SaveableStateRegistry) -> R,
): R = withHeadlessCompose {
    val registry = SaveableStateRegistry(restoredValues = restored, canBeSaved = ::canBeSavedToBundle)
    setContent {
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
            screen(onState, onShow)
        }
    }
    settle()
    interact(registry)
}

/**
 * 模擬旋轉螢幕：
 * 1. 第一次組合 [screen]，等它跑完，呼叫 [edit] 改動畫面上的狀態（[screen] 要用 onState 把狀態交出來）。
 * 2. 把狀態存起來（Activity 被銷毀前，Android 會做的事），結束第一次組合。
 * 3. 用存起來的資料重新組合 [screen]（Activity 重建）。
 *
 * 回傳 (旋轉前畫面顯示的文字, 旋轉後畫面顯示的文字)。第一次組合時如果存不下（rememberSaveable 丟 IllegalArgumentException），
 * 例外會直接往外丟。
 */
internal suspend fun <S> rotateScreen(
    screen: @Composable (onState: (S) -> Unit, onShow: (String) -> Unit) -> Unit,
    edit: (S) -> Unit,
): Pair<String, String> {
    var before = ""
    var after = ""
    var state: S? = null
    val saved = showScreen(null, screen, { state = it }, { before = it }) { registry ->
        @Suppress("UNCHECKED_CAST")
        edit(state as S)
        settle()                                                    // 等畫面重組完，旋轉前畫面顯示的才是改過的草稿
        registry.performSave()
    }
    showScreen<S, Unit>(saved, screen, {}, { after = it }) {}
    return before to after
}
