// L01：切換市價／限價，打到一半的數量就消失了（structural identity）
// 對應複習站：Swift08SwiftUIIdentity 第 1 題「SwiftUI 怎麼判斷『這是不是同一個
// view』？為什麼切換畫面後狀態會被重置？」
//
// 情境：下單頁有「市價／限價」的切換，兩種模式用同一個下單表單。
// 使用者在數量欄位打了 0.5，一切換，欄位就變回空白。
//
// 你要寫：
//   ① Bug 版：用 if / else 切換市價與限價，兩個分支各寫一個 OrderForm。
//   ② 修正版：只寫一個 OrderForm，模式用參數傳進去，讓 SwiftUI 認為它是同一個
// view。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift08-SwiftUIIdentity/L01_IfElseIdentity.swift
import SwiftUI
import LabKit

private enum OrderMode {
    case market, limit
    var title: String { self == .market ? "市價（成行）" : "限價（指値）" }
}

/// 下單表單的 ViewModel（兩個版本共用）
private final class OrderFormModel: ObservableObject {
    /// 最近建立的那一個。run 用它模擬「使用者在欄位輸入」，不是 bug 的一部分
    nonisolated(unsafe) static weak var latest: OrderFormModel?

    @Published var quantity = ""

    init() {
        SwiftUILabTally.shared.add("L01 OrderFormModel")
        OrderFormModel.latest = self
    }
}

/// 下單表單（兩個版本共用）
private struct OrderForm: View {
    let mode: OrderMode
    @StateObject private var model = OrderFormModel()

    var body: some View {
        let _ = SwiftUILabProbe.note(
            "L01 數量欄位", model.quantity.isEmpty ? "（空白）" : model.quantity)
        VStack(alignment: .leading) {
            Text("目前模式：\(mode.title)")
            TextField("數量", text: $model.quantity).textFieldStyle(
                .roundedBorder)
        }
    }
}

/// 市價／限價的開關（兩個版本共用）
private final class OrderModeSwitch: ObservableObject {
    @Published var isMarket = true
}

/// 演練流程（兩個版本共用）：使用者輸入 0.5 → 切換到限價 → 再切換回市價
@MainActor
private func scenario(
    _ log: LabLog, screen: (OrderModeSwitch) -> some View
) async {
    SwiftUILabProbe.reset(); SwiftUILabTally.shared.reset()
    let mode = OrderModeSwitch()
    let stage = SwiftUILabStage(screen(mode))
    await stage.settle()
    // 使用者在數量欄位輸入 0.5
    OrderFormModel.latest?.quantity = "0.5"
    await stage.settle()
    log("使用者輸入後，數量欄位的內容：\(SwiftUILabProbe.value("L01 數量欄位"))")
    mode.isMarket = false  // 切換到限價
    await stage.settle()
    mode.isMarket = true  // 再切換回市價
    await stage.settle()
    log(
        "切換 2 次後，OrderFormModel 建立了 \(SwiftUILabTally.shared.count("L01 OrderFormModel")) 次"
    )
    log("切換 2 次後，數量欄位的內容：\(SwiftUILabProbe.value("L01 數量欄位"))")
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）
private struct L01Demo: View {
    let variant: LabVariant
    @StateObject private var mode = OrderModeSwitch()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("模式", selection: $mode.isMarket) {
                Text("市價").tag(true)
                Text("限價").tag(false)
            }
            .pickerStyle(.segmented)
            switch variant {
            case .bug: L01Bug.Screen(mode: mode)
            case .fixed: L01Fixed.Screen(mode: mode)
            }
            SwiftUILabTallyView(names: ["L01 OrderFormModel"])
        }
    }
}

// MARK: - Bug 版
private enum L01Bug {
    struct Screen: View {
        @ObservedObject var mode: OrderModeSwitch

        var body: some View {
            // TODO ①：用 if / else 切換：mode.isMarket 時畫 OrderForm(mode:
            // market)，否則畫 OrderForm(mode: limit)（兩個分支各寫一個
            // OrderForm）
            //        現在只畫一個市價的表單，不會有 bug
            OrderForm(mode: .market)
        }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    struct Screen: View {
        @ObservedObject var mode: OrderModeSwitch

        var body: some View {
            // TODO ②：現在是兩個分支各寫一個 OrderForm（Bug 的寫法）。
            // 改成只寫一個 OrderForm，用參數表達市價或限價
            if mode.isMarket {
                OrderForm(mode: .market)
            } else {
                OrderForm(mode: .limit)
            }
        }
    }
}

// MARK: - 演練設定
extension Swift08SwiftUIIdentity {
    static let l01 = Lab(
        id: "L01",
        title: "切換市價／限價，打到一半的數量消失",
        question: "第 1 題：SwiftUI 怎麼判斷「這是不是同一個 view」？為什麼切換畫面後狀態會被重置？",
        goal:
            "下單表單的 ViewModel 是 @StateObject。先寫出「切換時數量消失」的 Bug 版，再改成切換時已經輸入的數量會留下來。",
        steps: [
            "打開 Practice/Swift/Swift08-SwiftUIIdentity/L01_IfElseIdentity.swift，寫 TODO ①。",
            "選「練習」「Bug 版」，在數量欄位輸入 0.5，點上方的「限價」：欄位被清空，下面「OrderFormModel 建立了 N 次」加 1。",
            "按「執行」，看 log：切換 2 次後建立了幾次、數量欄位剩什麼。",
            "寫 TODO ②，選「修正版」，同樣輸入 0.5 再切換：欄位不會被清掉，建立次數維持 1。",
            "對照兩個版本的 Screen：差別只在 body 裡是兩個分支各寫一個 OrderForm，還是只寫一個。",
        ],
        expectBug: "切換 2 次後，OrderFormModel 建立了 3 次，數量欄位變回（空白）。",
        expectFixed: "切換 2 次後，OrderFormModel 只建立 1 次，數量欄位的內容還是 0.5。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log) { L01Bug.Screen(mode: $0) }
            case .fixed: await scenario(log) { L01Fixed.Screen(mode: $0) }
            }
        },
        demo: { variant, _ in AnyView(L01Demo(variant: variant)) },
        checkBug: { lines in
            if !lines.contains("使用者輸入後，數量欄位的內容：0.5") {
                return "下單表單沒有跑起來：使用者輸入 0.5 後，欄位要顯示 0.5"
            }
            if !lines.contains("切換 2 次後，OrderFormModel 建立了 3 次") {
                return "Bug 還沒重現：兩個分支各寫一個 OrderForm，每次切換都要重新建立（共 3 次）"
            }
            if !lines.contains("切換 2 次後，數量欄位的內容：（空白）") {
                return "Bug 還沒重現：切換後數量欄位要被清空"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("使用者輸入後，數量欄位的內容：0.5") {
                return "下單表單沒有跑起來：使用者輸入 0.5 後，欄位要顯示 0.5"
            }
            if !lines.contains("切換 2 次後，OrderFormModel 建立了 1 次") {
                return "OrderFormModel 應該只建立 1 次：不要用兩個分支，改成只寫一個 OrderForm"
            }
            if !lines.contains("切換 2 次後，數量欄位的內容：0.5") {
                return "切換後數量欄位的內容應該還是 0.5"
            }
            return nil
        }
    )
}
