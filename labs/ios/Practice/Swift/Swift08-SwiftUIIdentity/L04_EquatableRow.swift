// L04：每一檔行情的列都跟著重算（傳 closure 的子 view 沒辦法比較）
// 對應複習站：Swift08SwiftUIIdentity 第 4 題「怎麼讓沒有變的子 view 被跳過，
// 不要白做工？」
//
// 情境：行情列表有 3 檔，每一列有一個 onTap closure，
// 內容會用到父層「目前更新了幾次」。每次更新只有 BTC 的價格在變，ETH、XRP
// 都沒變。
//
// 你要寫：
//   ① Bug 版：每一列的 onTap closure 捕獲每次都在變的 updates，讓 SwiftUI
// 沒辦法比較。
//   ② 修正版：TickerRow 遵守 Equatable，只比較 ticker，並用 equatable() 告訴
// SwiftUI。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift08-SwiftUIIdentity/L04_EquatableRow.swift
import SwiftUI
import LabKit

private struct Ticker: Equatable, Identifiable {
    let id: String
    let price: Int
}

/// 報價更新計數（兩個版本共用）：每次 tick()，上層的 view 就重新計算 body
private final class QuoteClock: ObservableObject {
    @Published private(set) var updates = 0
    func tick() { updates += 1 }

    /// 目前的 3 檔行情：每次更新只有 BTC 的價格在變
    var tickers: [Ticker] {
        [
            Ticker(id: "BTC", price: 1_000 + updates),
            Ticker(id: "ETH", price: 500), Ticker(id: "XRP", price: 60),
        ]
    }
}

/// 演練流程（兩個版本共用）：父層更新 3 次，看每一列的 body 一共算了幾次
@MainActor
private func scenario(_ log: LabLog, screen: (QuoteClock) -> some View) async {
    BodyCount.shared.reset()
    let clock = QuoteClock()
    let stage = SwiftUILabStage(screen(clock))
    await stage.settle()
    for _ in 1...3 {
        clock.tick()
        await stage.settle()
    }
    log("父層更新 3 次後，BTC 那一列的 body 算了 \(BodyCount.shared.value("L04 BTC 列")) 次")
    log("父層更新 3 次後，ETH 那一列的 body 算了 \(BodyCount.shared.value("L04 ETH 列")) 次")
    log("父層更新 3 次後，XRP 那一列的 body 算了 \(BodyCount.shared.value("L04 XRP 列")) 次")
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）
private struct L04Demo: View {
    let variant: LabVariant
    @StateObject private var clock = QuoteClock()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch variant {
            case .bug: L04Bug.Screen(clock: clock)
            case .fixed: L04Fixed.Screen(clock: clock)
            }
            Button("報價更新一次（只有 BTC 變）") { clock.tick() }
                .buttonStyle(.bordered)
            BodyCountView(names: ["L04 BTC 列", "L04 ETH 列", "L04 XRP 列"])
        }
    }
}

// MARK: - Bug 版
private enum L04Bug {
    struct TickerRow: View {
        let ticker: Ticker
        // closure 沒辦法比較
        let onTap: () -> Void

        var body: some View {
            let _ = BodyCount.tick("L04 \(ticker.id) 列")
            HStack {
                Text(ticker.id)
                Spacer()
                Text("\(ticker.price)").monospacedDigit()
            }
            .onTapGesture(perform: onTap)
        }
    }

    struct Screen: View {
        @ObservedObject var clock: QuoteClock

        var body: some View {
            let updates = clock.updates
            VStack {
                ForEach(clock.tickers) { t in
                    // TODO ①：onTap 的 closure 現在是空的，不會有 bug。在
                    // closure 裡呼叫 SwiftUILabProbe.note("L04 最後一次點擊",
                    // …)，
                    //        內容要用到上面的 updates（closure
                    // 捕獲每次都在變的值，SwiftUI 就沒辦法比較它）
                    TickerRow(ticker: t) {
                    }
                }
            }
        }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    // TODO ②-1：讓 TickerRow 遵守 Equatable，寫 == 只比較 ticker（closure
    // 不比）；提醒：View 預設在主執行緒，== 前面加 nonisolated
    struct TickerRow: View {
        let ticker: Ticker
        let onTap: () -> Void

        var body: some View {
            let _ = BodyCount.tick("L04 \(ticker.id) 列")
            HStack {
                Text(ticker.id)
                Spacer()
                Text("\(ticker.price)").monospacedDigit()
            }
            .onTapGesture(perform: onTap)
        }
    }

    struct Screen: View {
        @ObservedObject var clock: QuoteClock

        var body: some View {
            let updates = clock.updates
            VStack {
                ForEach(clock.tickers) { t in
                    // TODO ②-2：在 TickerRow 後面加上 modifier，告訴 SwiftUI
                    // 比較時用你定義的 ==
                    TickerRow(ticker: t) {
                        SwiftUILabProbe.note(
                            "L04 最後一次點擊", "\(t.id)，第 \(updates) 次更新時")
                    }
                }
            }
        }
    }
}

// MARK: - 演練設定
extension Swift08SwiftUIIdentity {
    static let l04 = Lab(
        id: "L04",
        title: "沒變的行情列，每次都跟著重算",
        question: "第 4 題：怎麼讓沒有變的子 view 被跳過，不要白做工？",
        goal: "每次報價更新只有 BTC 的價格變。先寫出三列的 body 都被重算的 Bug 版，再讓 ETH、XRP 這兩列被跳過。",
        steps: [
            "打開 Practice/Swift/Swift08-SwiftUIIdentity/L04_EquatableRow.swift，寫 TODO ①。",
            "選「練習」「Bug 版」，按操作區的「報價更新一次」幾次，看三列的「body 計算」次數全部一起增加。",
            "按「執行」，看 log：父層更新 3 次後，三列各算了幾次。",
            "寫 TODO ②，選「修正版」，同樣操作：只有 BTC 那一列增加，ETH、XRP 維持 1 次。",
            "對照兩個版本的 TickerRow：修正版多了 Equatable 與 .equatable()。",
        ],
        expectBug:
            "父層更新 3 次後，BTC、ETH、XRP 三列的 body 都算了 4 次：closure 每次都不一樣，SwiftUI 沒辦法比較。",
        expectFixed:
            "BTC 那一列算了 4 次（價格每次都變），ETH、XRP 各只算 1 次：== 只比較 ticker，沒變的列被跳過。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log) { L04Bug.Screen(clock: $0) }
            case .fixed: await scenario(log) { L04Fixed.Screen(clock: $0) }
            }
        },
        demo: { variant, _ in AnyView(L04Demo(variant: variant)) },
        checkBug: { lines in
            if !lines.contains("父層更新 3 次後，BTC 那一列的 body 算了 4 次") {
                return "BTC 的價格每次都變，BTC 那一列的 body 應該算 4 次"
            }
            if !lines.contains("父層更新 3 次後，ETH 那一列的 body 算了 4 次") {
                return
                    "Bug 還沒重現：ETH 沒變，但它的 body 也要每次都重算（4 次）：TickerRow 要傳一個會捕獲 updates 的 closure"
            }
            if !lines.contains("父層更新 3 次後，XRP 那一列的 body 算了 4 次") {
                return "Bug 還沒重現：XRP 沒變，但它的 body 也要每次都重算（4 次）"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("父層更新 3 次後，BTC 那一列的 body 算了 4 次") {
                return "BTC 的價格每次都變，BTC 那一列的 body 應該算 4 次"
            }
            if !lines.contains("父層更新 3 次後，ETH 那一列的 body 算了 1 次") {
                return
                    "ETH 沒變，它的 body 應該只算 1 次：TickerRow 遵守 Equatable、== 只比較 ticker，並加上 .equatable()"
            }
            if !lines.contains("父層更新 3 次後，XRP 那一列的 body 算了 1 次") {
                return "XRP 沒變，它的 body 應該只算 1 次"
            }
            return nil
        }
    )
}
