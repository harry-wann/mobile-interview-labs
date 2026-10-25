// L07：行情列表每秒更新幾十次，畫面卡頓（資料層合併、不在 body 裡格式化）
// 對應複習站：Swift08SwiftUIIdentity 第 7 題「行情列表每秒更新很多次，
// 怎麼避免整頁卡頓？怎麼確認真的有改善？」
//
// 情境：行情列表有 200 檔，推播每 25 毫秒送來一份「全部幣種」的新報價（約每秒
// 40 次），每一列要把價格格式化成有千分位的文字。
// Bug 版：每一份推播都立刻更新畫面，而且每一列在 body 裡自己 new 一個
// NumberFormatter 來格式化。
//        每秒 40 次 × 200 列，主執行緒一直在建立 formatter、重算 body，FPS
// 掉下來。
// 修正版：資料層用 TickBatcher 合併（每 200 毫秒才送出一次，
// 每個幣種只留最新的一筆），格式化在資料層用快取的 formatter 做好，
//        每一列的 body 只顯示現成的文字。
import SwiftUI
import LabKit

private let symbolCount = 200

/// 一檔幣種的報價（兩個版本共用）
private struct Quote: Identifiable, Equatable {
    let id: Int
    var price: Int
    // 格式化好的價格；修正版在資料層算好，Bug 版留空
    var text: String
}

private func initialQuotes() -> [Quote] {
    (0..<symbolCount).map { Quote(id: $0, price: 1_000_000 + $0, text: "") }
}

private func nowMs() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
}

/// 建立一個千分位的 formatter，並記下建立了幾次（兩個版本共用）
private func makeFormatter() -> NumberFormatter {
    SwiftUILabTally.shared.add("L07 NumberFormatter")
    let f = NumberFormatter()
    f.numberStyle = .decimal
    return f
}

/// 行情推播的接收端（兩個版本共用）
private protocol QuoteFeed: AnyObject {
    /// 收到一份「全部幣種」的新報價，tick 是第幾份
    func receiveSnapshot(tick: Int)
    /// ViewModel 的 quotes 被「發布」了幾次
    var publishCount: Int { get }
}

/// 模擬行情推播：共 count 份，每 25 毫秒一份
@MainActor
private func pushAll(into feed: some QuoteFeed, count: Int = 40) async {
    for tick in 1...count {
        feed.receiveSnapshot(tick: tick)
        try? await Task.sleep(for: .milliseconds(25))
    }
}

/// 演練流程（兩個版本共用）：畫出 200 列 → 推播 40 份，
/// 同時記錄主執行緒最長停了多久
@MainActor
private func scenario(
    _ log: LabLog, feed: some QuoteFeed, list: some View
) async {
    BodyCount.shared.reset(); SwiftUILabTally.shared.reset()
    let stage = SwiftUILabStage(list)
    await stage.settle()
    SwiftUILabTally.shared.reset()  // 只算推播期間
    BodyCount.shared.reset()

    var hitches = 0, blockedMs = 0
    // 每 5 毫秒排一次，看實際隔了多久：主執行緒被卡住，間隔就會變長
    let sampler = Task { @MainActor in
        var last = nowMs()
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(5))
            let t = nowMs()
            let gap = Int(t - last)
            // 超過 60 Hz 的一格（16.7 毫秒）才算卡頓
            if gap > 16 { hitches += 1 }
            if gap > 5 { blockedMs += gap - 5 }
            last = t
        }
    }
    await pushAll(into: feed)
    await stage.settle(ms: 300)
    sampler.cancel()

    log("推播 40 份，ViewModel 發布了 \(feed.publishCount) 次")
    log(
        "推播期間，NumberFormatter 建立了 \(SwiftUILabTally.shared.count("L07 NumberFormatter")) 次"
    )
    log("推播期間，各列的 body 一共算了 \(BodyCount.shared.value("L07 行情列")) 次")
    log("主執行緒卡頓（單次超過 16 ms）\(hitches) 次，累計卡住約 \(blockedMs) ms（Debug 的數字，只供參考）")
    stage.remove()
}

/// 畫面類演練的操作區：按「開始推播」，看 FPS 與卡頓次數
private struct L07Demo<Feed: QuoteFeed & ObservableObject, List: View>: View {
    @StateObject private var vm: Feed
    private let makeList: (Feed) -> List
    @State private var running = false

    init(
        make: @autoclosure @escaping () -> Feed, list: @escaping (Feed) -> List
    ) {
        _vm = StateObject(wrappedValue: make())
        makeList = list
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FrameMeter()
            Button(running ? "推播中…" : "開始推播（每 25 毫秒一份，共 200 份）") {
                running = true
                Task {
                    await pushAll(into: vm, count: 200)
                    running = false
                }
            }
            .buttonStyle(.bordered)
            .disabled(running)
            makeList(vm)
                .frame(height: 220)
        }
    }
}

// MARK: - Bug 版
private enum L07Bug {
    final class MarketViewModel: ObservableObject, QuoteFeed {
        @Published var quotes = initialQuotes()
        private(set) var publishCount = 0

        func receiveSnapshot(tick: Int) {
            // 每一份推播都立刻更新
            quotes = quotes.map {
                Quote(id: $0.id, price: 1_000_000 + $0.id + tick * 7, text: "")
            }
            publishCount += 1
        }
    }

    struct QuoteRow: View {
        let quote: Quote

        var body: some View {
            let _ = BodyCount.tick("L07 行情列")
            // 在 body 裡建立 formatter：每次重算都再建一個
            let text =
                makeFormatter().string(from: NSNumber(value: quote.price)) ?? ""
            Text("#\(quote.id)　\(text)").font(
                .system(.body, design: .monospaced))
        }
    }

    struct MarketList: View {
        @ObservedObject var vm: MarketViewModel

        var body: some View {
            ScrollView {
                VStack(alignment: .leading) {
                    ForEach(vm.quotes) { QuoteRow(quote: $0) }
                }
            }
        }
    }
}

// MARK: - 修正版
private enum L07Fixed {
    /// 合併更新：每個幣種只留最新的一筆，到了固定間隔才一次送出
    struct TickBatcher<Value> {
        let intervalMs: Int64
        private var pending: [String: Value] = [:]  // 幣種 -> 最新的值
        private var lastFlush: Int64?

        init(intervalMs: Int64) { self.intervalMs = intervalMs }

        mutating func push(
            _ symbol: String, _ value: Value, now: Int64
        ) -> [String: Value]? {
            // 同一個幣種，後來的蓋掉先來的
            pending[symbol] = value
            // 還沒到間隔，先不送
            if let last = lastFlush, now - last < intervalMs { return nil }
            let batch = pending
            pending = [:]
            lastFlush = now
            // 到了間隔，一次送出所有幣種的最新值
            return batch
        }
    }

    final class MarketViewModel: ObservableObject, QuoteFeed {
        /// 快取的 formatter：整個 ViewModel 只建立一次
        private static let formatter = makeFormatter()

        @Published var quotes: [Quote]
        private var batcher = TickBatcher<Int>(intervalMs: 200)
        private(set) var publishCount = 0

        init() {
            quotes = initialQuotes().map {
                Quote(
                    id: $0.id, price: $0.price, text: Self.text(for: $0.price))
            }
        }

        func receiveSnapshot(tick: Int) {
            let now = nowMs()
            var latest: [String: Int]?
            for id in 0..<symbolCount {
                if let batch = batcher.push(
                    "\(id)", 1_000_000 + id + tick * 7, now: now)
                {
                    latest = batch
                }
            }
            // 還沒到合併視窗：不更新畫面
            guard let batch = latest else { return }
            var next = quotes
            for (key, price) in batch {
                guard let id = Int(key) else { continue }
                // 格式化在資料層做好
                next[id] = Quote(
                    id: id, price: price, text: Self.text(for: price))
            }
            // 一個合併視窗只發布一次
            quotes = next
            publishCount += 1
        }

        private static func text(for price: Int) -> String {
            formatter.string(from: NSNumber(value: price)) ?? ""
        }
    }

    struct QuoteRow: View {
        let quote: Quote

        var body: some View {
            let _ = BodyCount.tick("L07 行情列")
            // 只顯示現成的文字
            Text("#\(quote.id)　\(quote.text)").font(
                .system(.body, design: .monospaced))
        }
    }

    struct MarketList: View {
        @ObservedObject var vm: MarketViewModel

        var body: some View {
            ScrollView {
                VStack(alignment: .leading) {
                    ForEach(vm.quotes) { QuoteRow(quote: $0) }
                }
            }
        }
    }
}

// MARK: - 演練設定
extension Swift08SwiftUIIdentity {
    static let l07 = Lab(
        id: "L07",
        title: "行情每秒更新幾十次，畫面卡頓",
        question: "第 7 題：行情列表每秒更新很多次，怎麼避免整頁卡頓？怎麼確認真的有改善？",
        goal:
            "200 檔行情、每秒約 40 份推播，每一列要把價格格式化成千分位。用資料層合併加上「不在 body 裡格式化」，把卡頓降下來，並用數字確認有改善。",
        steps: [
            "選「Bug 版」，按操作區的「開始推播」，看上方的 FPS、卡頓次數與最長一格：FPS 掉下來，卡頓次數一直增加。",
            "按「執行」，看 log：ViewModel 發布了幾次、NumberFormatter 建立了幾次、各列的 body 算了幾次。",
            "選「修正版」，同樣按「開始推播」或「執行」，比較同樣的數字。",
            "進階：Product › Profile 開 Instruments 的 SwiftUI 模板（Long View Body Updates、Cause & Effect Graph）錄 Bug 版，找出最耗時的 view body；改成 Release 再比一次。",
        ],
        expectBug:
            "40 份推播，ViewModel 發布了 40 次；NumberFormatter 建立了 8000 次（200 列 × 40 次，每次重算都再建一個）；主執行緒卡頓約 40 次、累計卡住約 0.9 秒（Debug 實測），FPS 掉下來。",
        expectFixed:
            "ViewModel 只發布約 5 次（每 200 毫秒一次）；推播期間 NumberFormatter 建立 0 次（formatter 在 ViewModel 建立時就做好了）；各列的 body 只算 800 次左右；主執行緒卡頓只有約 4 次。",
        run: { variant, log in
            switch variant {
            case .bug:
                let vm = L07Bug.MarketViewModel()
                await scenario(log, feed: vm, list: L07Bug.MarketList(vm: vm))
            case .fixed:
                let vm = L07Fixed.MarketViewModel()
                await scenario(log, feed: vm, list: L07Fixed.MarketList(vm: vm))
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug:
                AnyView(
                    L07Demo(make: L07Bug.MarketViewModel()) {
                        L07Bug.MarketList(vm: $0)
                    })
            case .fixed:
                AnyView(
                    L07Demo(make: L07Fixed.MarketViewModel()) {
                        L07Fixed.MarketList(vm: $0)
                    })
            }
        },
        checkBug: { lines in
            if !lines.contains("推播 40 份，ViewModel 發布了 40 次") {
                return "Bug 還沒重現：每一份推播都要立刻更新畫面（發布 40 次）"
            }
            guard
                let line = lines.first(where: {
                    $0.hasPrefix("推播期間，NumberFormatter 建立了 ")
                }),
                let n = Int(
                    line.dropFirst("推播期間，NumberFormatter 建立了 ".count).prefix(
                        while: \.isNumber))
            else { return "還沒有 NumberFormatter 建立的次數" }
            if n < 1000 {
                return
                    "Bug 還沒重現：NumberFormatter 只建立了 \(n) 次。每一列在 body 裡都要自己 new 一個（makeFormatter()）"
            }
            return nil
        },
        checkFixed: { lines in
            guard
                let publish = lines.first(where: {
                    $0.hasPrefix("推播 40 份，ViewModel 發布了 ")
                }),
                let p = Int(
                    publish.dropFirst("推播 40 份，ViewModel 發布了 ".count).prefix(
                        while: \.isNumber))
            else { return "還沒有 ViewModel 發布次數" }
            if p == 0 {
                return
                    "畫面一次都沒更新：TickBatcher 到了間隔要送出最新的一批，ViewModel 再把它套用到 quotes"
            }
            if p > 10 {
                return "ViewModel 發布了 \(p) 次，太多了：要用 TickBatcher 每 200 毫秒才送出一次"
            }
            guard
                let line = lines.first(where: {
                    $0.hasPrefix("推播期間，NumberFormatter 建立了 ")
                }),
                let n = Int(
                    line.dropFirst("推播期間，NumberFormatter 建立了 ".count).prefix(
                        while: \.isNumber))
            else { return "還沒有 NumberFormatter 建立的次數" }
            if n > 2 {
                return
                    "推播期間 NumberFormatter 又建立了 \(n) 次：格式化要在資料層用快取的 formatter 做好，每一列的 body 只顯示現成的文字（quote.text）"
            }
            return nil
        }
    )
}
