package tw.harry.labs.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 計算某種物件目前「還活著」幾個，用來看記憶體洩漏。
 *
 * Kotlin 沒有 deinit，物件什麼時候被回收由 GC 決定。這裡的做法是：
 * 物件建立時 [add]，該釋放資源的時候（例如 ViewModel 的 onCleared、listener 被移除）[remove]。
 * 要看「物件真的有沒有被回收」，請用 LeakCanary 或 Android Studio 的 Memory Profiler。
 */
object LiveObjects {
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts = _counts.asStateFlow()

    fun add(name: String) = _counts.update {
        it + (name to (it[name] ?: 0) + 1)
    }
    fun remove(name: String) = _counts.update {
        it +
            (name to (it[name] ?: 0) - 1)
    }
    fun count(name: String): Int = _counts.value[name] ?: 0
    fun reset() {
        _counts.value = emptyMap()
    }
}

@Composable
fun LiveObjectsView(vararg names: String) {
    val counts by LiveObjects.counts.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name ->
            val n = counts[name] ?: 0
            Text(
                "存活中 $name：$n",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(
                        if (n >
                            0
                        ) {
                            Color(0x33FF9800)
                        } else {
                            Color(0x334CAF50)
                        },
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 顯示「這個位置重組了幾次」。放在想觀察的 Composable 裡：
 *
 * ```
 * @Composable fun PriceRow(...) {
 *     RecomposeCounter("PriceRow BTC")
 *     ...
 * }
 * ```
 * 數字是用 SideEffect 在每次重組完成後 +1，計數本身不會觸發重組。
 * 標了 @NonSkippableComposable：放它的 Composable 重組時它一定跟著執行，所以數到的是「外層」的重組次數。
 * （沒有這個標記的話，參數是固定字串，外層重組時它會被跳過，數字永遠停在 1。）
 * 想看更完整的資訊：Android Studio 的 Layout Inspector 也能顯示重組次數。
 */
@NonSkippableComposable
@Composable
fun RecomposeCounter(label: String) {
    val count = remember { IntArray(1) }
    SideEffect { count[0]++ }
    Text(
        "重組 $label：${count[0] + 1} 次",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = Color(0xFFE65100),
    )
}

/**
 * 顯示目前每秒畫了幾格，以及累計「卡頓」次數（某一格的間隔超過 1.5 倍的正常值）。
 * 主執行緒被卡住時，這裡的數字會掉下來。
 */
@Composable
fun FrameMeter() {
    var fps by remember { mutableIntStateOf(0) }
    var hitches by remember { mutableIntStateOf(0) }
    var worstMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        var frames = 0
        var windowStart = 0L
        var typical = 16_666_667L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val dt = now - last
                if (dt < typical) typical = dt.coerceAtLeast(6_000_000L)
                if (dt > typical * 3 / 2) hitches++
                worstMs = maxOf(worstMs, dt / 1_000_000)
            }
            last = now
            frames++
            if (windowStart == 0L) windowStart = now
            if (now - windowStart >= 1_000_000_000L) {
                fps = (frames * 1_000_000_000L / (now - windowStart)).toInt()
                frames = 0
                windowStart = now
            }
        }
    }

    Row(
        Modifier
            .background(
                if (fps in
                    1..49
                ) {
                    Color(0x33F44336)
                } else {
                    Color(0x334CAF50)
                },
                RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "FPS $fps　卡頓 $hitches 次　最長一格 $worstMs ms",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = {
            hitches = 0; worstMs = 0
        }) { Text("歸零", fontSize = 12.sp) }
    }
}
