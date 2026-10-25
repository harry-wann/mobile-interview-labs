// L02：行情每 50 毫秒跳一次，畫面卻等到行情停了才更新（行情用 debounce）
// 對應複習站：Coding02TimeStreams 第 2 題「debounce、throttle、sample
// 差在哪？行情畫面用哪一個？」
//
// 情境：行情每 50 毫秒送來一筆價格，從第 0 到第 1000 毫秒共 21 筆，
// 畫面希望「最多每 200 毫秒更新一次，而且永遠顯示最新價格」。
//       演練用純函式處理：輸入是帶時間的價格，輸出是畫面實際更新的時間與價格。
//
// 你要寫：
//   ① Bug 版（L02Bug.updates）：用已經寫好的 c02Debounce(ticks, wait: 300)
// 處理行情。
//   ② 修正版（L02Fixed.sample）：每隔 interval 毫秒送出一次，
// 內容是這段時間內最新的一筆。
//      每一筆行情屬於哪一段：windowEnd = (tick.time / interval + 1) *
// interval；同一段後來的取代前面的，新的一段就加一筆。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding02-TimeStreams/L02_QuoteSample.swift
import LabKit

private let ticks = (0...20).map {
    C02Tick(time: $0 * 50, price: 15_000_000 + $0 * 1_000)
}

/// 演練流程（兩個版本共用）：把 21 筆行情交給 updates，
/// 看畫面更新的次數與最長多久沒動
private func scenario(_ log: LabLog, updates: ([C02Tick]) -> [C02Tick]) {
    let out = updates(ticks)
    log("行情每 50 毫秒跳一次，共 \(ticks.count) 筆（第 0 到 1000 毫秒）")
    log("畫面共更新 \(out.count) 次，更新的時間：\(out.map(\.time))")
    var previous = 0
    var longest = 0
    for update in out {
        longest = max(longest, update.time - previous)
        previous = update.time
    }
    log("畫面最長 \(longest) 毫秒沒有更新")
    // 每次更新的價格，都應該是「更新當下之前」最新的一筆行情
    let fresh = out.allSatisfy { update in
        ticks.last(where: { $0.time < update.time })?.price == update.price
    }
    log("每次更新的價格都是當下最新的一筆：\(fresh ? "是" : "否")")
}

// MARK: - Bug 版
private enum L02Bug {
    static func updates(_ ticks: [C02Tick]) -> [C02Tick] {
        // TODO ①：回傳 c02Debounce(ticks, wait: 300)（輸入停下來 300
        // 毫秒才送出，行情一直在跳就永遠等不到）
        return []
    }

    static func run(_ log: LabLog) async {
        scenario(log, updates: updates)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    /// 每隔 interval 毫秒送出一次，內容是這段時間內最新的一筆
    static func sample(_ ticks: [C02Tick], interval: Int) -> [C02Tick] {
        var result: [C02Tick] = []
        for tick in ticks {
            // TODO ②-1：這一筆屬於哪一段，那一段什麼時候結束：let windowEnd =
            // (tick.time / interval + 1) * interval
            // TODO ②-2：result 最後一筆的 time 等於 windowEnd（同一段）：
            // 用這一筆的價格取代它（result[result.count - 1] = …）
            // TODO ②-3：否則是新的一段：result.append(C02Tick(time: windowEnd,
            // price: tick.price))
        }
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, updates: { sample($0, interval: 200) })
    }
}

// MARK: - 演練設定
extension Coding02TimeStreams {
    static let l02 = Lab(
        id: "L02",
        title: "行情一直跳，畫面卻不更新",
        question: "第 2 題：debounce、throttle、sample 差在哪？行情畫面用哪一個？",
        goal:
            "行情要用 sample（每段時間送最新的）。debounce 要等輸入停下來，行情一直在變就永遠等不到。先寫出用 debounce 處理行情的 Bug 版，再寫 sample 的修正版。",
        steps: [
            "打開 Practice/Coding/Coding02-TimeStreams/L02_QuoteSample.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看畫面更新幾次、最長多久沒更新。",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，確認畫面大約每 200 毫秒更新一次，而且每次都是最新價格。",
            "想一想：搜尋框為什麼適合 debounce，行情卻不行？",
        ],
        expectBug: "「畫面共更新 1 次」，時間是 [1300]，最長 1300 毫秒沒有更新。",
        expectFixed:
            "「畫面共更新 6 次」，時間 [200, 400, 600, 800, 1000, 1200]，最長 200 毫秒沒有更新，每次都是最新價格。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("畫面共更新") }) {
                return "演練沒有跑完：要用 scenario(log, updates:) 執行"
            }
            if lines.contains("畫面共更新 0 次，更新的時間：[]") {
                return "updates 還沒寫：呼叫 c02Debounce(ticks, wait: 300)"
            }
            return lines.contains("畫面共更新 1 次，更新的時間：[1300]")
                ? nil : "Bug 還沒重現：用 wait 300 的 debounce，行情每 50 毫秒就來一筆，只有最後一筆會送出"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("畫面共更新") }) {
                return "演練沒有跑完：要用 scenario(log, updates:) 執行"
            }
            if !lines.contains(
                "畫面共更新 6 次，更新的時間：[200, 400, 600, 800, 1000, 1200]")
            {
                return "sample 還沒寫對：21 筆行情要分成 6 段（每 200 毫秒一段），每段在結束時送出一次"
            }
            if !lines.contains("每次更新的價格都是當下最新的一筆：是") {
                return "同一段裡有好幾筆時，要留最後（最新）的那一筆"
            }
            return nil
        }
    )
}
