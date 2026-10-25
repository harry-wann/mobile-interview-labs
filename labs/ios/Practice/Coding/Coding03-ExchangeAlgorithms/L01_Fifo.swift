// L01：同價格的賣單，後掛的先成交了（撮合沒有照時間先後，FIFO 被插隊）
// 對應複習站：Coding03ExchangeAlgorithms 第 1 題「撮合引擎怎麼決定誰和誰成交、用什麼價格？」
//
// 情境：賣方的掛單用陣列存，價格由低到高。依序收到 A（100）、D（101）、B（100）、C（100）四張賣單，
//       其中 A、B、C 都是 100 円、各 1 個，掛單的先後是 A → B → C。接著有人用限價 100 買 2 個，
//       依「價格優先、時間優先」，應該和 A、B 成交。
//
// 你要寫：
//   ① Bug 版（L01Bug.insertIndex）：回傳「第一個價格 >= 新價格」的位置（asks.firstIndex { … }），找不到就放最後（asks.count）。
//      跑起來會看到後掛的 C 插在同價格那一排的最前面，先成交。
//   ② 修正版（L01Fixed.insertIndex）：回傳「第一個價格 > 新價格」的位置，找不到就放最後。同價格先掛的先成交。
// 只改有 TODO 的地方（掛單與撮合的流程已經寫好）。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding03-ExchangeAlgorithms/L01_Fifo.swift
import LabKit

/// 演練流程（兩個版本共用）：依序掛上四張賣單（insertIndex 決定插在陣列的哪裡），再用限價 100 買 2 個
private func scenario(_ log: LabLog, insertIndex: ([C03Order], Int) -> Int) {
    var asks: [C03Order] = []
    for order in [C03Order(id: "A", price: 100, qty: 1), C03Order(id: "D", price: 101, qty: 1),
                  C03Order(id: "B", price: 100, qty: 1), C03Order(id: "C", price: 100, qty: 1)] {
        asks.insert(order, at: insertIndex(asks, order.price))
    }
    log("掛單的先後：A、D、B、C。賣單簿（由低價到高價）：\(c03Text(asks))")

    var remaining = 2
    var trades: [C03Trade] = []
    while remaining > 0, let best = asks.first, best.price <= 100 {
        let fill = min(remaining, best.qty)
        trades.append(C03Trade(maker: best.id, price: best.price, qty: fill))
        remaining -= fill
        asks[0].qty -= fill
        if asks[0].qty == 0 { asks.removeFirst() }
    }
    log("買進 100 × 2 → 成交 \(c03Text(trades))")
}

// MARK: - Bug 版
private enum L01Bug {
    /// 新賣單該插在陣列的哪個位置
    static func insertIndex(_ asks: [C03Order], price: Int) -> Int {
        // TODO ①：找「第一個價格 >= 新價格」的位置：asks.firstIndex { $0.price >= price }，找不到就用 asks.count（放最後）
        return asks.count
    }

    static func run(_ log: LabLog) async {
        scenario(log, insertIndex: { insertIndex($0, price: $1) })
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func insertIndex(_ asks: [C03Order], price: Int) -> Int {
        // TODO ②：改成找「第一個價格大於新價格」的位置（一樣用 asks.firstIndex），找不到就放最後；這樣新單會排在同價格那一排的最後面
        return asks.count
    }

    static func run(_ log: LabLog) async {
        scenario(log, insertIndex: { insertIndex($0, price: $1) })
    }
}

// MARK: - 演練設定
extension Coding03ExchangeAlgorithms {
    static let l01 = Lab(
        id: "L01",
        title: "同價格後掛的賣單，先成交了",
        question: "第 1 題：撮合引擎怎麼決定誰和誰成交、用什麼價格？",
        goal: "價格優先、時間優先：同價格的掛單，先掛的先成交（FIFO）。新單要排在同價格那一排的最後面。先寫出插進同價格最前面的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Coding/Coding03-ExchangeAlgorithms/L01_Fifo.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看賣單簿裡 A、B、C 的順序，以及買進 100 × 2 之後和誰成交。",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，確認先掛的 A、B 先成交。",
            "想一想：插入位置用 >= 或 > 差在哪？如果只有 A、B、C 三張同價格的單，這個差別看得出來嗎？",
        ],
        expectBug: "賣單簿 [C 100×1, B 100×1, A 100×1, D 101×1]；買進 100 × 2 → 成交 [C:1@100, B:1@100]，先掛的 A 沒輪到。",
        expectFixed: "賣單簿 [A 100×1, B 100×1, C 100×1, D 101×1]；買進 100 × 2 → 成交 [A:1@100, B:1@100]。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("買進 100 × 2") }) { return "演練沒有跑完：要用 scenario(log, insertIndex:) 執行" }
            return lines.contains("買進 100 × 2 → 成交 [C:1@100, B:1@100]")
                ? nil : "Bug 還沒重現：新單要插在「第一個價格 >= 新價格」的位置，後掛的 C 才會排在同價格的最前面"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("買進 100 × 2") }) { return "演練沒有跑完：要用 scenario(log, insertIndex:) 執行" }
            return lines.contains("買進 100 × 2 → 成交 [A:1@100, B:1@100]")
                ? nil : "賣單簿要由低價到高價排好，同價格依掛單先後：新單插在「第一個價格 > 新價格」的位置（找不到就放最後）"
        }
    )
}
