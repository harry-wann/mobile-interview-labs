// L01：行情一直在更新，畫面上的價格卻不動（@State 與 @StateObject）
// 對應複習站：Swift07SwiftUIState 第 1 題「@State、@Binding、@StateObject、
// @ObservedObject 到底差在哪？」
//
// 情境：行情頁自己建立一個 ViewModel，ViewModel 每收到一筆報價就更新
// @Published 的 price，畫面顯示 price。
//
// 你要寫：
//   ① Bug 版：ViewModel（class）用錯誤的 property wrapper 保管，畫面不會跟著
// price 變。
//   ② 修正版：view 自己建立的 ViewModel，要用哪個 property wrapper？
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift07-SwiftUIState/L01_StateVsStateObject.swift
import SwiftUI
import LabKit

/// 行情 ViewModel（兩個版本共用）：模擬行情推播，每隔一段時間收到一筆新報價
private final class TickerViewModel: ObservableObject {
    @Published var price = 100

    init() {
        SwiftUILabTally.shared.add("L01 TickerViewModel")
        SwiftUILabProbe.note("L01 ViewModel 的價格", "100")
    }

    @MainActor func feed(ticks: Int, interval: Duration) async {
        for i in 1...ticks {
            try? await Task.sleep(for: interval)
            price = 100 + i
            SwiftUILabProbe.note("L01 ViewModel 的價格", "\(price)")
        }
    }
}

/// 畫面類演練的操作區（兩個版本共用）：行情頁每秒收到一筆新報價
private struct L01Demo: View {
    let variant: LabVariant

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch variant {
            case .bug: L01Bug.Screen(ticks: 30, interval: .seconds(1))
            case .fixed: L01Fixed.Screen(ticks: 30, interval: .seconds(1))
            }
            SwiftUILabProbeText(
                title: "ViewModel 實際的價格", name: "L01 ViewModel 的價格")
            BodyCountView(names: ["L01 行情頁"])
            SwiftUILabTallyView(names: ["L01 TickerViewModel"])
        }
    }
}

// MARK: - Bug 版
private enum L01Bug {
    struct Screen: View {
        var ticks = 3
        var interval = Duration.milliseconds(60)
        // TODO ①：在 vm 前面加上 property wrapper（也加上 private）。Bug
        // 版要用「給簡單的值（數字、字串、開關）用的那個」來保管這個
        // ViewModel（很多人會這樣誤用）
        var vm = TickerViewModel()

        var body: some View {
            let _ = BodyCount.tick("L01 行情頁")
            let _ = SwiftUILabProbe.note("L01 畫面上的價格", "\(vm.price)")
            Text("BTC/JPY  \(vm.price)")
                .font(.title2.monospacedDigit())
                .task { await vm.feed(ticks: ticks, interval: interval) }
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        SwiftUILabProbe.reset(); BodyCount.shared.reset();
        SwiftUILabTally.shared.reset()
        let screen = Screen()
        log(
            "vm 的 property wrapper：\(swiftUILabWrapperName(of: screen, property: "vm"))"
        )
        let stage = SwiftUILabStage(screen)
        // 等推播送完，再等畫面更新
        await stage.settle(until: {
            SwiftUILabProbe.value("L01 ViewModel 的價格") == "103"
        })
        log("ViewModel 的價格：\(SwiftUILabProbe.value("L01 ViewModel 的價格"))")
        log("畫面上的價格：\(SwiftUILabProbe.value("L01 畫面上的價格"))")
        log("行情頁的 body 計算了 \(BodyCount.shared.value("L01 行情頁")) 次")
        stage.remove()
    }
}

// MARK: - 修正版
private enum L01Fixed {
    struct Screen: View {
        var ticks = 3
        var interval = Duration.milliseconds(60)
        // TODO ②：在 vm 前面加上 property wrapper（也加上 private）。這個
        // ViewModel 是這個 view 自己建立的，要用哪一個才會同時「保管」又「觀察
        // @Published」？
        var vm = TickerViewModel()

        var body: some View {
            let _ = BodyCount.tick("L01 行情頁")
            let _ = SwiftUILabProbe.note("L01 畫面上的價格", "\(vm.price)")
            Text("BTC/JPY  \(vm.price)")
                .font(.title2.monospacedDigit())
                .task { await vm.feed(ticks: ticks, interval: interval) }
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        SwiftUILabProbe.reset(); BodyCount.shared.reset();
        SwiftUILabTally.shared.reset()
        let screen = Screen()
        log(
            "vm 的 property wrapper：\(swiftUILabWrapperName(of: screen, property: "vm"))"
        )
        let stage = SwiftUILabStage(screen)
        // 等推播送完，再等畫面更新
        await stage.settle(until: {
            SwiftUILabProbe.value("L01 ViewModel 的價格") == "103"
        })
        log("ViewModel 的價格：\(SwiftUILabProbe.value("L01 ViewModel 的價格"))")
        log("畫面上的價格：\(SwiftUILabProbe.value("L01 畫面上的價格"))")
        log("行情頁的 body 計算了 \(BodyCount.shared.value("L01 行情頁")) 次")
        stage.remove()
    }
}

// MARK: - 演練設定
extension Swift07SwiftUIState {
    static let l01 = Lab(
        id: "L01",
        title: "行情一直更新，畫面上的價格卻不動",
        question: "第 1 題：@State、@Binding、@StateObject、@ObservedObject 到底差在哪？",
        goal:
            "行情頁自己建立 ViewModel，ViewModel 的價格每秒變一次。先寫出畫面不跟著變的 Bug 版，再用「誰建立，誰擁有」的規則修好。",
        steps: [
            "打開 Practice/Swift/Swift07-SwiftUIState/L01_StateVsStateObject.swift，寫 TODO ①。",
            "選「練習」「Bug 版」，看上方操作區：BTC/JPY 的價格停在 100，但下面「ViewModel 實際的價格」一直在增加。",
            "看「body 計算」：價格變了好幾次，行情頁的 body 卻只算 1 次，因為 @State 不會通知畫面。",
            "按「執行」，看 log 裡「畫面上的價格」和「ViewModel 的價格」是否一樣。",
            "寫 TODO ②，選「修正版」，再看操作區與 log：畫面的價格跟著變，body 算的次數也增加了。",
        ],
        expectBug:
            "vm 的 property wrapper 是 State；ViewModel 的價格是 103，畫面上的價格卻停在 100；行情頁的 body 只算 1 次。",
        expectFixed:
            "vm 的 property wrapper 是 StateObject；畫面上的價格跟著變成 103；行情頁的 body 算了 4 次（第一次加上三次價格更新）。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        demo: { variant, _ in AnyView(L01Demo(variant: variant)) },
        checkBug: { lines in
            if !lines.contains("vm 的 property wrapper：State") {
                return
                    "Bug 版的 vm 要用 @State 宣告（目前：\(lines.first { $0.hasPrefix("vm 的") } ?? "還沒執行")）"
            }
            if !lines.contains("ViewModel 的價格：103") {
                return "行情頁沒有跑起來：ViewModel 的價格應該被推播更新到 103"
            }
            if !lines.contains("畫面上的價格：100") {
                return "Bug 還沒重現：畫面上的價格應該停在 100，不會跟著變"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("vm 的 property wrapper：StateObject") {
                return
                    "修正版的 vm 要用 @StateObject 宣告（目前：\(lines.first { $0.hasPrefix("vm 的") } ?? "還沒執行")）"
            }
            if !lines.contains("ViewModel 的價格：103") {
                return "行情頁沒有跑起來：ViewModel 的價格應該被推播更新到 103"
            }
            if !lines.contains("畫面上的價格：103") {
                return "畫面上的價格要跟著變成 103：ViewModel 的 @Published 變動時畫面要重算"
            }
            return nil
        }
    )
}
