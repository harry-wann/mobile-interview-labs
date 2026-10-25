// L06：行情列表每秒更新幾十次，整頁一直重算（iOS 15 沒有 @Observable，怎麼縮小重算範圍）
// 對應複習站：Swift07SwiftUIState 第 6 題「專案最低版本是 iOS 15，不能用 @Observable，行情列表怎麼避免整頁一直重算？」
//
// 情境：行情列表有 10 檔幣種，推播一下子來好幾十筆，其中 BTC 最頻繁、其他幣種偶爾才更新。
// Bug 版：每一筆推播都立刻更新畫面，而且每一列都拿整個 ViewModel（@ObservedObject）。任何一檔更新，10 列的 body 全部重算。
// 修正版：① 資料層先合併（conflation）：推播先存起來，每 200 毫秒才一次更新畫面，同一檔只留最新的一筆。
//        ② 每一列只拿自己這一檔的 Ticker（值型別、遵守 Equatable）：沒變的列，SwiftUI 就跳過它的 body。
import SwiftUI
import LabKit

/// 一檔幣種的報價（兩個版本共用）
private struct Ticker: Identifiable, Equatable {
    let id: String
    var price: Int
}

private func initialTickers() -> [Ticker] {
    ["BTC", "ETH", "XRP", "LTC", "BCH", "ADA", "DOT", "SOL", "DOGE", "LINK"]
        .enumerated().map { Ticker(id: $1, price: 1000 + $0 * 100) }
}

/// 行情推播的接收端（兩個版本共用）
private protocol MarketFeed: AnyObject {
    func receive(index: Int, price: Int)
    /// 合併視窗到了（每 200 毫秒呼叫一次）
    func flush()
    /// ViewModel 的 tickers 被「發布」了幾次（每發布一次，畫面就要檢查一輪）
    var publishCount: Int { get }
}

/// 模擬行情推播：共 count 筆，每 40 毫秒一筆。每 5 筆有 1 筆是其他幣種，其餘都是 BTC。每 200 毫秒呼叫一次 flush
@MainActor
private func pushAll(into feed: some MarketFeed, count: Int = 30) async {
    let flusher = Task { @MainActor in
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(200))
            feed.flush()
        }
    }
    for i in 0..<count {
        let index = i % 5 == 4 ? (i / 5) % 9 + 1 : 0
        feed.receive(index: index, price: 2000 + i)
        try? await Task.sleep(for: .milliseconds(40))
    }
    try? await Task.sleep(for: .milliseconds(250))
    flusher.cancel()
}

/// 演練流程（兩個版本共用）：畫出 10 列 → 推播 30 筆
@MainActor
private func scenario(_ log: LabLog, feed: some MarketFeed, list: some View) async {
    BodyCount.shared.reset(); SwiftUILabProbe.reset()
    let stage = SwiftUILabStage(list)
    await stage.settle()
    log("一開始畫出 \(BodyCount.shared.value("L06 行情列")) 列")
    await pushAll(into: feed)
    await stage.settle(until: { SwiftUILabProbe.value("L06 BTC 那一列") == "2028" })
    log("收到 30 筆推播（24 筆 BTC、6 筆其他幣種），ViewModel 發布了 \(feed.publishCount) 次")
    log("各列的 body 一共算了 \(BodyCount.shared.value("L06 行情列")) 次（包含一開始的 10 次）")
    log("BTC 那一列顯示：\(SwiftUILabProbe.value("L06 BTC 那一列"))")
    stage.remove()
}

/// 畫面類演練的操作區：按「開始推播」，看各列 body 的累計次數
private struct L06Demo<Feed: MarketFeed & ObservableObject, List: View>: View {
    @StateObject private var vm: Feed
    private let makeList: (Feed) -> List
    @State private var running = false

    init(make: @autoclosure @escaping () -> Feed, list: @escaping (Feed) -> List) {
        _vm = StateObject(wrappedValue: make())
        makeList = list
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            makeList(vm)
            Button(running ? "推播中…" : "開始推播（150 筆，約 6 秒）") {
                running = true
                Task {
                    await pushAll(into: vm, count: 150)
                    running = false
                }
            }
            .buttonStyle(.bordered)
            .disabled(running)
            BodyCountView(names: ["L06 行情列"])
        }
    }
}

// MARK: - Bug 版
private enum L06Bug {
    final class MarketViewModel: ObservableObject, MarketFeed {
        @Published var tickers = initialTickers()
        private(set) var publishCount = 0

        func receive(index: Int, price: Int) {
            tickers[index].price = price           // 每一筆推播都立刻更新畫面
            publishCount += 1
        }

        func flush() {}
    }

    struct TickerRow: View {
        let id: String
        @ObservedObject var vm: MarketViewModel    // 整個 ViewModel 都交給每一列

        var body: some View {
            let _ = BodyCount.tick("L06 行情列")
            let price = vm.tickers.first { $0.id == id }?.price ?? 0
            let _ = id == "BTC" ? SwiftUILabProbe.note("L06 BTC 那一列", "\(price)") : true
            Text("\(id)  \(price)").font(.system(.body, design: .monospaced))
        }
    }

    struct MarketList: View {
        @ObservedObject var vm: MarketViewModel

        var body: some View {
            VStack(alignment: .leading) {
                ForEach(vm.tickers) { TickerRow(id: $0.id, vm: vm) }
            }
        }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    final class MarketViewModel: ObservableObject, MarketFeed {
        @Published var tickers = initialTickers()
        private var pending: [Int: Int] = [:]      // 檔次 → 最新的價格；同一檔只留最新的一筆
        private(set) var publishCount = 0

        func receive(index: Int, price: Int) {
            pending[index] = price                 // 先存起來，不更新畫面
        }

        func flush() {
            guard !pending.isEmpty else { return }
            var next = tickers
            for (index, price) in pending { next[index].price = price }
            pending.removeAll()
            tickers = next                         // 一個合併視窗只發布一次
            publishCount += 1
        }
    }

    struct TickerRow: View {
        let ticker: Ticker                         // 只拿自己這一檔的值：沒變，SwiftUI 就跳過這一列

        var body: some View {
            let _ = BodyCount.tick("L06 行情列")
            let _ = ticker.id == "BTC" ? SwiftUILabProbe.note("L06 BTC 那一列", "\(ticker.price)") : true
            Text("\(ticker.id)  \(ticker.price)").font(.system(.body, design: .monospaced))
        }
    }

    struct MarketList: View {
        @ObservedObject var vm: MarketViewModel

        var body: some View {
            VStack(alignment: .leading) {
                ForEach(vm.tickers) { TickerRow(ticker: $0) }
            }
        }
    }
}

// MARK: - 演練設定
extension Swift07SwiftUIState {
    static let l06 = Lab(
        id: "L06",
        title: "行情每秒更新幾十次，整頁一直重算",
        question: "第 6 題：專案最低版本是 iOS 15，不能用 @Observable，行情列表怎麼避免整頁一直重算？",
        goal: "10 檔行情，推播很頻繁但大多是 BTC。不用 @Observable，把各列 body 的計算次數降下來：資料層先合併、每一列只拿自己的資料。",
        steps: [
            "選「Bug 版」，按操作區的「開始推播」，看「body 計算 行情列」的次數一直往上衝。",
            "按「執行」，看 log：30 筆推播，ViewModel 發布了幾次、各列的 body 一共算了幾次。",
            "選「修正版」，同樣按「開始推播」或「執行」，比較兩個數字。",
            "進階：用 Instruments 的 SwiftUI 模板錄兩個版本，比較 View Body 的次數。",
        ],
        expectBug: "30 筆推播，ViewModel 發布了 30 次；各列的 body 算了 310 次（10 列 × 31 次，包含一開始的 10 次）。",
        expectFixed: "ViewModel 只發布約 6 次（每 200 毫秒一次）；各列的 body 一共約 22 次（Debug 實測），沒變的列被跳過。",
        run: { variant, log in
            switch variant {
            case .bug:
                let vm = L06Bug.MarketViewModel()
                await scenario(log, feed: vm, list: L06Bug.MarketList(vm: vm))
            case .fixed:
                let vm = L06Fixed.MarketViewModel()
                await scenario(log, feed: vm, list: L06Fixed.MarketList(vm: vm))
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug: AnyView(L06Demo(make: L06Bug.MarketViewModel()) { L06Bug.MarketList(vm: $0) })
            case .fixed: AnyView(L06Demo(make: L06Fixed.MarketViewModel()) { L06Fixed.MarketList(vm: $0) })
            }
        },
        checkBug: { lines in
            if !lines.contains("一開始畫出 10 列") { return "行情列表一開始要畫出 10 列" }
            if !lines.contains("收到 30 筆推播（24 筆 BTC、6 筆其他幣種），ViewModel 發布了 30 次") { return "Bug 還沒重現：每一筆推播都要立刻更新畫面（發布 30 次）" }
            guard let line = lines.first(where: { $0.hasPrefix("各列的 body 一共算了 ") }),
                  let n = Int(line.dropFirst("各列的 body 一共算了 ".count).prefix(while: \.isNumber)) else { return "還沒有各列 body 的次數" }
            if n < 250 { return "Bug 還沒重現：各列 body 只算了 \(n) 次。每一列都要觀察整個 ViewModel，任何一檔更新，10 列都重算（約 310 次）" }
            if !lines.contains("BTC 那一列顯示：2028") { return "BTC 那一列要顯示最新的價格 2028：每一列要從 vm.tickers 找出自己的價格" }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("一開始畫出 10 列") { return "行情列表一開始要畫出 10 列" }
            guard let publish = lines.first(where: { $0.contains("ViewModel 發布了 ") }),
                  let p = Int(publish.components(separatedBy: "發布了 ").last?.prefix(while: \.isNumber) ?? "") else { return "還沒有 ViewModel 發布次數" }
            if p > 10 { return "ViewModel 發布了 \(p) 次，太多了：推播要先存起來，每 200 毫秒的合併視窗才發布一次（同一檔只留最新的一筆）" }
            if p == 0 { return "畫面一次都沒更新：flush 要把存起來的推播套用到 tickers" }
            guard let line = lines.first(where: { $0.hasPrefix("各列的 body 一共算了 ") }),
                  let n = Int(line.dropFirst("各列的 body 一共算了 ".count).prefix(while: \.isNumber)) else { return "還沒有各列 body 的次數" }
            if n > 40 { return "各列 body 算了 \(n) 次，太多了：每一列只拿自己的 Ticker，沒變的列 SwiftUI 才會跳過" }
            if !lines.contains("BTC 那一列顯示：2028") { return "BTC 那一列要顯示最新的價格 2028：合併時同一檔要留最新的一筆" }
            return nil
        }
    )
}
