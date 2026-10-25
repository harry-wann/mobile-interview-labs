// L06：切換週期之後，圖表一片空白（歷史資料和暫存的推送直接串起來，id 重複、順序亂）
// 對應複習站：Exchange07Kline 第 5 題「切換週期、接上即時推送時，歷史資料和推送怎麼接起來？」
//
// 情境：使用者切換週期。App 先訂閱新週期的推送並暫存，再用 REST 抓歷史，最後把兩邊合併，一次交給圖表（`setData`）。
//       歷史（REST，已由舊到新）：id 60 收 1、id 120 收 2、id 180 收 3。
//       暫存的推送（訂閱後收到）：id 120 收 99（比歷史最後一根舊的舊資料）、id 180 收 30（同一根的最新資料）、id 240 收 4（新的一根）。
//       正確的合併：60:1、120:2、180:30、240:4。圖表的 `setData` 和 Lightweight Charts 一樣，要求資料「嚴格由舊到新」，id 不能重複，否則丟出例外。
// Bug 版：把歷史和暫存的推送接在一起，再依 id 排序：id 120、180 各出現兩次，圖表拒絕這份資料，整張圖一片空白。
// 修正版：比歷史最後一根（id 180）更舊的推送丟掉（120）；相同 id 以推送為準（180 變成 30）；新的 id 加進來（240）；最後依 id 排序。
import Foundation
import LabKit

private struct Candle {
    let id: Int64
    let close: Int
}

private struct ChartError: Error {
    let message: String
}

/// 假的圖表：`setData` 要求資料嚴格由舊到新（id 不能重複、不能倒退）
private final class FakeChart {
    private(set) var candles: [Candle] = []

    func setData(_ data: [Candle]) throws {
        for (previous, next) in zip(data, data.dropFirst()) where next.id <= previous.id {
            throw ChartError(message: "data must be asc ordered by time（id \(next.id) 沒有比前一根 \(previous.id) 新）")
        }
        candles = data
    }
}

private func text(_ candles: [Candle]) -> String {
    candles.map { "\($0.id):\($0.close)" }.joined(separator: "、")
}

/// 演練流程（兩個版本共用）：合併之後一次 setData
private func scenario(_ log: LabLog, merge: (_ history: [Candle], _ buffered: [Candle]) -> [Candle]) {
    let history = [Candle(id: 60, close: 1), Candle(id: 120, close: 2), Candle(id: 180, close: 3)]
    let buffered = [Candle(id: 120, close: 99), Candle(id: 180, close: 30), Candle(id: 240, close: 4)]
    let merged = merge(history, buffered)
    log("合併後：\(text(merged))")
    let chart = FakeChart()
    do {
        try chart.setData(merged)
        log("圖表 setData：成功，共 \(chart.candles.count) 根")
    } catch let error as ChartError {
        log("圖表 setData：失敗，\(error.message)")
    } catch {
        log("圖表 setData：失敗")
    }
    log("最後：圖表有 \(chart.candles.count) 根\(chart.candles.isEmpty ? "" : "：" + text(chart.candles))（正確答案：60:1、120:2、180:30、240:4）")
}

// MARK: - Bug 版
private enum L06Bug {
    static func run(_ log: LabLog) async {
        scenario(log) { history, buffered in
            (history + buffered).sorted { $0.id < $1.id }           // 直接接在一起：id 重複
        }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func run(_ log: LabLog) async {
        scenario(log) { history, buffered in
            guard let lastHistoryID = history.last?.id else { return buffered.sorted { $0.id < $1.id } }
            var byID = Dictionary(uniqueKeysWithValues: history.map { ($0.id, $0) })
            for candle in buffered where candle.id >= lastHistoryID {      // 比歷史最後一根更舊的丟掉
                byID[candle.id] = candle                                    // 相同 id 以推送為準
            }
            return byID.values.sorted { $0.id < $1.id }
        }
    }
}

// MARK: - 演練設定
extension Exchange07Kline {
    static let l06 = Lab(
        id: "L06",
        title: "切換週期之後，圖表一片空白",
        question: "第 5 題：切換週期、接上即時推送時，歷史資料和推送怎麼接起來？",
        goal: "歷史與暫存的推送要依 id 合併：比歷史最後一根更舊的推送丟掉，相同 id 以推送為準，最後依 id 由舊到新排序，一次交給圖表。",
        steps: [
            "選「Bug 版」執行，看合併後的資料有哪些 id 重複，圖表的 setData 回報什麼錯誤。",
            "想一想：id 120 的推送（收 99）比歷史最後一根（id 180）還舊，該拿它怎麼辦？id 180 兩邊都有，聽誰的？",
            "選「修正版」執行，圖表要有 4 根：60:1、120:2、180:30、240:4。",
            "進階：為什麼要先訂閱推送並暫存，再抓歷史？（和訂單簿「先暫存增量、再取快照」是同一個道理，Exchange04 L05、Platform01 L07 練過）",
        ],
        expectBug: "合併後「60:1、120:2、120:99、180:3、180:30、240:4」，圖表 setData 失敗：「最後：圖表有 0 根」。",
        expectFixed: "合併後「60:1、120:2、180:30、240:4」，「最後：圖表有 4 根：60:1、120:2、180:30、240:4」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, merge:) 執行" }
            if !lines.contains("合併後：60:1、120:2、120:99、180:3、180:30、240:4") { return "Bug 版要把 history + buffered 直接接在一起，再依 id 排序（不去重、不丟舊的）" }
            return lines.contains(where: { $0.hasPrefix("最後：圖表有 0 根") }) ? nil : "Bug 還沒重現：id 重複的資料交給圖表，setData 應該失敗"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, merge:) 執行" }
            return lines.contains("合併後：60:1、120:2、180:30、240:4")
                ? nil : "比歷史最後一根（id 180）更舊的推送丟掉；相同 id 以推送為準；新的 id 加進來；最後依 id 排序"
        }
    )
}
