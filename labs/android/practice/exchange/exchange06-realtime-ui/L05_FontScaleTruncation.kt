// L05：字體放大之後，價格被截斷成「12,345,…」（價格欄寫死寬度，
// 沒有考慮字體縮放）
// 對應複習站：Exchange06RealtimeUI 第 6 題「數字怎麼顯示，才不會抖動、
// 也不會拖慢畫面？」（價格欄的寬度與字體大小）
//
// 這題的自動檢查要真的排版文字，所以在電腦上跑單元測試（JVM，用 Robolectric
// 模擬 Android，不用模擬器），沒有「執行」按鈕。
// 測試在 practice/exchange/test/Exchange06L05Test.kt。第一次執行 Robolectric
// 會下載 Android 的模擬環境，要等一下。
// 畫面上的「demo」可以在模擬器切換字體縮放，直接看價格有沒有被截斷。
//
// 情境：行情列有交易對（BTC_JPY）與價格（12,345,678）。
// 團隊為了讓價格欄上下對齊，給價格欄一個固定寬度，而且限定只顯示一行。
//       使用者在系統設定把「字型大小」調大（fontScale 1.3 到最大 2.0），
// 價格被截斷，看不到完整的價格就下單。
//       測試把同一列分別用字體縮放 1.0、1.3、2.0
// 各排版一次（CompositionLocalProvider 提供 Density(density, fontScale)），
//       讀 Text 的 onTextLayout 回傳的 TextLayoutResult：hasVisualOverflow 是
// true，代表文字被截斷（顯示成「…」）。
//
// 你要寫：
//   ① Bug 版：價格 Text 用 Modifier.width(96.dp) 寫死寬度，加 maxLines = 1、
// overflow = TextOverflow.Ellipsis。
//      字體縮放 1.0 剛好放得下，1.3 與 2.0 需要的寬度超過 96 dp，價格被截斷，
// 而且價格欄的寬度不隨字體縮放變化。
//   ② 修正版：不寫死寬度，也不限一行：改用 Modifier.widthIn(min = 96.dp)
// （最小寬度，字變大就跟著變寬），拿掉 maxLines 與 overflow。
// 只改有 TODO 的地方（寫在這個檔案）。測試在
// practice/exchange/test/Exchange06L05Test.kt，寫完之後執行，
// 結果會告訴你寫對了沒有。
// 解答：solution/exchange/exchange06-realtime-ui/L05_FontScaleTruncation.kt
package tw.harry.labs.practice.exchange06realtimeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.Variant

/** 測試與 demo 用的價格（兩個版本共用） */
const val E06_PRICE_SAMPLE = "12,345,678"

/** demo（兩個版本共用）：切換字體縮放，看價格有沒有被截斷 */
@Composable
private fun L05Demo(variant: Variant) {
    var fontScale by remember { mutableFloatStateOf(1f) }
    var overflow by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Column(
        Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                FilterChip(selected = fontScale == scale, onClick = {
                    fontScale =
                        scale
                }, label = { Text("字體 $scale") })
            }
        }
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
        ) {
            val report: (TextLayoutResult) -> Unit = {
                if (overflow !=
                    it.hasVisualOverflow
                ) {
                    overflow = it.hasVisualOverflow
                }
            }
            when (variant) {
                Variant.BUG -> E06PriceRowBug(
                    "BTC_JPY",
                    E06_PRICE_SAMPLE,
                    report,
                )

                Variant.FIXED -> E06PriceRowFixed(
                    "BTC_JPY",
                    E06_PRICE_SAMPLE,
                    report,
                )
            }
        }
        Text(if (overflow) "價格被截斷了" else "價格完整顯示")
        Text("也可以在模擬器的「設定 > 顯示 > 字型大小與樣式」把字型大小調到最大，再回來看。", fontSize = 12.sp)
    }
}

// MARK: - Bug 版
@Composable
fun E06PriceRowBug(
    symbol: String,
    price: String,
    onPriceLayout: (TextLayoutResult) -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Text(
            text = price,
            // TODO ①：價格欄寫死寬度與只准一行：modifier = Modifier.width(96.
            // dp)、maxLines = 1、overflow = TextOverflow.Ellipsis
            fontSize = 16.sp,
            textAlign = TextAlign.End,
            onTextLayout = onPriceLayout,
        )
    }
}

// MARK: - 修正版
@Composable
fun E06PriceRowFixed(
    symbol: String,
    price: String,
    onPriceLayout: (TextLayoutResult) -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Text(
            text = price,
            // TODO ②：下面三行是 Bug 版的寫法（會被截斷）。把 width(96.dp)
            // 改成 widthIn(min = 96.dp)（最小寬度，字變大就跟著變寬），拿掉
            // maxLines 與 overflow
            modifier = Modifier.width(96.dp),
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            onTextLayout = onPriceLayout,
        )
    }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "字體放大後，價格被截斷成「12,345,…」",
    question = "第 6 題：數字怎麼顯示，才不會抖動、也不會拖慢畫面？（價格欄的寬度與字體大小）",
    goal = "價格欄不能寫死寬度又限定一行：使用者把字體調大時，價格要完整顯示。不夠寬就讓價格欄跟著變寬（或允許換行），不要讓使用者看到被截斷的價格就下單。 先寫出寫死寬度的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange06-realtime-ui/L05_FontScaleTruncation.kt，寫 TODO ①。",
        "這題不用按「執行」，在電腦上跑單元測試（Robolectric）。打開 test/Exchange06L05Test.kt，點 l05() 旁邊的 ▶ 執行。第一次要下載 Robolectric 的環境，會比較久。",
        "或在終端機：cd labs/android && ./gradlew :practice-exchange:testDebugUnitTest --tests '*Exchange06L05Test*' -i | grep SELFTEST。",
        "看 Bug 版的結果：字體縮放 1.0 完整顯示，1.3 與 2.0 被截斷，價格欄寬度不隨縮放變化。在畫面上方切換「字體 1.0／1.3／2.0」可以直接看到「12,345,…」。",
        "想一想：價格被截斷，使用者還是能按下單，會發生什麼事？金額、價格、數量這類不能少一個字的資料，應該怎麼排版？",
        "寫 TODO ②，再跑一次：三種字體縮放都完整顯示，字體縮放 2.0 的價格欄比 1.0 寬。進階：iOS 一樣，Dynamic Type 調到最大（accessibilityExtraExtraExtraLarge），用 ViewThatFits 放不下時上下排。",
    ),
    expectBug = "字體縮放 1.0：完整顯示；1.3、2.0：被截斷（hasVisualOverflow 為 true）；價格欄寬度三種縮放都一樣（寫死）。畫面上價格變成「12,345,…」。",
    expectFixed = "字體縮放 1.0、1.3、2.0 都完整顯示（hasVisualOverflow 為 false）；字體縮放 2.0 的價格欄比 1.0 寬。",
    file = "test/Exchange06L05Test.kt",
    demo = { variant, _ -> L05Demo(variant) },
)
