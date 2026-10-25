// L05：沒有成交的時段補了 K 線，價格卻是很久以前的收盤價（補 K 線用錯前一根的收盤價）
// 對應複習站：Coding03ExchangeAlgorithms 第 3 題「某些時段沒有成交時，K 線要怎麼補？」
//
// 情境：1 分鐘 K 線（週期 60 秒），API 沒有回傳沒成交的那幾分鐘。收到的 K 線時間是 0、180、240、420，
//       中間缺 60、120（第 1 個缺口，前一根收盤 102）和 300、360（第 2 個缺口，前一根是 240 那根，收盤 115）。
//       缺的時段要補一根開高低收都等於「前一根收盤價」、成交量 0 的 K 線。
//
// 你要寫：
//   ① Bug 版（L05Bug.fillGaps）：lastClose 只在一開始設成第一根的收盤價（let lastClose = first.close），之後都不更新；
//      缺口裡每一根補 C03Candle(time: t, open: lastClose, high: lastClose, low: lastClose, close: lastClose, volume: 0)。
//   ② 修正版（L05Fixed.fillGaps）：lastClose 是 var；補洞一樣用 lastClose；每放進一根真的 K 線（result.append(candle)）之後，更新 lastClose = candle.close。
// 只改有 TODO 的地方（找缺口的時間迴圈已經寫好）。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding03-ExchangeAlgorithms/L05_FillGaps.swift
import LabKit

private let period = 60

private let candles = [
    C03Candle(time: 0, open: 100, high: 105, low: 99, close: 102, volume: 5),
    C03Candle(time: 180, open: 103, high: 112, low: 102, close: 111, volume: 7),
    C03Candle(time: 240, open: 111, high: 116, low: 110, close: 115, volume: 4),
    C03Candle(time: 420, open: 116, high: 121, low: 114, close: 120, volume: 6),
]

/// 演練流程（兩個版本共用）：補洞，列出時間軸與補出來的 K 線
private func scenario(_ log: LabLog, fillGaps: ([C03Candle], Int) -> [C03Candle]) {
    let result = fillGaps(candles, period)
    let originalTimes = Set(candles.map(\.time))
    let filled = result.filter { !originalTimes.contains($0.time) }
    log("時間軸：\(result.map(\.time))（共 \(result.count) 根）")
    log("補了 \(filled.count) 根（時間→價格）：\(filled.map { "\($0.time)→\($0.close)" }.joined(separator: ", "))")
    let flat = filled.allSatisfy { $0.open == $0.close && $0.high == $0.close && $0.low == $0.close && $0.volume == 0 }
    log("補的 K 線開高低收一樣、成交量 0：\(flat ? "是" : "否")")
}

// MARK: - Bug 版
private enum L05Bug {
    static func fillGaps(_ candles: [C03Candle], period: Int) -> [C03Candle] {
        guard let first = candles.first else { return [] }
        var result = [first]
        // TODO ①-1：記下第一根的收盤價：let lastClose = first.close，之後整個函式都不更新它
        let lastClose = 0
        for candle in candles.dropFirst() {
            var t = result[result.count - 1].time + period
            while t < candle.time {
                // TODO ①-2：補一根：result.append(C03Candle(time: t, open: lastClose, high: lastClose, low: lastClose, close: lastClose, volume: 0))
                t += period
            }
            result.append(candle)
        }
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, fillGaps: { fillGaps($0, period: $1) })
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func fillGaps(_ candles: [C03Candle], period: Int) -> [C03Candle] {
        guard let first = candles.first else { return [] }
        var result = [first]
        var lastClose = first.close
        for candle in candles.dropFirst() {
            var t = result[result.count - 1].time + period
            while t < candle.time {
                // TODO ②-1：補一根：result.append(C03Candle(time: t, open: lastClose, high: lastClose, low: lastClose, close: lastClose, volume: 0))
                t += period
            }
            result.append(candle)
            // TODO ②-2：每放進一根真的 K 線，就更新「前一根的收盤價」：lastClose = candle.close
        }
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, fillGaps: { fillGaps($0, period: $1) })
    }
}

// MARK: - 演練設定
extension Coding03ExchangeAlgorithms {
    static let l05 = Lab(
        id: "L05",
        title: "補的 K 線用了很久以前的收盤價",
        question: "第 3 題：某些時段沒有成交時，K 線要怎麼補？",
        goal: "補洞的價格是「緊鄰缺口前面那一根」的收盤價；每放進一根真的 K 線，就要更新它。先寫出 lastClose 一直沒更新的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Coding/Coding03-ExchangeAlgorithms/L05_FillGaps.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，比較兩個缺口補出來的價格：第 2 個缺口（300、360）前一根的收盤價是多少？",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，確認補的價格是 60→102、120→102、300→115、360→115。",
            "想一想：如果漏了 t += period，會發生什麼事？（無限迴圈）開始寫之前要先確認什麼？（時間有沒有對齊週期）",
        ],
        expectBug: "「補了 4 根（時間→價格）：60→102, 120→102, 300→102, 360→102」：第 2 個缺口還在用第一根的收盤價。",
        expectFixed: "「補了 4 根（時間→價格）：60→102, 120→102, 300→115, 360→115」，時間軸 0 到 420 共 8 根。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("補了") }) { return "演練沒有跑完：要用 scenario(log, fillGaps:) 執行" }
            if lines.contains(where: { $0.hasPrefix("補了 0 根") }) { return "fillGaps 還沒寫：缺的時段要補 K 線" }
            return lines.contains("補了 4 根（時間→價格）：60→102, 120→102, 300→102, 360→102")
                ? nil : "Bug 還沒重現：lastClose 只在一開始設成第一根的收盤價，之後不更新，第 2 個缺口才會用到 102"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("補了") }) { return "演練沒有跑完：要用 scenario(log, fillGaps:) 執行" }
            if lines.contains(where: { $0.hasPrefix("補了 0 根") }) { return "fillGaps 還沒寫：缺的時段要補 K 線" }
            if !lines.contains("補了 4 根（時間→價格）：60→102, 120→102, 300→115, 360→115") {
                return "第 2 個缺口要用 240 那根的收盤價 115：每放進一根真的 K 線，就更新 lastClose"
            }
            return lines.contains("補的 K 線開高低收一樣、成交量 0：是") ? nil : "補的 K 線開高低收都要等於 lastClose，成交量是 0"
        }
    )
}
