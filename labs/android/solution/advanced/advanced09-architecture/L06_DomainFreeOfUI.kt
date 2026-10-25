// L06：測試手續費得先建畫面物件，兩個畫面的手續費還不一樣（Domain 層依賴了 UI 框架）
// 對應複習站：Advanced09Architecture 第 3 題「UseCase 什麼時候有價值？」（業務規則被多個畫面重用）、第 2 題「MVVM、MVI、Clean Architecture、TCA 各解決什麼問題？」（Clean 的依賴只能指向內層）
//
// 情境：現貨的 taker（吃單）手續費是成交金額的 0.12%，四捨五入到整數日圓。「一般下單」與「快速下單」兩個畫面都要顯示預估手續費。
//       演練模擬兩件事：
//       ① 寫一個單元測試，驗證 12,345 日圓的手續費是 15 日圓（12,345 × 0.12% = 14.814，四捨五入是 15）；
//       ② 兩個畫面各輸入 12,345 與 99,999 日圓，看畫面上顯示的手續費。
// Bug 版：CalcFeeUseCase 的建構子直接收「一般下單畫面」的狀態物件（裡面是 Compose 的 MutableState），算完把結果寫進它的 feeText。
//        （實務上也常見直接收 Context 或 Binding 這類 UI 框架的物件。）要測 UseCase，得先建立一個畫面物件；
//        快速下單畫面的型別不同，用不了這個 UseCase，只好把公式抄一份進自己的狀態物件，抄的時候順手改成無條件捨去：同樣的金額，兩個畫面的手續費不一樣。
// 修正版：Domain 層只收純資料、回傳結果（FeeRequest 進、FeeResult 出，都是 data class，不 import androidx.compose 或 android.*）；
//        兩個畫面各自把結果放進自己的狀態。測試只建 UseCase，不需要任何畫面物件；兩個畫面共用同一個 UseCase，數字一定一致。
package tw.harry.labs.solution.advanced09architecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicInteger
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 手續費率與四捨五入（兩個版本共用） */
private val L06_TAKER_RATE = BigDecimal("0.0012")

private fun rounded(value: BigDecimal, mode: RoundingMode): BigDecimal = value.setScale(0, mode)

/** 數「畫面物件被建立了幾個」（每次執行開始時歸零） */
private val L06_SCREEN_OBJECTS = AtomicInteger()

/** 兩個版本接線的方式不同：單元測試怎麼驗證公式、兩個畫面各怎麼顯示手續費 */
private class L06Wiring(
    /** 單元測試：驗證 12,345 日圓的手續費，回傳算出來的結果 */
    val unitTest: () -> String,
    /** 一般下單、快速下單：輸入金額之後，畫面上顯示的手續費 */
    val orderScreenFee: (BigDecimal) -> String,
    val quickScreenFee: (BigDecimal) -> String,
)

/** 演練流程（兩個版本共用）：先數單元測試建立了幾個畫面物件，再比對兩個畫面的手續費 */
private fun scenario(log: LabLog, wiring: L06Wiring) {
    L06_SCREEN_OBJECTS.set(0)
    val tested = wiring.unitTest()
    val created = L06_SCREEN_OBJECTS.get()                       // 單元測試做完，先讀數量，後面兩個畫面也會建立畫面物件
    log("單元測試：12345 日圓的手續費 $tested 日圓（應該是 15）")
    log("單元測試為了驗證公式，建立了 $created 個畫面物件")

    var mismatches = 0
    val amounts = listOf(BigDecimal(12_345), BigDecimal(99_999))
    for (amount in amounts) {
        val order = wiring.orderScreenFee(amount)
        val quick = wiring.quickScreenFee(amount)
        if (order != quick) mismatches++
        log("${amount.toPlainString()} 日圓：一般下單顯示 $order，快速下單顯示 $quick${if (order == quick) "" else "（不一致）"}")
    }
    log("兩個畫面的手續費不一致：$mismatches 筆（共 ${amounts.size} 筆）")
}

// MARK: - Bug 版
private object L06Bug {
    /** 一般下單畫面的狀態物件（feeText 是 Compose 的 MutableState） */
    class OrderScreenState {
        var feeText by mutableStateOf("")

        init {
            L06_SCREEN_OBJECTS.incrementAndGet()
        }
    }

    /** 「Domain 層」的 UseCase，卻直接收畫面的狀態物件（依賴了 Compose） */
    class CalcFeeUseCase(private val screen: OrderScreenState) {
        fun calculate(amount: BigDecimal) {
            screen.feeText = rounded(amount * L06_TAKER_RATE, RoundingMode.HALF_UP).toPlainString()    // 算完直接改畫面的 State
        }
    }

    /** 快速下單畫面的狀態物件：型別不同，用不了上面的 UseCase，只好自己抄一份公式 */
    class QuickScreenState {
        var feeText by mutableStateOf("")

        init {
            L06_SCREEN_OBJECTS.incrementAndGet()
        }

        fun amountChanged(amount: BigDecimal) {
            feeText = rounded(amount * L06_TAKER_RATE, RoundingMode.DOWN).toPlainString()            // 抄的時候改成無條件捨去
        }
    }

    suspend fun run(log: LabLog) = scenario(
        log,
        L06Wiring(
            unitTest = {
                val screen = OrderScreenState()                  // 想測公式，得先建一個畫面物件
                CalcFeeUseCase(screen).calculate(BigDecimal(12_345))
                screen.feeText
            },
            orderScreenFee = { amount ->
                val screen = OrderScreenState()
                CalcFeeUseCase(screen).calculate(amount)
                screen.feeText
            },
            quickScreenFee = { amount ->
                val screen = QuickScreenState()
                screen.amountChanged(amount)
                screen.feeText
            },
        ),
    )
}

// MARK: - 修正版
private object L06Fixed {
    /** Domain 層：輸入與輸出都是純資料，不認識任何畫面 */
    data class FeeRequest(val amountJpy: BigDecimal)

    data class FeeResult(val fee: BigDecimal)

    class CalcFeeUseCase {
        operator fun invoke(request: FeeRequest): FeeResult =
            FeeResult(rounded(request.amountJpy * L06_TAKER_RATE, RoundingMode.HALF_UP))   // 規則只在這一個地方
    }

    /** Presentation 層：自己呼叫 UseCase，再把結果放進畫面的狀態 */
    class OrderScreenState(private val calcFee: CalcFeeUseCase) {
        var feeText by mutableStateOf("")
            private set

        init {
            L06_SCREEN_OBJECTS.incrementAndGet()
        }

        fun amountChanged(amount: BigDecimal) {
            feeText = calcFee(FeeRequest(amount)).fee.toPlainString()
        }
    }

    class QuickScreenState(private val calcFee: CalcFeeUseCase) {
        var feeText by mutableStateOf("")
            private set

        init {
            L06_SCREEN_OBJECTS.incrementAndGet()
        }

        fun amountChanged(amount: BigDecimal) {
            feeText = calcFee(FeeRequest(amount)).fee.toPlainString()              // 兩個畫面共用同一個 UseCase
        }
    }

    suspend fun run(log: LabLog) {
        val calcFee = CalcFeeUseCase()
        scenario(
            log,
            L06Wiring(
                unitTest = { calcFee(FeeRequest(BigDecimal(12_345))).fee.toPlainString() },   // 測試只建 UseCase，沒有任何畫面物件
                orderScreenFee = { amount ->
                    val screen = OrderScreenState(calcFee)
                    screen.amountChanged(amount)
                    screen.feeText
                },
                quickScreenFee = { amount ->
                    val screen = QuickScreenState(calcFee)
                    screen.amountChanged(amount)
                    screen.feeText
                },
            ),
        )
    }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "測試手續費得先建畫面，兩個畫面數字不同",
    question = "第 3 題：UseCase 什麼時候有價值？（業務規則被多個畫面重用）、第 2 題：MVVM、MVI、Clean Architecture、TCA 各解決什麼問題？（依賴只能指向內層）",
    goal = "手續費公式屬於業務規則：Domain 層的 UseCase 只收純資料、回傳結果，不認識任何 UI 框架；畫面（Presentation 層）自己把結果放進狀態。",
    steps = listOf(
        "選「Bug 版」執行，看 log：「單元測試為了驗證公式，建立了幾個畫面物件」，以及兩個畫面對同一個金額顯示的手續費。",
        "想一想：手續費的費率和四捨五入是業務規則，為什麼測它要先有畫面物件？快速下單畫面為什麼只好把公式抄一份？",
        "選「修正版」執行：單元測試建立 0 個畫面物件，兩個畫面顯示一樣的手續費。",
        "對照兩個版本的 CalcFeeUseCase：Bug 版收畫面的狀態物件、算完直接改 MutableState；修正版輸入 FeeRequest、回傳 FeeResult，兩個都是 data class。",
        "進階：用 import 檢查 domain 的檔案：不該出現 androidx.compose、android.*（Context、Log）、kotlinx.coroutines.android。iOS 一樣，UseCase 不該 import SwiftUI 或 UIKit。",
    ),
    expectBug = "「單元測試為了驗證公式，建立了 1 個畫面物件」，「兩個畫面的手續費不一致：2 筆（共 2 筆）」（12345 日圓：一般下單 15、快速下單 14）。",
    expectFixed = "「單元測試為了驗證公式，建立了 0 個畫面物件」，「兩個畫面的手續費不一致：0 筆（共 2 筆）」（兩個畫面都是 15 與 120）。",
    file = "advanced09-architecture/L06_DomainFreeOfUI.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("兩個畫面的手續費不一致") } -> "演練沒有跑完：要用 scenario 執行"
            "單元測試：12345 日圓的手續費 15 日圓（應該是 15）" !in lines ->
                "UseCase 還沒算出手續費：calculate 要把 amount * L06_TAKER_RATE 四捨五入（HALF_UP），寫進 screen.feeText"
            "單元測試為了驗證公式，建立了 1 個畫面物件" !in lines ->
                "Bug 還沒重現：CalcFeeUseCase 要收 OrderScreenState（畫面物件），單元測試才得先建立一個"
            "兩個畫面的手續費不一致：2 筆（共 2 筆）" !in lines ->
                "Bug 還沒重現：快速下單畫面自己抄一份公式，用 RoundingMode.DOWN 無條件捨去，12345 會是 14、99999 會是 119，和一般下單不一樣"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("兩個畫面的手續費不一致") } -> "演練沒有跑完：要用 scenario 執行"
            "單元測試：12345 日圓的手續費 15 日圓（應該是 15）" !in lines ->
                "UseCase 算出來的手續費不對：invoke 要回傳 FeeResult(金額乘以 L06_TAKER_RATE，四捨五入 HALF_UP)"
            "單元測試為了驗證公式，建立了 0 個畫面物件" !in lines ->
                "測試不該建立畫面物件：UseCase 只收 FeeRequest、回傳 FeeResult，不要收 OrderScreenState"
            "99999 日圓：一般下單顯示 120，快速下單顯示 120" !in lines ->
                "兩個畫面都要呼叫同一個 UseCase：99999 日圓的手續費是 120（四捨五入），兩邊都一樣"
            "兩個畫面的手續費不一致：0 筆（共 2 筆）" !in lines -> "兩個畫面都應該呼叫同一個 UseCase，不要各抄一份公式"
            else -> null
        }
    },
)
