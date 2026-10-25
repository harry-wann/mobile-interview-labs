// L06：測試手續費得先建畫面物件，兩個畫面的手續費還不一樣（Domain 層依賴了 UI
// 框架）
// 對應複習站：Advanced09Architecture 第 3 題「UseCase 什麼時候有價值？」
// （業務規則被多個畫面重用）、第 2 題「MVVM、MVI、Clean Architecture、TCA
// 各解決什麼問題？」（Clean 的依賴只能指向內層）
//
// 情境：現貨的 taker（吃單）手續費是成交金額的 0.12%，四捨五入到整數日圓。
// 「一般下單」與「快速下單」兩個畫面都要顯示預估手續費。
//       演練模擬兩件事：
//       ① 寫一個單元測試，驗證 12,345 日圓的手續費是 15 日圓（12,345 × 0.12% =
// 14.814，四捨五入是 15）；
//       ② 兩個畫面各輸入 12,345 與 99,999 日圓，看畫面上顯示的手續費。
// Bug 版：CalcFeeUseCase 的建構子直接收「一般下單畫面」
// 的狀態物件（ObservableObject），算完把結果寫進它的 @Published feeText。
//        要測 UseCase，得先建立一個畫面物件；快速下單畫面的型別不同，
// 用不了這個 UseCase，只好把公式抄一份進自己的狀態物件，
//        抄的時候順手改成無條件捨去：同樣的金額，兩個畫面的手續費不一樣。
// 修正版：Domain 層只收純資料、回傳結果（FeeRequest 進、FeeResult 出，只用到
// Foundation）；兩個畫面各自把結果放進自己的狀態。
//        測試只建 UseCase，不需要任何畫面物件；兩個畫面共用同一個 UseCase，
// 數字一定一致。
import SwiftUI
import LabKit

/// 手續費率與四捨五入（兩個版本共用）
private let a09TakerRate = Decimal(string: "0.0012")!

private func rounded(
    _ value: Decimal, _ mode: NSDecimalNumber.RoundingMode
) -> Decimal {
    var input = value
    var output = Decimal()
    NSDecimalRound(&output, &input, 0, mode)
    return output
}

private let screenObjects = "L06 畫面物件"

/// 兩個版本接線的方式不同：單元測試怎麼驗證公式、兩個畫面各怎麼顯示手續費
private struct Wiring {
    /// 單元測試：驗證 12,345 日圓的手續費，回傳算出來的結果
    let unitTest: () -> String
    /// 一般下單、快速下單：輸入金額之後，畫面上顯示的手續費
    let orderScreenFee: (Decimal) -> String
    let quickScreenFee: (Decimal) -> String
}

/// 演練流程（兩個版本共用）：先數單元測試建立了幾個畫面物件，
/// 再比對兩個畫面的手續費
private func scenario(_ log: LabLog, _ wiring: Wiring) {
    SwiftUILabTally.shared.reset()
    let tested = wiring.unitTest()
    // 單元測試做完，先讀數量，後面兩個畫面也會建立畫面物件
    let created = SwiftUILabTally.shared.count(screenObjects)
    log("單元測試：12345 日圓的手續費 \(tested) 日圓（應該是 15）")
    log("單元測試為了驗證公式，建立了 \(created) 個畫面物件")

    var mismatches = 0
    let amounts: [Decimal] = [12_345, 99_999]
    for amount in amounts {
        let order = wiring.orderScreenFee(amount)
        let quick = wiring.quickScreenFee(amount)
        if order != quick { mismatches += 1 }
        log(
            "\(amount) 日圓：一般下單顯示 \(order)，快速下單顯示 \(quick)\(order == quick ? "" : "（不一致）")"
        )
    }
    log("兩個畫面的手續費不一致：\(mismatches) 筆（共 \(amounts.count) 筆）")
}

// MARK: - Bug 版
private enum L06Bug {
    /// 一般下單畫面的狀態物件
    final class OrderScreenState: ObservableObject {
        @Published var feeText = ""

        init() { SwiftUILabTally.shared.add(screenObjects) }
    }

    /// 「Domain 層」的 UseCase，卻直接收畫面的狀態物件（import 了 SwiftUI）
    struct CalcFeeUseCase {
        let screen: OrderScreenState

        func calculate(amount: Decimal) {
            // 算完直接改畫面的 @Published
            screen.feeText = "\(rounded(amount * a09TakerRate, .plain))"
        }
    }

    /// 快速下單畫面的狀態物件：型別不同，用不了上面的 UseCase，
    /// 只好自己抄一份公式
    final class QuickScreenState: ObservableObject {
        @Published var feeText = ""

        init() { SwiftUILabTally.shared.add(screenObjects) }

        func amountChanged(_ amount: Decimal) {
            // 抄的時候改成無條件捨去
            feeText = "\(rounded(amount * a09TakerRate, .down))"
        }
    }

    static func run(_ log: LabLog) async {
        scenario(
            log,
            Wiring(
                unitTest: {
                    // 想測公式，得先建一個畫面物件
                    let screen = OrderScreenState()
                    CalcFeeUseCase(screen: screen).calculate(amount: 12_345)
                    return screen.feeText
                },
                orderScreenFee: { amount in
                    let screen = OrderScreenState()
                    CalcFeeUseCase(screen: screen).calculate(amount: amount)
                    return screen.feeText
                },
                quickScreenFee: { amount in
                    let screen = QuickScreenState()
                    screen.amountChanged(amount)
                    return screen.feeText
                }
            ))
    }
}

// MARK: - 修正版
private enum L06Fixed {
    /// Domain 層：輸入與輸出都是純資料，不認識任何畫面
    struct FeeRequest {
        let amountJPY: Decimal
    }

    struct FeeResult {
        let fee: Decimal
    }

    struct CalcFeeUseCase {
        func callAsFunction(_ request: FeeRequest) -> FeeResult {
            // 規則只在這一個地方
            FeeResult(fee: rounded(request.amountJPY * a09TakerRate, .plain))
        }
    }

    /// Presentation 層：自己呼叫 UseCase，再把結果放進畫面的狀態
    final class OrderScreenState: ObservableObject {
        @Published var feeText = ""
        private let calcFee: CalcFeeUseCase

        init(calcFee: CalcFeeUseCase) {
            self.calcFee = calcFee
            SwiftUILabTally.shared.add(screenObjects)
        }

        func amountChanged(_ amount: Decimal) {
            feeText = "\(calcFee(FeeRequest(amountJPY: amount)).fee)"
        }
    }

    final class QuickScreenState: ObservableObject {
        @Published var feeText = ""
        private let calcFee: CalcFeeUseCase

        init(calcFee: CalcFeeUseCase) {
            self.calcFee = calcFee
            SwiftUILabTally.shared.add(screenObjects)
        }

        func amountChanged(_ amount: Decimal) {
            // 兩個畫面共用同一個 UseCase
            feeText = "\(calcFee(FeeRequest(amountJPY: amount)).fee)"
        }
    }

    static func run(_ log: LabLog) async {
        let calcFee = CalcFeeUseCase()
        scenario(
            log,
            Wiring(
                // 測試只建 UseCase，沒有任何畫面物件
                unitTest: { "\(calcFee(FeeRequest(amountJPY: 12_345)).fee)" },
                orderScreenFee: { amount in
                    let screen = OrderScreenState(calcFee: calcFee)
                    screen.amountChanged(amount)
                    return screen.feeText
                },
                quickScreenFee: { amount in
                    let screen = QuickScreenState(calcFee: calcFee)
                    screen.amountChanged(amount)
                    return screen.feeText
                }
            ))
    }
}

// MARK: - 演練設定
extension Advanced09Architecture {
    static let l06 = Lab(
        id: "L06",
        title: "測試手續費得先建畫面，兩個畫面數字不同",
        question:
            "第 3 題：UseCase 什麼時候有價值？（業務規則被多個畫面重用）、第 2 題：MVVM、MVI、Clean Architecture、TCA 各解決什麼問題？（依賴只能指向內層）",
        goal:
            "手續費公式屬於業務規則：Domain 層的 UseCase 只收純資料、回傳結果，不認識任何 UI 框架；畫面（Presentation 層）自己把結果放進狀態。",
        steps: [
            "選「Bug 版」執行，看 log：「單元測試為了驗證公式，建立了幾個畫面物件」，以及兩個畫面對同一個金額顯示的手續費。",
            "想一想：手續費的費率和四捨五入是業務規則，為什麼測它要先有畫面物件？快速下單畫面為什麼只好把公式抄一份？",
            "選「修正版」執行：單元測試建立 0 個畫面物件，兩個畫面顯示一樣的手續費。",
            "對照兩個版本的 CalcFeeUseCase：Bug 版收 ObservableObject、算完直接改畫面的 @Published；修正版輸入 FeeRequest、回傳 FeeResult，只用到 Foundation。",
            "進階：Android 一樣，UseCase 不收 MutableState 或 Context，輸入輸出用 data class，ViewModel 再把結果放進 StateFlow。可以用 import 檢查：domain 的檔案不該出現 SwiftUI、androidx.compose、android.*。",
        ],
        expectBug:
            "「單元測試為了驗證公式，建立了 1 個畫面物件」，「兩個畫面的手續費不一致：2 筆（共 2 筆）」（12345 日圓：一般下單 15、快速下單 14）。",
        expectFixed:
            "「單元測試為了驗證公式，建立了 0 個畫面物件」，「兩個畫面的手續費不一致：0 筆（共 2 筆）」（兩個畫面都是 15 與 120）。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("兩個畫面的手續費不一致") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains("單元測試：12345 日圓的手續費 15 日圓（應該是 15）") {
                return
                    "UseCase 還沒算出手續費：calculate 要把 amount * a09TakerRate 四捨五入（.plain），寫進 screen.feeText"
            }
            if !lines.contains("單元測試為了驗證公式，建立了 1 個畫面物件") {
                return
                    "Bug 還沒重現：CalcFeeUseCase 要收 OrderScreenState（畫面物件），單元測試才得先建立一個"
            }
            if !lines.contains("兩個畫面的手續費不一致：2 筆（共 2 筆）") {
                return
                    "Bug 還沒重現：快速下單畫面自己抄一份公式，用 .down 無條件捨去，12345 會是 14、99999 會是 119，和一般下單不一樣"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("兩個畫面的手續費不一致") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains("單元測試：12345 日圓的手續費 15 日圓（應該是 15）") {
                return
                    "UseCase 算出來的手續費不對：callAsFunction 要回傳 FeeResult(fee: 金額乘以 a09TakerRate，四捨五入 .plain)"
            }
            if !lines.contains("單元測試為了驗證公式，建立了 0 個畫面物件") {
                return
                    "測試不該建立畫面物件：UseCase 只收 FeeRequest、回傳 FeeResult，不要收 OrderScreenState"
            }
            if !lines.contains("99999 日圓：一般下單顯示 120，快速下單顯示 120") {
                return "兩個畫面都要呼叫同一個 UseCase：99999 日圓的手續費是 120（四捨五入），兩邊都一樣"
            }
            if !lines.contains("兩個畫面的手續費不一致：0 筆（共 2 筆）") {
                return "兩個畫面都應該呼叫同一個 UseCase，不要各抄一份公式"
            }
            return nil
        }
    )
}
