// L02：收盤價是最後「收到」的那一筆，不是最後「發生」的那一筆（成交亂序，
// 用到達順序決定 open 和 close）
// 對應複習站：Exchange07Kline 第 2 題「怎麼把一筆一筆的成交聚合成 K 線？」
//
// 情境：重連補資料時，成交常常亂序到達。一分鐘內收到四筆成交（依收到的順序）：
//       第 40 秒 4,800,000×0.2、第 5 秒 4,790,000×0.1、第 55 秒 4,810,000×0.
// 05、第 20 秒 4,780,000×0.3。
//       正確的 K 線：open 4790000（最早的：第 5 秒）、close 4810000（最晚的：
// 第 55 秒）、high 4810000、low 4780000、amount 0.65、vol 3113500、count 4。
//       （high、low、amount、vol、count 和順序無關，已經寫好。）
//
// 你要寫：
//   ① Bug 版（L02Bug.run 的 openClose 閉包）：open 取 trades.first!.price、
// close 取 trades.last!.price（收到的順序）。
//   ② 修正版（L02Fixed.run 的 openClose 閉包）：先依 (ts, tradeId) 排序，open
// 取排序後的第一筆、close 取最後一筆。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange07-Kline/L02_OpenCloseOrder.swift
import Foundation
import LabKit

private struct Trade {
    let tradeId: Int64
    let ts: Int64  // 毫秒
    let price: Decimal
    let qty: Decimal
}

/// 演練流程（兩個版本共用）：`openClose` 決定怎麼找出 open 與 close
private func scenario(
    _ log: LabLog, openClose: ([Trade]) -> (open: Decimal, close: Decimal)
) {
    // 這一分鐘的起點（毫秒）
    let base: Int64 = 1_632_931_200_000
    let trades = [
        Trade(
            tradeId: 4, ts: base + 40_000, price: e07("4800000"),
            qty: e07("0.2")),
        Trade(
            tradeId: 1, ts: base + 5_000, price: e07("4790000"), qty: e07("0.1")
        ),
        Trade(
            tradeId: 6, ts: base + 55_000, price: e07("4810000"),
            qty: e07("0.05")),
        Trade(
            tradeId: 3, ts: base + 20_000, price: e07("4780000"),
            qty: e07("0.3")),
    ]
    let (open, close) = openClose(trades)
    let high = trades.map(\.price).max()!, low = trades.map(\.price).min()!
    let amount = trades.reduce(Decimal(0)) { $0 + $1.qty }
    let vol = trades.reduce(Decimal(0)) { $0 + $1.price * $1.qty }
    log(
        "正確答案：open 4790000，close 4810000，high 4810000，low 4780000，amount 0.65，vol 3113500，count 4"
    )
    log(
        "最後：open \(open)，close \(close)，high \(high)，low \(low)，amount \(amount)，vol \(vol)，count \(trades.count)"
    )
}

// MARK: - Bug 版
private enum L02Bug {
    static func run(_ log: LabLog) async {
        scenario(log) { trades in
            // TODO ①：open 取 trades.first!.price、close 取 trades.last!.
            // price（收到的順序，不排序），回傳 (open, close)。
            //        目前回傳 (0, 0) 只是佔位
            (0, 0)
        }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func run(_ log: LabLog) async {
        scenario(log) { trades in
            // TODO ②：先依 (ts, tradeId) 排序（trades.sorted），open
            // 取排序後第一筆的 price、close 取最後一筆的 price，回傳 (open,
            // close)。
            //        目前回傳 (0, 0) 只是佔位
            return (0, 0)
        }
    }
}

// MARK: - 演練設定
extension Exchange07Kline {
    static let l02 = Lab(
        id: "L02",
        title: "收盤價是最後收到的那一筆，不是最後發生的",
        question: "第 2 題：怎麼把一筆一筆的成交聚合成 K 線？",
        goal:
            "聚合成交時，open 是時間最早的那一筆、close 是時間最晚的那一筆，不是陣列的第一筆和最後一筆：要先依（時間, 成交編號）排序。先寫出用收到順序的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange07-Kline/L02_OpenCloseOrder.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看 open 和 close，和正確答案比一比。",
            "想一想：重連補資料時，成交為什麼會亂序？「收到的最後一筆」和「最後發生的一筆」差在哪？",
            "寫 TODO ②（修正版），選「修正版」執行，open 要是 4790000、close 要是 4810000。",
            "進階：同一毫秒有好幾筆成交時，用什麼決定先後？（成交編號 tradeId，排序的鍵是 (ts, tradeId)）",
        ],
        expectBug: "「最後：open 4800000，close 4780000，…」：收到的第一筆與最後一筆，和正確答案不同。",
        expectFixed:
            "「最後：open 4790000，close 4810000，high 4810000，low 4780000，amount 0.65，vol 3113500，count 4」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, openClose:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：open 4800000，close 4780000")
            })
                ? nil
                : "Bug 還沒重現：open 取 trades.first!.price、close 取 trades.last!.price（收到的順序，不排序）"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, openClose:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix(
                    "最後：open 4790000，close 4810000，high 4810000，low 4780000，amount 0.65，vol 3113500，count 4"
                )
            })
                ? nil : "先依（ts, tradeId）排序，open 取排序後的第一筆、close 取排序後的最後一筆"
        }
    )
}
