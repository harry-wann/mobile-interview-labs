// L07：縮小圖表之後，那根暴漲的針不見了（縮小時用「每隔幾根取一根」，沒有取最高和最低）
// 對應複習站：Exchange07Kline 第 6 題「資料量很大時，圖表怎麼只畫看得到的範圍？圖表元件怎麼選？」
//
// 情境：圖表縮小到一個畫面塞進很多根 K 線，每根連 1 個像素都不到，要把落在同一個像素欄位的 K 線合併。
//       演練用 12 根 K 線縮成 4 欄（每 3 根一欄）。第 5 根（索引 4）有一根暴漲的針：最高 130（其他都在 101 到 106 之間）。
//       正確的縮小：每一欄 open 取最早的一根、close 取最晚的一根、high 取最大、low 取最小。
// Bug 版：每隔 3 根取 1 根（取索引 0、3、6、9）。針在索引 4，剛好被跳過，縮小後最高價只剩 105。
// 修正版：把每 3 根合併成一根：open 取第一根的 open、close 取最後一根的 close、high 取最大、low 取最小。
import Foundation
import LabKit

private struct Candle {
    let open: Int
    let high: Int
    let low: Int
    let close: Int
}

private let candles = [
    Candle(open: 100, high: 101, low: 99, close: 100), Candle(open: 100, high: 102, low: 99, close: 101),
    Candle(open: 101, high: 102, low: 100, close: 100), Candle(open: 100, high: 101, low: 99, close: 100),
    Candle(open: 100, high: 130, low: 98, close: 101), Candle(open: 101, high: 103, low: 100, close: 102),
    Candle(open: 102, high: 103, low: 101, close: 102), Candle(open: 102, high: 104, low: 101, close: 103),
    Candle(open: 103, high: 104, low: 102, close: 103), Candle(open: 103, high: 105, low: 102, close: 104),
    Candle(open: 104, high: 105, low: 103, close: 104), Candle(open: 104, high: 106, low: 103, close: 105),
]

/// 演練流程（兩個版本共用）：把 12 根縮成 4 欄
private func scenario(_ log: LabLog, downsample: ([Candle], Int) -> [Candle]) {
    let small = downsample(candles, 4)
    log("縮成 \(small.count) 欄：" + small.map { "\($0.open)/\($0.high)/\($0.low)/\($0.close)" }.joined(separator: "、") + "（開/高/低/收）")
    let maxHigh = small.map(\.high).max().map(String.init) ?? "-"
    log("最後：縮小後的最高價 \(maxHigh)（真正的最高價 \(candles.map(\.high).max()!)）")
}

// MARK: - Bug 版
private enum L07Bug {
    static func downsample(_ candles: [Candle], columns: Int) -> [Candle] {
        let step = candles.count / columns
        return stride(from: 0, to: candles.count, by: step).map { candles[$0] }       // 每隔 step 根取 1 根
    }

    static func run(_ log: LabLog) async {
        scenario(log) { downsample($0, columns: $1) }
    }
}

// MARK: - 修正版
private enum L07Fixed {
    static func downsample(_ candles: [Candle], columns: Int) -> [Candle] {
        let size = candles.count / columns
        return stride(from: 0, to: candles.count, by: size).map { start in
            let group = candles[start..<min(start + size, candles.count)]
            return Candle(open: group.first!.open, high: group.map(\.high).max()!,       // 每一欄：開盤取最早的、收盤取最晚的、
                          low: group.map(\.low).min()!, close: group.last!.close)        // 最高取最大的、最低取最小的
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { downsample($0, columns: $1) }
    }
}

// MARK: - 演練設定
extension Exchange07Kline {
    static let l07 = Lab(
        id: "L07",
        title: "縮小圖表之後，暴漲的那根針不見了",
        question: "第 6 題：資料量很大時，圖表怎麼只畫看得到的範圍？圖表元件怎麼選？",
        goal: "縮小時要把落在同一個像素欄位的 K 線合併：open 取最早的、close 取最晚的、high 取最大、low 取最小。每隔幾根取一根，會把最高點和最低點丟掉。",
        steps: [
            "選「Bug 版」執行，看縮小後的最高價，和真正的最高價比一比。",
            "想一想：每隔 3 根取 1 根，取到的是索引 0、3、6、9。那根暴漲的針在索引幾？",
            "選「修正版」執行，縮小後的最高價要和真正的一樣，第二欄要是 100/130/98/102。",
            "進階：只畫可視範圍時，要用二分搜尋在已排序的資料裡找出起訖索引，而不是從頭掃描；向左拖才分頁載入更舊的歷史。",
        ],
        expectBug: "縮成 4 欄「100/101/99/100、100/101/99/100、102/103/101/102、103/105/102/104」，「最後：縮小後的最高價 105（真正的最高價 130）」。",
        expectFixed: "縮成 4 欄「100/102/99/100、100/130/98/102、102/104/101/103、103/106/102/105」，「最後：縮小後的最高價 130（真正的最高價 130）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, downsample:) 執行" }
            return lines.contains(where: { $0.hasPrefix("最後：縮小後的最高價 105") })
                ? nil : "Bug 還沒重現：每隔 step（candles.count / columns）根取 1 根（stride），針在索引 4 會被跳過"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, downsample:) 執行" }
            if !lines.contains(where: { $0.hasPrefix("縮成 4 欄：100/102/99/100、100/130/98/102、102/104/101/103、103/106/102/105") }) {
                return "把每 3 根合併成一欄：open 取第一根的 open、high 取最大、low 取最小、close 取最後一根的 close"
            }
            return nil
        }
    )
}
