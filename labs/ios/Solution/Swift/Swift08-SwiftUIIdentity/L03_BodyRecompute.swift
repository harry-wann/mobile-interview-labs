// L03：父層每更新一次，沒變的子 view 也跟著重算（每次都 new 一個物件傳下去）
// 對應複習站：Swift08SwiftUIIdentity 第 3 題「body
// 為什麼會被重新計算？重新計算就等於重新畫畫面嗎？」
//
// 情境：報價頁上方有一個每秒更新的計數，下面一列顯示固定的委託價。
// 為了格式化價格，父層在 body 裡 new 了一個 PriceStyle（包著 NumberFormatter）
// 傳給子 view。
// Bug 版：每次父層的 body 計算，都 new 一個新的 PriceStyle 傳進 PriceRow。
// 每次都是不同的物件，SwiftUI 判斷「欄位有變」，子 view 的 body 每次都重算。
// 修正版：子 view 只收「格式化好的字串」（值型別），格式化用快取的 formatter
// 做。字串沒變，子 view 的 body 就被跳過。
import SwiftUI
import LabKit

/// 報價更新計數（兩個版本共用）：每次 tick()，上層的 view 就重新計算 body
private final class QuoteClock: ObservableObject {
    @Published private(set) var updates = 0
    func tick() { updates += 1 }
}

/// 把 NumberFormatter 包成 class（Bug 版用）
private final class PriceStyle {
    let formatter = NumberFormatter()

    init() {
        formatter.numberStyle = .decimal
        SwiftUILabTally.shared.add("L03 PriceStyle")
    }

    func text(_ price: Int) -> String {
        formatter.string(from: NSNumber(value: price)) ?? "\(price)"
    }
}

/// 演練流程（兩個版本共用）：父層更新 3 次，看 PriceRow 的 body 算了幾次
@MainActor
private func scenario(_ log: LabLog, screen: (QuoteClock) -> some View) async {
    BodyCount.shared.reset(); SwiftUILabTally.shared.reset()
    let clock = QuoteClock()
    let stage = SwiftUILabStage(screen(clock))
    await stage.settle()
    for _ in 1...3 {
        clock.tick()
        await stage.settle()
    }
    log(
        "父層更新 3 次（父層的 body 共算 4 次），PriceStyle 建立了 \(SwiftUILabTally.shared.count("L03 PriceStyle")) 次"
    )
    log(
        "父層更新 3 次後，PriceRow 的 body 算了 \(BodyCount.shared.value("L03 PriceRow")) 次"
    )
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）：按「更新」讓父層重新計算
private struct L03Demo: View {
    let variant: LabVariant
    @StateObject private var clock = QuoteClock()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch variant {
            case .bug: L03Bug.Screen(clock: clock)
            case .fixed: L03Fixed.Screen(clock: clock)
            }
            Button("父層更新一次") { clock.tick() }
                .buttonStyle(.bordered)
            BodyCountView(names: ["L03 PriceRow"])
            SwiftUILabTallyView(names: ["L03 PriceStyle"])
        }
    }
}

// MARK: - Bug 版
private enum L03Bug {
    struct PriceRow: View {
        let price: Int
        let style: PriceStyle

        var body: some View {
            let _ = BodyCount.tick("L03 PriceRow")
            Text("委託價 \(style.text(price))")
        }
    }

    struct Screen: View {
        @ObservedObject var clock: QuoteClock

        var body: some View {
            VStack(alignment: .leading) {
                Text("報價已更新 \(clock.updates) 次")
                // 每次 body 都 new 一個新物件傳下去
                PriceRow(price: 15_000_000, style: PriceStyle())
            }
        }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    /// 格式化只做一次：formatter 只建立一次，字串也只算一次（實務上放在資料層，
    /// body 只拿現成的字串）
    static let priceText: String = {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        return f.string(from: 15_000_000) ?? ""
    }()

    struct PriceRow: View {
        // 只收格式化好的字串（值型別）
        let priceText: String

        var body: some View {
            let _ = BodyCount.tick("L03 PriceRow")
            Text("委託價 \(priceText)")
        }
    }

    struct Screen: View {
        @ObservedObject var clock: QuoteClock

        var body: some View {
            VStack(alignment: .leading) {
                Text("報價已更新 \(clock.updates) 次")
                // 字串沒變，子 view 就被跳過
                PriceRow(priceText: L03Fixed.priceText)
            }
        }
    }
}

// MARK: - 演練設定
extension Swift08SwiftUIIdentity {
    static let l03 = Lab(
        id: "L03",
        title: "沒變的子 view，每次都跟著重算",
        question: "第 3 題：body 為什麼會被重新計算？重新計算就等於重新畫畫面嗎？",
        goal: "父層的報價計數每更新一次，下面那列固定的委託價完全沒變。找出為什麼它的 body 每次都被重算，讓它被跳過。",
        steps: [
            "選「Bug 版」，按操作區的「父層更新一次」幾次，看「body 計算 PriceRow」每按一次就加 1，「PriceStyle 建立了」也每次加 1。",
            "按「執行」，看 log：父層更新 3 次後，PriceRow 的 body 算了幾次。",
            "選「修正版」，同樣按「父層更新一次」：PriceRow 的 body 維持 1 次，沒有 PriceStyle 被建立。",
            "進階：在 Bug 版 PriceRow 的 body 開頭加 let _ = Self._printChanges()，看主控台印出這次重算的原因。",
        ],
        expectBug:
            "父層更新 3 次後，PriceStyle 建立了 4 次，PriceRow 的 body 算了 4 次：每次都是新物件，SwiftUI 判斷欄位有變。",
        expectFixed: "父層更新 3 次後，PriceRow 的 body 只算 1 次：傳進去的字串沒變，後面都被跳過。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log) { L03Bug.Screen(clock: $0) }
            case .fixed: await scenario(log) { L03Fixed.Screen(clock: $0) }
            }
        },
        demo: { variant, _ in AnyView(L03Demo(variant: variant)) },
        checkBug: { lines in
            if !lines.contains("父層更新 3 次後，PriceRow 的 body 算了 4 次") {
                return
                    "Bug 還沒重現：每次父層重算，都要 new 一個新的 PriceStyle 傳給 PriceRow，讓它的 body 每次都重算（共 4 次）"
            }
            if !lines.contains("父層更新 3 次（父層的 body 共算 4 次），PriceStyle 建立了 4 次") {
                return "Bug 版的父層每次 body 都要 new 一個 PriceStyle（共 4 次）"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("父層更新 3 次後，PriceRow 的 body 算了 1 次") {
                return
                    "PriceRow 的 body 應該只算 1 次：只傳值型別（格式化好的字串）給子 view，不要每次 new 新物件"
            }
            return nil
        }
    )
}
