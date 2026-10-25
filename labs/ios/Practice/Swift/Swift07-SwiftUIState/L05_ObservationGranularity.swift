// L05：只更新 BTC 的價格，ETH 那一列也跟著重算（ObservableObject 與
// @Observable）
// 對應複習站：Swift07SwiftUIState 第 5 題「ObservableObject 和 iOS 17 的
// @Observable 差在哪？」
//
// 情境：行情頁的 ViewModel 放了 BTC、ETH 兩個價格，各有一列 view
// 只讀自己那一個。BTC 的價格更新時，ETH 那一列完全不需要重算。
//
// 你要寫：
//   ① Bug 版：ViewModel 用 ObservableObject（已經寫好），讓 btc、eth
// 兩個屬性變動時通知畫面。
//   ② 修正版：ViewModel 改用 iOS 17 的 Observation，讓 SwiftUI
// 只重算讀到變動屬性的那一列。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift07-SwiftUIState/L05_ObservationGranularity.swift
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
        // TODO ①：這兩個屬性要加上什麼標記，值一變 ObservableObject
        // 才會通知畫面？
        var btc = 100
        var eth = 50
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
    // TODO ②：在 class 前面加上 iOS 17 Observation 框架的
    // macro（屬性不用再逐一標記）
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
            "同一個 ViewModel 放 BTC、ETH 兩個價格，各有一列只讀自己的價格。先寫出「只更新 BTC，ETH 那一列也重算」的 Bug 版，再改成只有 BTC 那一列重算。",
        steps: [
            "打開 Practice/Swift/Swift07-SwiftUIState/L05_ObservationGranularity.swift，寫 TODO ①。",
            "選「練習」「Bug 版」，按操作區的「BTC 價格 +1」幾次，看「body 計算」：ETH 那一列的次數也跟著加。",
            "按「執行」，看 log：只更新 BTC 後，ETH 那一列算了幾次。",
            "寫 TODO ②，選「修正版」，同樣按「BTC 價格 +1」：只有 BTC 那一列增加，ETH 那一列維持 1 次；按「ETH 價格 +1」才會換 ETH 增加。",
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
