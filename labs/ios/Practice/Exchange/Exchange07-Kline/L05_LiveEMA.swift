// L05：進行中的 K 線，EMA 越跳越高（每個 tick 都把算出來的值寫回「上一根的
// EMA」）
// 對應複習站：Exchange07Kline 第 4 題「移動平均（SMA、EMA）和 RSI
// 怎麼算？即時更新時要注意什麼？」
//
// 情境：EMA 週期 9（k = 2 ÷ (9 + 1) = 0.2），公式 ema = prev + k × (價格 −
// prev)。上一根已收盤的 EMA 是 100。
//       這一根還在進行中，連續收到三個 tick，價格都是 110；收盤之後，
// 下一根的第一個 tick 價格是 104。
//       正確的顯示值：三個 tick 都是 102（100 + 0.2 × 10）；收盤後 EMA 固定為
// 102；下一根第一個 tick：102 + 0.2 × (104 − 102) = 102.4。
//
// 你要寫：
//   ① Bug 版（L05Bug.EMA.onTick）：prev = prev + k * (price - prev)，
// 把結果寫回 prev 再回傳。
//   ② 修正版（L05Fixed.EMA）：onTick：live = prevClosed + k * (price -
// prevClosed)，回傳 live（不寫回 prevClosed）；onCandleClose：prevClosed =
// live。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange07-Kline/L05_LiveEMA.swift
import Foundation
import LabKit

private let k = e07("0.2")

private protocol LiveEMA: AnyObject {
    /// 進行中的這一根收到一個 tick，回傳要顯示的 EMA
    func onTick(_ price: Decimal) -> Decimal
    /// 這一根收盤了
    func onCandleClose()
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, ema: LiveEMA) {
    var shown: [Decimal] = []
    for price in [e07("110"), e07("110"), e07("110")] {
        let value = ema.onTick(price)
        shown.append(value)
        log("這一根的 tick \(price) → EMA \(value)")
    }
    ema.onCandleClose()
    let next = ema.onTick(e07("104"))
    log("收盤後，下一根的第一個 tick 104 → EMA \(next)")
    log(
        "最後：三個 tick 的 EMA 是 \(shown.map { "\($0)" }.joined(separator: "、"))，下一根第一個 tick 是 \(next)（正確答案：102、102、102，下一根 102.4）"
    )
}

// MARK: - Bug 版
private enum L05Bug {
    final class EMA: LiveEMA {
        private var prev = e07("100")

        func onTick(_ price: Decimal) -> Decimal {
            // TODO ①：prev = prev + k * (price - prev)：把算出來的值寫回 prev，
            // 再回傳 prev。目前回傳 0 只是佔位
            return 0
        }

        func onCandleClose() {}
    }

    static func run(_ log: LabLog) async {
        scenario(log, ema: EMA())
    }
}

// MARK: - 修正版
private enum L05Fixed {
    final class EMA: LiveEMA {
        // 上一根「已收盤」的 EMA
        private var prevClosed = e07("100")
        private var live = e07("100")

        func onTick(_ price: Decimal) -> Decimal {
            // TODO ②-1：live = prevClosed + k * (price - prevClosed)，回傳
            // live；不要寫回 prevClosed。目前回傳 0 只是佔位
            return 0
        }

        func onCandleClose() {
            // TODO ②-2：收盤了：把 prevClosed 換成最後的 live
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, ema: EMA())
    }
}

// MARK: - 演練設定
extension Exchange07Kline {
    static let l05 = Lab(
        id: "L05",
        title: "進行中的 K 線，EMA 越跳越高",
        question: "第 4 題：移動平均（SMA、EMA）和 RSI 怎麼算？即時更新時要注意什麼？",
        goal:
            "進行中的 K 線，要用「上一根已收盤的 EMA」算出顯示值，不要把進行中的值寫回 prev；K 線收盤時，才把最後的值固定成新的 prev。先寫出把進行中的值寫回 prev 的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange07-Kline/L05_LiveEMA.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看同樣是 110 的三個 tick，EMA 怎麼變？下一根的第一個 tick 又是多少？",
            "想一想：價格都沒變，EMA 為什麼一直往 110 靠近？每個 tick 都被算進去幾次？",
            "寫 TODO ②（修正版），選「修正版」執行，三個 tick 都要是 102，下一根第一個 tick 要是 102.4。",
            "進階：EMA 和 RSI 都是遞迴的，畫面最左邊那根的值取決於更早的資料，要多抓一些歷史暖機（至少 3 到 5 倍的週期）。",
        ],
        expectBug:
            "三個 tick 的 EMA 是 102、103.6、104.88，下一根第一個 tick 是 104.704：「最後：三個 tick 的 EMA 是 102、103.6、104.88，下一根第一個 tick 是 104.704」。",
        expectFixed:
            "三個 tick 都是 102，下一根第一個 tick 是 102.4：「最後：三個 tick 的 EMA 是 102、102、102，下一根第一個 tick 是 102.4」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, ema:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：三個 tick 的 EMA 是 102、103.6、104.88")
            })
                ? nil
                : "Bug 還沒重現：onTick 要把算出來的值寫回 prev（prev = prev + k * (price - prev)），再回傳 prev"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, ema:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix(
                    "最後：三個 tick 的 EMA 是 102、102、102，下一根第一個 tick 是 102.4")
            })
                ? nil
                : "onTick 用 prevClosed 算出 live 再回傳（不寫回 prevClosed）；onCandleClose 才把 prevClosed 換成 live"
        }
    )
}
