// L03：數量填了正確的數字，送出按鈕卻還是灰的；改成亂碼，按鈕反而亮著（狀態有兩個地方在管）
// 對應複習站：Advanced09Architecture 第 2 題「MVVM、MVI、Clean Architecture、TCA 各解決什麼問題？代價是什麼？」（單向資料流）
//
// 情境：下單畫面有價格、數量兩個輸入框和一個送出按鈕。按鈕能不能按（canSubmit）取決於兩個欄位是不是都是大於 0 的數字。
//       演練依序輸入：價格 15000000、數量 0.5、價格 15000001、數量 abc，每次輸入之後看按鈕該不該亮。
// Bug 版：MVVM 常見的寫法：price、quantity、canSubmit 各自是一個儲存的屬性。價格改變時有記得重算 canSubmit，
//        數量改變時忘了，canSubmit 就和兩個欄位不一致：數量填對了按鈕還是灰的，數量改成亂碼按鈕反而亮著。
// 修正版：MVI／單向資料流：畫面只有一份 state（price、quantity），所有操作都送進 reduce 這一個地方修改；
//        canSubmit 由 state 算出來（計算屬性），不可能和欄位不一致。
import Foundation
import LabKit

/// 大於 0 的數字才算有效（兩個版本共用）
private func isValid(_ text: String) -> Bool {
    text.range(of: #"^\d+(\.\d+)?$"#, options: .regularExpression) != nil && (Double(text) ?? 0) > 0
}

/// 演練流程（兩個版本共用）：依序輸入，記下每次輸入之後送出按鈕的狀態
private func scenario(
    _ log: LabLog,
    typePrice: (String) -> Void,
    typeQuantity: (String) -> Void,
    canSubmit: () -> Bool
) {
    func button() -> String { canSubmit() ? "可以按" : "不能按" }

    typePrice("15000000")
    log("輸入價格 15000000（數量還是空的）：送出按鈕\(button())")
    typeQuantity("0.5")
    log("輸入數量 0.5：送出按鈕\(button())")
    typePrice("15000001")
    log("價格改成 15000001：送出按鈕\(button())")
    typeQuantity("abc")
    log("數量改成 abc：送出按鈕\(button())")
}

// MARK: - Bug 版
private enum L03Bug {
    final class OrderViewModel {
        private(set) var price = ""
        private(set) var quantity = ""
        private(set) var canSubmit = false                      // 另一個儲存的屬性，要自己記得同步

        func priceChanged(_ text: String) {
            price = text
            canSubmit = isValid(price) && isValid(quantity)
        }

        func quantityChanged(_ text: String) {
            quantity = text                                     // 忘了重算 canSubmit
        }
    }

    static func run(_ log: LabLog) async {
        let viewModel = OrderViewModel()
        scenario(log, typePrice: viewModel.priceChanged, typeQuantity: viewModel.quantityChanged, canSubmit: { viewModel.canSubmit })
    }
}

// MARK: - 修正版
private enum L03Fixed {
    struct OrderState {
        var price = ""
        var quantity = ""
        var canSubmit: Bool { isValid(price) && isValid(quantity) }   // 由兩個欄位算出來，不會不一致
    }

    enum OrderAction {
        case priceChanged(String)
        case quantityChanged(String)
    }

    /// 狀態怎麼變，只有這一個地方決定
    static func reduce(_ state: inout OrderState, _ action: OrderAction) {
        switch action {
        case .priceChanged(let text): state.price = text
        case .quantityChanged(let text): state.quantity = text
        }
    }

    final class OrderViewModel {
        private(set) var state = OrderState()
        func send(_ action: OrderAction) { reduce(&state, action) }
    }

    static func run(_ log: LabLog) async {
        let viewModel = OrderViewModel()
        scenario(
            log,
            typePrice: { viewModel.send(.priceChanged($0)) },
            typeQuantity: { viewModel.send(.quantityChanged($0)) },
            canSubmit: { viewModel.state.canSubmit }
        )
    }
}

// MARK: - 演練設定
extension Advanced09Architecture {
    static let l03 = Lab(
        id: "L03",
        title: "數量填對了按鈕還是灰的",
        question: "第 2 題：MVVM、MVI、Clean Architecture、TCA 各解決什麼問題？代價是什麼？（單向資料流）",
        goal: "能從其他欄位算出來的值，不要另外存一份；畫面的狀態集中在一份 state，只在一個地方修改。",
        steps: [
            "選「Bug 版」執行，看每次輸入之後送出按鈕該不該亮，哪兩次不對？",
            "想一想：canSubmit 是誰負責更新的？有幾個地方會改價格、數量？每多一個欄位要多記得幾個地方？",
            "選「修正版」執行：canSubmit 改成由 state 算出來，四次都對。",
            "進階：Android 的做法是 ViewModel 暴露一個 StateFlow<OrderUiState>，用 data class 的屬性或 derived state 算出 canSubmit。",
        ],
        expectBug: "「輸入數量 0.5：送出按鈕不能按」（應該可以按）；「數量改成 abc：送出按鈕可以按」（應該不能按）。",
        expectFixed: "依序是：不能按、可以按、可以按、不能按，四次都正確。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("輸入價格 15000000（數量還是空的）：送出按鈕不能按") else { return "演練沒有跑完：要用 scenario 執行，價格輸入後（數量空的）按鈕應該不能按" }
            if !lines.contains("輸入數量 0.5：送出按鈕不能按") { return "Bug 還沒重現：quantityChanged 只更新 quantity，沒有重算 canSubmit，數量填對了按鈕還是不能按" }
            if !lines.contains("數量改成 abc：送出按鈕可以按") { return "Bug 還沒重現：quantityChanged 沒有重算 canSubmit，數量改成亂碼時按鈕還是亮著" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("輸入價格 15000000（數量還是空的）：送出按鈕不能按") else { return "價格輸入後（數量空的）按鈕應該不能按" }
            if !lines.contains("輸入數量 0.5：送出按鈕可以按") { return "數量填了 0.5 之後按鈕應該可以按：canSubmit 要由 price、quantity 算出來" }
            if !lines.contains("價格改成 15000001：送出按鈕可以按") { return "價格改成 15000001 之後按鈕應該可以按" }
            if !lines.contains("數量改成 abc：送出按鈕不能按") { return "數量改成 abc 之後按鈕應該不能按：canSubmit 要由 price、quantity 算出來" }
            return nil
        }
    )
}
