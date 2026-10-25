// L01：K 線圖每來一筆報價就整張重畫，閃爍（updateUIView 每次都重設）
// 對應複習站：Swift09UIKitInterop 第 3 題「UIViewRepresentable 的每個方法什麼時候被呼叫？Coordinator 是做什麼的？」
//
// 情境：K 線圖是 UIKit 的 view（CandleChartView），用 UIViewRepresentable 包進 SwiftUI。每一筆新報價進來，SwiftUI 就呼叫一次 updateUIView。
// Bug 版：updateUIView 每次都把整份 K 線重設一遍（reload）。每來一筆就整張圖重畫，畫面閃爍，K 線越多越慢。
// 修正版：用 Coordinator 記住上次推送到哪一根（lastPushedTime），updateUIView 只推送這根之後的 K 線，不重設整張圖。
import SwiftUI
import UIKit
import LabKit

/// 一根 K 線（兩個版本共用）
private struct Candle {
    let time: Int
    let close: Double
}

/// UIKit 的 K 線圖（兩個版本共用）：記下「整張重設了幾次」「一共推送了幾根 K 線」
private final class CandleChartView: UIView {
    private var candles: [Candle] = []
    private var pushed = 0

    /// 整張重設：丟掉原本的，換成這一份
    func reload(_ all: [Candle]) {
        SwiftUILabTally.shared.add("L01 整張圖重設")
        candles = all
        pushed += all.count
        SwiftUILabProbe.note("L01 推送的 K 線", "\(pushed)")
    }

    /// 新增或取代一根（以 time 為 key）
    func update(_ candle: Candle) {
        if let i = candles.firstIndex(where: { $0.time == candle.time }) { candles[i] = candle } else { candles.append(candle) }
        pushed += 1
        SwiftUILabProbe.note("L01 推送的 K 線", "\(pushed)")
    }
}

/// K 線資料（兩個版本共用）：開始有 100 根，每次 append() 多一根
private final class CandleFeed: ObservableObject {
    @Published private(set) var candles = (0..<100).map { Candle(time: $0, close: 1_000_000 + Double($0)) }

    func append() {
        let next = candles.count
        candles.append(Candle(time: next, close: 1_000_000 + Double(next)))
    }
}

/// K 線圖那一頁（兩個版本共用）：觀察 K 線資料，每多一根就用新的資料重新建立 Representable，SwiftUI 就會呼叫 updateUIView
private struct L01Screen: View {
    let variant: LabVariant
    @ObservedObject var feed: CandleFeed

    var body: some View {
        switch variant {
        case .bug: L01Bug.ChartRepresentable(candles: feed.candles).frame(height: 60)
        case .fixed: L01Fixed.ChartRepresentable(candles: feed.candles).frame(height: 60)
        }
    }
}

/// 演練流程（兩個版本共用）：畫出 100 根 K 線 → 新報價進來 3 次，每次多一根
@MainActor
private func scenario(_ log: LabLog, variant: LabVariant) async {
    SwiftUILabProbe.reset(); SwiftUILabTally.shared.reset()
    let feed = CandleFeed()
    let stage = SwiftUILabStage(L01Screen(variant: variant, feed: feed))
    await stage.settle()
    for _ in 1...3 {
        feed.append()
        await stage.settle()
    }
    log("updateUIView 被呼叫了 \(SwiftUILabTally.shared.count("L01 updateUIView")) 次")
    log("整張圖被重設了 \(SwiftUILabTally.shared.count("L01 整張圖重設")) 次")
    log("一共推送了 \(SwiftUILabProbe.value("L01 推送的 K 線")) 根 K 線（最後畫面上有 \(feed.candles.count) 根）")
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）：按「來一筆新報價」，看整張圖被重設的次數
private struct L01Demo: View {
    let variant: LabVariant
    @StateObject private var feed = CandleFeed()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            L01Screen(variant: variant, feed: feed)
            Button("來一筆新報價") { feed.append() }
                .buttonStyle(.bordered)
            SwiftUILabTallyView(names: ["L01 updateUIView", "L01 整張圖重設"])
            SwiftUILabProbeText(title: "一共推送的 K 線", name: "L01 推送的 K 線")
        }
    }
}

// MARK: - Bug 版
private enum L01Bug {
    struct ChartRepresentable: UIViewRepresentable {
        let candles: [Candle]

        func makeUIView(context: Context) -> CandleChartView { CandleChartView() }

        func updateUIView(_ view: CandleChartView, context: Context) {
            SwiftUILabTally.shared.add("L01 updateUIView")
            view.reload(candles)                    // 每次更新都把整份 K 線重設一遍
        }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    struct ChartRepresentable: UIViewRepresentable {
        let candles: [Candle]

        func makeCoordinator() -> Coordinator { Coordinator() }
        func makeUIView(context: Context) -> CandleChartView { CandleChartView() }

        func updateUIView(_ view: CandleChartView, context: Context) {
            SwiftUILabTally.shared.add("L01 updateUIView")
            let coordinator = context.coordinator
            // 只推送上次之後的 K 線；比上次更早的時間點跳過（最後一根可能還在變，所以用 >=）
            for candle in candles where candle.time >= (coordinator.lastPushedTime ?? Int.min) {
                view.update(candle)
                coordinator.lastPushedTime = candle.time
            }
        }

        final class Coordinator {
            var lastPushedTime: Int?                // 跨更新保存的狀態
        }
    }
}

// MARK: - 演練設定
extension Swift09UIKitInterop {
    static let l01 = Lab(
        id: "L01",
        title: "每來一筆報價，K 線圖就整張重畫",
        question: "第 3 題：UIViewRepresentable 的每個方法什麼時候被呼叫？Coordinator 是做什麼的？",
        goal: "K 線圖是 UIKit 的 view。每一筆新報價都會讓 SwiftUI 呼叫 updateUIView，找出怎麼只推送新的 K 線，不要每次整張重設。",
        steps: [
            "選「Bug 版」，按操作區的「來一筆新報價」幾次：「整張圖重設」每按一次加 1，「一共推送的 K 線」一次增加上百根。",
            "按「執行」，看 log：新報價進來 3 次後，整張圖被重設了幾次、一共推送了幾根 K 線。",
            "選「修正版」，同樣操作：整張圖不會被重設，每次只多推送一兩根。",
            "注意 updateUIView 被呼叫的次數：兩個版本都會多於報價的筆數，所以裡面的程式一定要能重複執行、而且便宜。",
        ],
        expectBug: "新報價進來 3 次後，整張圖被重設了 4 次（含第一次），一共推送了 406 根 K 線（100＋101＋102＋103）。",
        expectFixed: "整張圖重設 0 次，一共只推送 106 根 K 線（第一次 100 根，之後每次重推最後一根再加上新的一根）。",
        run: { variant, log in await scenario(log, variant: variant) },
        demo: { variant, _ in AnyView(L01Demo(variant: variant)) },
        checkBug: { lines in
            guard let line = lines.first(where: { $0.hasPrefix("整張圖被重設了 ") }),
                  let n = Int(line.dropFirst("整張圖被重設了 ".count).prefix(while: \.isNumber)) else { return "還沒有整張圖重設的次數" }
            if n < 4 { return "Bug 還沒重現：updateUIView 每次被呼叫，都要把整份 K 線重設一遍（至少 4 次）" }
            return nil
        },
        checkFixed: { lines in
            guard let line = lines.first(where: { $0.hasPrefix("整張圖被重設了 ") }),
                  let n = Int(line.dropFirst("整張圖被重設了 ".count).prefix(while: \.isNumber)) else { return "還沒有整張圖重設的次數" }
            if n != 0 { return "整張圖被重設了 \(n) 次：updateUIView 不要整張重設，只推送上次之後的 K 線（用 Coordinator 記住 lastPushedTime）" }
            guard let push = lines.first(where: { $0.hasPrefix("一共推送了 ") }),
                  let p = Int(push.dropFirst("一共推送了 ".count).prefix(while: \.isNumber)) else { return "還沒有推送的 K 線根數" }
            if p < 103 { return "只推送了 \(p) 根 K 線：最後畫面上要有 103 根，新的 K 線都要推送進去" }
            if p > 120 { return "推送了 \(p) 根 K 線，太多了：只推送上次之後的 K 線" }
            return nil
        }
    )
}
