// L03：換了一筆委託單，輸入框卻還顯示上一筆的價格（用參數初始化 @State）
// 對應複習站：Swift07SwiftUIState 第 3 題「為什麼用參數去初始化 @State，上層改了值，子層卻沒有跟著變？」
//
// 情境：委託單列表旁邊有一個改價畫面，輸入框的初始值是上層傳進來的價格。使用者點另一筆委託單，上層傳了新的價格，輸入框卻沒變。
//
// 你要寫：
//   ① Bug 版：改價畫面的輸入框，要用上層傳進來的價格當初始值（用 @State 保管輸入框的值）。
//   ② 修正版：改選另一筆委託單時，要讓 SwiftUI 把舊的改價畫面丟掉、重新建立一個。想想要在 PriceEditor 後面加什麼。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift07-SwiftUIState/L03_StateInitialValue.swift
import SwiftUI
import LabKit

/// 委託單（兩個版本共用）
private struct Order {
    let id: String
    let price: Int

    static let all = [Order(id: "A", price: 15_000_000), Order(id: "B", price: 16_000_000)]
    static func with(id: String) -> Order { all.first { $0.id == id } ?? all[0] }
}

/// 目前選中的委託單（兩個版本共用）
private final class OrderSelection: ObservableObject {
    @Published var id = "A"
}

/// 演練流程（兩個版本共用）：先選第一筆，再改選第二筆
@MainActor
private func scenario(_ log: LabLog, screen: (OrderSelection) -> some View) async {
    SwiftUILabProbe.reset()
    let selection = OrderSelection()
    let stage = SwiftUILabStage(screen(selection))
    await stage.settle()
    log("選第一筆（A）：輸入框顯示 \(SwiftUILabProbe.value("L03 輸入框"))")
    selection.id = "B"
    await stage.settle()
    log("改選第二筆（B）：上層傳進來的價格是 \(SwiftUILabProbe.value("L03 傳進來的價格"))")
    log("改選第二筆（B）：輸入框顯示 \(SwiftUILabProbe.value("L03 輸入框"))")
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）：用上方的選擇器換一筆委託單
private struct L03Demo: View {
    let variant: LabVariant
    @StateObject private var selection = OrderSelection()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("委託單", selection: $selection.id) {
                ForEach(Order.all, id: \.id) { Text("委託單 \($0.id)").tag($0.id) }
            }
            .pickerStyle(.segmented)
            switch variant {
            case .bug: L03Bug.Screen(selection: selection)
            case .fixed: L03Fixed.Screen(selection: selection)
            }
        }
    }
}

// MARK: - Bug 版
private enum L03Bug {
    struct PriceEditor: View {
        let orderID: String
        let price: Int                        // 上層傳進來的價格，每次都是最新的
        @State private var local: Int         // 輸入框正在編輯的值

        init(orderID: String, price: Int) {
            self.orderID = orderID
            self.price = price
            // TODO ①：用傳進來的 price 當 local 的初始值（用 State(initialValue:) 建立 _local）。現在寫的是 0，輸入框一開始就不對
            _local = State(initialValue: 0)
        }

        var body: some View {
            let _ = SwiftUILabProbe.note("L03 輸入框", "\(local)")
            let _ = SwiftUILabProbe.note("L03 傳進來的價格", "\(price)")
            VStack(alignment: .leading) {
                Text("委託單 \(orderID)：上層傳進來的價格 \(price)")
                TextField("價格", value: $local, format: .number.grouping(.never))
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
            }
        }
    }

    struct Screen: View {
        @ObservedObject var selection: OrderSelection

        var body: some View {
            let order = Order.with(id: selection.id)
            PriceEditor(orderID: order.id, price: order.price)      // 沒有 .id：換了一筆，還是同一個 view
        }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    struct PriceEditor: View {
        let orderID: String
        let price: Int
        @State private var local: Int

        init(orderID: String, price: Int) {
            self.orderID = orderID
            self.price = price
            _local = State(initialValue: price)
        }

        var body: some View {
            let _ = SwiftUILabProbe.note("L03 輸入框", "\(local)")
            let _ = SwiftUILabProbe.note("L03 傳進來的價格", "\(price)")
            VStack(alignment: .leading) {
                Text("委託單 \(orderID)：上層傳進來的價格 \(price)")
                TextField("價格", value: $local, format: .number.grouping(.never))
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
            }
        }
    }

    struct Screen: View {
        @ObservedObject var selection: OrderSelection

        var body: some View {
            let order = Order.with(id: selection.id)
            PriceEditor(orderID: order.id, price: order.price)
            // TODO ②：在上一行後面加一個 modifier，用委託單的 id 告訴 SwiftUI「id 不同就是不同的 view」
        }
    }
}

// MARK: - 演練設定
extension Swift07SwiftUIState {
    static let l03 = Lab(
        id: "L03",
        title: "換了一筆委託單，輸入框還是舊價格",
        question: "第 3 題：為什麼用參數去初始化 @State，上層改了值，子層卻沒有跟著變？",
        goal: "改價畫面的輸入框用上層傳進來的價格當初始值。先寫出「改選另一筆，輸入框卻沒換」的 Bug 版，再修到輸入框換成那一筆的價格。",
        steps: [
            "打開 Practice/Swift/Swift07-SwiftUIState/L03_StateInitialValue.swift，寫 TODO ①。",
            "選「練習」「Bug 版」，點上方選擇器的「委託單 B」：上面一行已經是 B 的價格，輸入框卻還是 A 的價格。",
            "按「執行」，看 log：傳進來的價格和輸入框顯示的價格不一樣。",
            "寫 TODO ②，選「修正版」，再點一次「委託單 B」：輸入框跟著換成 B 的價格。",
            "對照兩個版本的 Screen：只差在 PriceEditor 後面有沒有 .id。",
        ],
        expectBug: "改選 B 之後，上層傳進來的價格是 16000000，輸入框卻還顯示 15000000。",
        expectFixed: "改選 B 之後，輸入框顯示 16000000：.id 換了，改價畫面重新建立。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log) { L03Bug.Screen(selection: $0) }
            case .fixed: await scenario(log) { L03Fixed.Screen(selection: $0) }
            }
        },
        demo: { variant, _ in AnyView(L03Demo(variant: variant)) },
        checkBug: { lines in
            if !lines.contains("選第一筆（A）：輸入框顯示 15000000") { return "輸入框一開始要顯示上層傳進來的價格 15000000：用 State(initialValue:) 把價格複製成 @State" }
            if !lines.contains("改選第二筆（B）：上層傳進來的價格是 16000000") { return "改選之後，上層傳進來的價格應該是 16000000" }
            if !lines.contains("改選第二筆（B）：輸入框顯示 15000000") { return "Bug 還沒重現：改選 B 之後，輸入框應該還停在 A 的價格 15000000" }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("選第一筆（A）：輸入框顯示 15000000") { return "輸入框一開始要顯示上層傳進來的價格 15000000" }
            if !lines.contains("改選第二筆（B）：輸入框顯示 16000000") { return "改選 B 之後，輸入框應該換成 16000000：想想怎麼讓 SwiftUI 把舊的改價畫面丟掉重建" }
            return nil
        }
    )
}
