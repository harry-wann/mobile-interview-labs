// L05：只更新 BTC 的價格，ETH 那一列也跟著重算（ObservableObject 與
// @Observable）
// 對應複習站：Swift07SwiftUIState 第 5 題「ObservableObject 和 iOS 17 的
// @Observable 差在哪？」
//
// 情境：行情頁的 ViewModel 放了 BTC、ETH 兩個價格，各有一列 view
// 只讀自己那一個。BTC 的價格更新時，ETH 那一列完全不需要重算。
// Bug 版：ViewModel 用 ObservableObject。任何一個 @Published 變了，
// 所有觀察這個物件的 view 都會重算 body，不管它有沒有讀到那個屬性。
// 修正版：改用 iOS 17 的 @Observable。SwiftUI 記錄 body 實際讀了哪些屬性，
// 只有讀過 btc 的那一列才會重算。
import SwiftUI
import Observation
import LabKit

/// 演練流程（兩個版本共用）：畫出兩列 → 只更新 BTC 的價格
@MainActor
private func scenario(
    _ log: LabLog, screen: some View, updateBTC: () -> Void
) async {
    BodyCount.shared.reset()
    let stage = SwiftUILabStage(screen)
    await stage.settle()
    log(
        "一開始：BTC 那一列算了 \(BodyCount.shared.value("L05 BTC 那一列")) 次，ETH 那一列算了 \(BodyCount.shared.value("L05 ETH 那一列")) 次"
    )
    // 只有 BTC 的價格變了
    updateBTC()
    await stage.settle()
    log("只更新 BTC 的價格後：BTC 那一列算了 \(BodyCount.shared.value("L05 BTC 那一列")) 次")
    log("只更新 BTC 的價格後：ETH 那一列算了 \(BodyCount.shared.value("L05 ETH 那一列")) 次")
    stage.remove()
}

// MARK: - Bug 版
private enum L05Bug {
    final class MarketModel: ObservableObject {
        @Published var btc = 100
        @Published var eth = 50
    }

    struct BTCRow: View {
        @ObservedObject var model: MarketModel

        var body: some View {
            let _ = BodyCount.tick("L05 BTC 那一列")
            Text("BTC  \(model.btc)").font(.title3.monospacedDigit())
        }
    }

    struct ETHRow: View {
        @ObservedObject var model: MarketModel

        var body: some View {
            let _ = BodyCount.tick("L05 ETH 那一列")
            Text("ETH  \(model.eth)").font(.title3.monospacedDigit())
        }
    }

    struct Screen: View {
        let model: MarketModel

        var body: some View {
            VStack(alignment: .leading) {
                BTCRow(model: model)
                ETHRow(model: model)
            }
        }
    }

    struct Demo: View {
        @StateObject private var model = MarketModel()

        var body: some View {
            VStack(alignment: .leading, spacing: 8) {
                Screen(model: model)
                HStack {
                    Button("BTC 價格 +1") { model.btc += 1 }
                    Button("ETH 價格 +1") { model.eth += 1 }
                }
                .buttonStyle(.bordered)
                BodyCountView(names: ["L05 BTC 那一列", "L05 ETH 那一列"])
            }
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        let model = MarketModel()
        await scenario(log, screen: Screen(model: model)) { model.btc += 1 }
    }
}

// MARK: - 修正版
private enum L05Fixed {
    @Observable
    final class MarketModel {
        var btc = 100
        var eth = 50
    }

    struct BTCRow: View {
        let model: MarketModel

        var body: some View {
            let _ = BodyCount.tick("L05 BTC 那一列")
            Text("BTC  \(model.btc)").font(.title3.monospacedDigit())
        }
    }

    struct ETHRow: View {
        let model: MarketModel

        var body: some View {
            let _ = BodyCount.tick("L05 ETH 那一列")
            Text("ETH  \(model.eth)").font(.title3.monospacedDigit())
        }
    }

    struct Screen: View {
        let model: MarketModel

        var body: some View {
            VStack(alignment: .leading) {
                BTCRow(model: model)
                ETHRow(model: model)
            }
        }
    }

    struct Demo: View {
        @State private var model = MarketModel()

        var body: some View {
            VStack(alignment: .leading, spacing: 8) {
                Screen(model: model)
                HStack {
                    Button("BTC 價格 +1") { model.btc += 1 }
                    Button("ETH 價格 +1") { model.eth += 1 }
                }
                .buttonStyle(.bordered)
                BodyCountView(names: ["L05 BTC 那一列", "L05 ETH 那一列"])
            }
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        let model = MarketModel()
        await scenario(log, screen: Screen(model: model)) { model.btc += 1 }
    }
}

// MARK: - 演練設定
extension Swift07SwiftUIState {
    static let l05 = Lab(
        id: "L05",
        title: "只更新 BTC，ETH 那一列也跟著重算",
        question: "第 5 題：ObservableObject 和 iOS 17 的 @Observable 差在哪？",
        goal:
            "同一個 ViewModel 放 BTC、ETH 兩個價格，各有一列只讀自己的價格。比較只更新 BTC 時，ETH 那一列的 body 會不會被重算。",
        steps: [
            "選「Bug 版」，按操作區的「BTC 價格 +1」幾次，看「body 計算」：ETH 那一列的次數也跟著加。",
            "按「執行」，看 log：只更新 BTC 後，ETH 那一列算了幾次。",
            "選「修正版」，同樣按「BTC 價格 +1」：只有 BTC 那一列增加，ETH 那一列維持 1 次；按「ETH 價格 +1」才會換 ETH 增加。",
            "對照兩個版本：差別在 ViewModel 的宣告（ObservableObject 加 @Published／@Observable），以及列怎麼拿到 ViewModel（@ObservedObject／直接 let）。",
        ],
        expectBug: "只更新 BTC 的價格後，BTC 那一列算了 2 次，ETH 那一列也算了 2 次。",
        expectFixed: "只更新 BTC 的價格後，BTC 那一列算了 2 次，ETH 那一列還是 1 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug: AnyView(L05Bug.Demo())
            case .fixed: AnyView(L05Fixed.Demo())
            }
        },
        checkBug: { lines in
            if !lines.contains("一開始：BTC 那一列算了 1 次，ETH 那一列算了 1 次") {
                return "兩列一開始都要畫出來（body 各算 1 次）"
            }
            if !lines.contains("只更新 BTC 的價格後：BTC 那一列算了 2 次") {
                return "BTC 的價格變了，BTC 那一列要重算：ViewModel 的屬性要讓畫面知道它變了（@Published）"
            }
            if !lines.contains("只更新 BTC 的價格後：ETH 那一列算了 2 次") {
                return "Bug 還沒重現：ObservableObject 變動時，ETH 那一列的 body 也要被重算"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("一開始：BTC 那一列算了 1 次，ETH 那一列算了 1 次") {
                return "兩列一開始都要畫出來（body 各算 1 次）"
            }
            if !lines.contains("只更新 BTC 的價格後：BTC 那一列算了 2 次") {
                return "BTC 的價格變了，BTC 那一列要重算：ViewModel 要用 iOS 17 的 @Observable"
            }
            if !lines.contains("只更新 BTC 的價格後：ETH 那一列算了 1 次") {
                return "ETH 那一列不該重算：它沒有讀 btc"
            }
            return nil
        }
    )
}
