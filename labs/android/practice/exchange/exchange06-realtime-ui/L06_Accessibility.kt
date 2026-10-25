// L06：TalkBack 只唸「12,345,678」和「按鈕」，UI 測試也找不到元素（行情列沒有 contentDescription 與 testTag）
// 對應複習站：Exchange06RealtimeUI 第 6 題「數字怎麼顯示，才不會抖動、也不會拖慢畫面？」（畫面上的數字怎麼唸出來、怎麼被測試找到）
//
// 這題的自動檢查要讀 Compose 的 semantics 樹，所以在電腦上跑單元測試（JVM，用 Robolectric 模擬 Android，不用模擬器），沒有「執行」按鈕。
// 測試在 practice/exchange/test/Exchange06L06Test.kt。第一次執行 Robolectric 會下載 Android 的模擬環境，要等一下。
// 畫面上的「demo」可以在模擬器開 TalkBack，或用 Layout Inspector 的 Semantics 看這一列。
//
// 情境：行情列有交易對 BTC_JPY、價格 12,345,678、漲跌（只用綠色的 ▲ 表示上漲 1.23%），最右邊是一顆只有購物車圖示的下單按鈕。
//       視障使用者開 TalkBack 要能聽到「比特幣、價格、漲還是跌、可以下單」；QA 寫 UI 測試要能用 testTag 找到這一列和那顆按鈕。
//       測試把這一列畫出來，讀 Compose 的 semantics 樹（TalkBack 看到的就是這棵樹）：每個元素的文字、contentDescription、stateDescription、testTag、是不是按鈕。
//
// 你要寫：
//   ① Bug 版：漲跌只靠顏色與「▲」符號（Text("▲", color = …)），下單按鈕只有圖示（IconButton 裡面 Image 的 contentDescription = null），都不加 semantics。
//      TalkBack 看到 4 個元素，沒有人說「上漲」，按鈕沒有名稱。
//   ② 修正版：行情那一塊合成一個元素，加上 contentDescription（幣種）、stateDescription（把價格與漲跌唸完整）與 testTag「ticker.BTC_JPY.quote」；
//      下單按鈕的圖示加 contentDescription「下單 BTC_JPY」，按鈕加 testTag「ticker.BTC_JPY.order」。
// 只改有 TODO 的地方（寫在這個檔案）。測試在 practice/exchange/test/Exchange06L06Test.kt，寫完之後執行，結果會告訴你寫對了沒有。
// 解答：solution/exchange/exchange06-realtime-ui/L06_Accessibility.kt
package tw.harry.labs.practice.exchange06realtimeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.Variant

/** 購物車圖示的替身（兩個版本共用）：真的專案會用 Icon(Icons.Default.ShoppingCart, …)，這裡用色塊代替，少加一個圖示套件 */
private val CartIcon = ColorPainter(Color(0xFF455A64))

/** demo（兩個版本共用）：把行情列放在畫面上，開 TalkBack 或用 Layout Inspector 看 */
@Composable
private fun L06Demo(variant: Variant) {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (variant) {
            Variant.BUG -> E06TickerRowBug()
            Variant.FIXED -> E06TickerRowFixed()
        }
        Text("開 TalkBack（設定 > 無障礙 > TalkBack），點這一列聽它唸什麼；或用 Android Studio 的 Layout Inspector 看 Semantics。", fontSize = 12.sp)
    }
}

// MARK: - Bug 版
@Composable
fun E06TickerRowBug() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("BTC_JPY")
        Spacer(Modifier.weight(1f))
        Text("12,345,678")
        // TODO ①-1：漲跌只靠顏色與符號：Text("▲", color = Color(0xFF2E7D32))
        // TODO ①-2：下單按鈕只有圖示：IconButton(onClick = {}) { Image(CartIcon, contentDescription = null, Modifier.size(24.dp)) }（都不要加 semantics）
    }
}

// MARK: - 修正版
@Composable
fun E06TickerRowFixed() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            // TODO ②-1：這一塊合成一個元素：Modifier.weight(1f).clearAndSetSemantics { … }，裡面設
            //          contentDescription（幣種，例如「BTC 兌日圓」）、stateDescription（「12,345,678 日圓，上漲 1.23%」）、testTag = "ticker.BTC_JPY.quote"
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("BTC_JPY")
            Spacer(Modifier.weight(1f))
            Text("12,345,678")
            Text("▲", color = Color(0xFF2E7D32))
        }
        // TODO ②-2：IconButton 加 modifier = Modifier.testTag("ticker.BTC_JPY.order")；裡面 Image 的 contentDescription 改成「下單 BTC_JPY」
        IconButton(onClick = {}) {
            Image(CartIcon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "TalkBack 只唸數字和「按鈕」",
    question = "第 6 題：數字怎麼顯示，才不會抖動、也不會拖慢畫面？（畫面上的數字怎麼唸出來、怎麼被測試找到）",
    goal = "行情列不能只靠顏色與符號表達漲跌：用 contentDescription／stateDescription 把幣種、價格、漲跌唸完整，圖示按鈕要有名稱，並加 testTag 讓 UI 測試找得到。 先寫出什麼都沒加的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange06-realtime-ui/L06_Accessibility.kt，寫 TODO ①。",
        "這題不用按「執行」，在電腦上跑單元測試（Robolectric）。打開 test/Exchange06L06Test.kt，點 l06() 旁邊的 ▶ 執行。第一次要下載 Robolectric 的環境，會比較久。",
        "或在終端機：cd labs/android && ./gradlew :practice-exchange:testDebugUnitTest --tests '*Exchange06L06Test*' -i | grep SELFTEST。",
        "看 Bug 版的結果：TalkBack 看到幾個元素？哪一個有提到「上漲」？按鈕有名稱嗎？用 testTag 找得到嗎？",
        "想一想：視障使用者聽到「12,345,678」和「按鈕」，知道這是什麼幣、漲還是跌嗎？只靠綠色與紅色表示漲跌，色覺異常的使用者呢？",
        "寫 TODO ②，再跑一次：行情列只剩兩個元素，唸出「BTC 兌日圓，12,345,678 日圓，上漲 1.23%」與「下單 BTC_JPY」，兩個 testTag 都找得到。進階：iOS 的 accessibilityLabel／accessibilityValue／accessibilityIdentifier 對應 contentDescription／stateDescription／testTag。",
    ),
    expectBug = "TalkBack 看到 4 個元素（交易對、價格、▲、按鈕各一個），▲ 唸成幾何符號、按鈕沒有名稱；沒有任何元素提到「上漲」或「下單」；兩個 testTag 都找不到。",
    expectFixed = "TalkBack 看到 2 個元素：「BTC 兌日圓，12,345,678 日圓，上漲 1.23%」與「下單 BTC_JPY」（按鈕）；兩個 testTag 都找得到。",
    file = "test/Exchange06L06Test.kt",
    demo = { variant, _ -> L06Demo(variant) },
)
