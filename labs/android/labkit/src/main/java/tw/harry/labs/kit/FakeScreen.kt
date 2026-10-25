package tw.harry.labs.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * 模擬「打開畫面／關閉畫面」。
 *
 * 打開時給 [content] 一個全新的 ViewModelStoreOwner，所以裡面用 `viewModel()` 拿到的 ViewModel
 * 只屬於這次打開的畫面；關閉時清掉它，ViewModel 的 onCleared 會被呼叫、viewModelScope 會被取消，
 * 和真的離開一個畫面一樣。
 */
@Composable
fun FakeScreenHost(
    log: LabLog,
    name: String = "畫面",
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var openCount by remember { mutableIntStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !open, onClick = {
                openCount++
                open = true
                log("打開$name（第 $openCount 次）")
            }) { Text("打開$name") }
            OutlinedButton(enabled = open, onClick = {
                open = false
                log("關閉$name")
            }) { Text("關閉$name") }
        }
        if (open) {
            val owner = remember(openCount) { ScreenOwner() }
            DisposableEffect(owner) {
                onDispose { owner.viewModelStore.clear() }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                content()
            }
        }
    }
}

private class ScreenOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
