// L02：輸入的數量過一秒就被清空（@ObservedObject 與 @StateObject）
// 對應複習站：Swift07SwiftUIState 第 2 題「常見的 bug：用 @ObservedObject 建立 ViewModel，為什麼狀態會被重置？」
//
// 情境：下單頁上方有「報價剩餘 N 秒」的倒數，每秒更新一次。使用者剛在數量欄位輸入 0.5，一秒後欄位又變回空白。
// Bug 版：下單頁用 @ObservedObject 加上預設值 = OrderViewModel()。@ObservedObject 不擁有物件，
//        倒數更新時上層的 body 重新計算，下單頁的 struct 被重新建立，預設值又 new 出一個全新的 ViewModel。
// 修正版：自己建立的 ViewModel 用 @StateObject。它的初始值是 autoclosure，SwiftUI 只在這個 view 第一次出現時執行。
import SwiftUI
import LabKit

/// 下單頁的 ViewModel（兩個版本共用）
private final class OrderViewModel: ObservableObject {
    /// 最近建立的那一個。run 用它模擬「使用者在欄位輸入」，不是 bug 的一部分
    nonisolated(unsafe) static weak var latest: OrderViewModel?

    @Published var quantity = ""

    init() {
        SwiftUILabTally.shared.add("L02 OrderViewModel")
        LiveObjects.shared.add("L02 OrderViewModel")
        OrderViewModel.latest = self
    }

    deinit { LiveObjects.shared.remove("L02 OrderViewModel") }
}

/// 報價倒數（兩個版本共用）：每次 tick()，secondsLeft 就變一次，讓上層的 view 重新計算 body
private final class QuoteClock: ObservableObject {
    @Published private(set) var secondsLeft = 30
    func tick() { secondsLeft -= 1 }
}

/// 演練流程（兩個版本共用）：放上下單頁 → 使用者輸入 0.5 → 倒數更新 3 次
@MainActor
private func scenario(_ log: LabLog, orderView: some View, screen: (QuoteClock) -> some View) async {
    SwiftUILabProbe.reset(); SwiftUILabTally.shared.reset()
    log("vm 的 property wrapper：\(swiftUILabWrapperName(of: orderView, property: "vm"))")
    let clock = QuoteClock()
    let stage = SwiftUILabStage(screen(clock))
    await stage.settle()
    OrderViewModel.latest?.quantity = "0.5"          // 使用者在數量欄位輸入 0.5
    await stage.settle()
    log("使用者輸入後，數量欄位的內容：\(SwiftUILabProbe.value("L02 數量欄位"))")
    for _ in 1...3 {                                 // 倒數更新 3 次，上層的 body 重新計算 3 次
        clock.tick()
        await stage.settle()
    }
    log("倒數更新 3 次後，OrderViewModel 建立了 \(SwiftUILabTally.shared.count("L02 OrderViewModel")) 次")
    log("倒數更新 3 次後，數量欄位的內容：\(SwiftUILabProbe.value("L02 數量欄位"))")
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）：倒數每秒更新一次，在數量欄位輸入一個數字，看它會不會被清掉
private struct L02Demo: View {
    let variant: LabVariant
    @StateObject private var clock = QuoteClock()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch variant {
            case .bug: L02Bug.Screen(clock: clock)
            case .fixed: L02Fixed.Screen(clock: clock)
            }
            SwiftUILabTallyView(names: ["L02 OrderViewModel"])
            LiveObjectsView(names: ["L02 OrderViewModel"])
        }
        .task {
            for _ in 1...30 {
                try? await Task.sleep(for: .seconds(1))
                clock.tick()
            }
        }
    }
}

// MARK: - Bug 版
private enum L02Bug {
    struct OrderView: View {
        @ObservedObject var vm = OrderViewModel()      // 不擁有，卻自己建立：每次 struct 重建都 new 一個

        var body: some View {
            let _ = SwiftUILabProbe.note("L02 數量欄位", vm.quantity.isEmpty ? "（空白）" : vm.quantity)
            TextField("數量", text: $vm.quantity).textFieldStyle(.roundedBorder)
        }
    }

    struct Screen: View {
        @ObservedObject var clock: QuoteClock          // 每秒更新一次的倒數

        var body: some View {
            VStack(alignment: .leading) {
                Text("報價剩餘 \(clock.secondsLeft) 秒")
                OrderView()                            // 上層的 body 每算一次，就重新建立一次 OrderView
            }
        }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    struct OrderView: View {
        @StateObject private var vm = OrderViewModel() // 自己建立並擁有：SwiftUI 保管，只建立一次

        var body: some View {
            let _ = SwiftUILabProbe.note("L02 數量欄位", vm.quantity.isEmpty ? "（空白）" : vm.quantity)
            TextField("數量", text: $vm.quantity).textFieldStyle(.roundedBorder)
        }
    }

    struct Screen: View {
        @ObservedObject var clock: QuoteClock

        var body: some View {
            VStack(alignment: .leading) {
                Text("報價剩餘 \(clock.secondsLeft) 秒")
                OrderView()
            }
        }
    }
}

// MARK: - 演練設定
extension Swift07SwiftUIState {
    static let l02 = Lab(
        id: "L02",
        title: "輸入的數量過一秒就被清空",
        question: "第 2 題：常見的 bug：用 @ObservedObject 建立 ViewModel，為什麼狀態會被重置？",
        goal: "下單頁上方的倒數每秒更新一次，數量欄位剛輸入的內容卻被清空。找出原因，改成 ViewModel 只建立一次。",
        steps: [
            "選「Bug 版」，在數量欄位輸入 0.5，等一秒：欄位被清空，下面「OrderViewModel 建立了 N 次」每秒加 1。",
            "按「執行」，看 log：倒數更新 3 次後，OrderViewModel 被建立了幾次、欄位內容還剩什麼。",
            "選「修正版」，同樣輸入 0.5：欄位不會被清掉，建立次數維持 1。",
            "對照兩個版本的 OrderView：只差在 vm 前面的 property wrapper。",
        ],
        expectBug: "vm 的 property wrapper 是 ObservedObject；倒數更新 3 次後，OrderViewModel 建立了 4 次，數量欄位變回（空白）。",
        expectFixed: "vm 的 property wrapper 是 StateObject；OrderViewModel 只建立 1 次，數量欄位的內容還是 0.5。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log, orderView: L02Bug.OrderView(), screen: { L02Bug.Screen(clock: $0) })
            case .fixed: await scenario(log, orderView: L02Fixed.OrderView(), screen: { L02Fixed.Screen(clock: $0) })
            }
        },
        demo: { variant, _ in AnyView(L02Demo(variant: variant)) },
        checkBug: { lines in
            if !lines.contains("vm 的 property wrapper：ObservedObject") {
                return "Bug 版的 vm 要用 @ObservedObject 加預設值（目前：\(lines.first { $0.hasPrefix("vm 的") } ?? "還沒執行")）"
            }
            if !lines.contains("使用者輸入後，數量欄位的內容：0.5") { return "下單頁沒有跑起來：使用者輸入 0.5 後，欄位要顯示 0.5" }
            if !lines.contains("倒數更新 3 次後，OrderViewModel 建立了 4 次") { return "Bug 還沒重現：倒數每更新一次，OrderViewModel 就要被重新建立一次（共 4 次）" }
            if !lines.contains("倒數更新 3 次後，數量欄位的內容：（空白）") { return "Bug 還沒重現：數量欄位的內容要被清空" }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("vm 的 property wrapper：StateObject") {
                return "修正版的 vm 要用 @StateObject（目前：\(lines.first { $0.hasPrefix("vm 的") } ?? "還沒執行")）"
            }
            if !lines.contains("倒數更新 3 次後，OrderViewModel 建立了 1 次") { return "OrderViewModel 應該只建立 1 次：自己建立的 ViewModel 要讓 SwiftUI 保管" }
            if !lines.contains("倒數更新 3 次後，數量欄位的內容：0.5") { return "倒數更新後，數量欄位的內容應該還是 0.5" }
            return nil
        }
    )
}
