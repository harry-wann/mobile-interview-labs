// L03：搜尋框打完字，最後一個字沒有送出去；剛好間隔 wait 的輸入被當成取消（debounce 的邊界）
// 對應複習站：Coding02TimeStreams 第 3 題「debounce 怎麼寫成不需要等待就能測試的函式？」
//
// 情境：把 debounce 寫成純函式：輸入是依時間排序的事件，輸出是實際送出的事件（送出時間 = 事件時間 + wait）。
//       規則：下一個事件在 `time + wait` 之前（嚴格小於）到達，這一個才被取消；剛好在 `time + wait` 到達不算取消；最後一個事件一定送出。
//       演練用 4 組固定的資料（wait = 100 毫秒）檢查答案。
// Bug 版：迴圈只跑到倒數第二個事件（最後一個沒有「下一個」可以比，就被漏掉了），而且判斷寫成 `> wait`，
//        剛好相差 wait 毫秒的事件被當成取消。
// 修正版：最後一個事件一定送出；其他事件看下一個事件的間隔，`>= wait` 才送出。
import LabKit

private struct Case {
    let title: String
    let events: [C02Event]
    let expected: [C02Event]
}

private let cases = [
    Case(title: "打字停頓", events: [C02Event(time: 0, value: "b"), C02Event(time: 50, value: "bt"), C02Event(time: 300, value: "btc")],
         expected: [C02Event(time: 150, value: "bt"), C02Event(time: 400, value: "btc")]),
    Case(title: "剛好間隔 wait", events: [C02Event(time: 0, value: "a"), C02Event(time: 100, value: "ab")],
         expected: [C02Event(time: 100, value: "a"), C02Event(time: 200, value: "ab")]),
    Case(title: "只有一個事件", events: [C02Event(time: 0, value: "a")],
         expected: [C02Event(time: 100, value: "a")]),
    Case(title: "沒有事件", events: [], expected: []),
]

/// 演練流程（兩個版本共用）：4 組資料各算一次，和標準答案比對（wait = 100）
private func scenario(_ log: LabLog, debounce: ([C02Event], Int) -> [C02Event]) {
    var right = 0
    for (index, item) in cases.enumerated() {
        let got = debounce(item.events, 100)
        let ok = got == item.expected
        if ok { right += 1 }
        log("案例 \(index + 1)（\(item.title)）：得到 \(c02Text(got))，期待 \(c02Text(item.expected)) \(ok ? "✓" : "✗")")
    }
    log("答對 \(right)/\(cases.count)")
}

// MARK: - Bug 版
private enum L03Bug {
    static func debounce(_ events: [C02Event], wait: Int) -> [C02Event] {
        var result: [C02Event] = []
        for index in 0..<max(events.count - 1, 0) {                       // 只跑到倒數第二個：最後一個事件被漏掉
            if events[index + 1].time - events[index].time > wait {       // 用 >：剛好相差 wait 也被當成取消
                result.append(C02Event(time: events[index].time + wait, value: events[index].value))
            }
        }
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, debounce: { debounce($0, wait: $1) })
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func debounce(_ events: [C02Event], wait: Int) -> [C02Event] {
        var result: [C02Event] = []
        for (index, event) in events.enumerated() {
            let isLast = index == events.count - 1
            // 最後一個一定送出；其他的要等下一個事件和這一個相差至少 wait
            if isLast || events[index + 1].time - event.time >= wait {
                result.append(C02Event(time: event.time + wait, value: event.value))
            }
        }
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, debounce: { debounce($0, wait: $1) })
    }
}

// MARK: - 演練設定
extension Coding02TimeStreams {
    static let l03 = Lab(
        id: "L03",
        title: "最後一個字沒送出，邊界被當成取消",
        question: "第 3 題：debounce 怎麼寫成不需要等待就能測試的函式？",
        goal: "最後一個事件一定送出；下一個事件剛好在 wait 之後到達不算取消（>= wait 才送出）。",
        steps: [
            "選「Bug 版」執行，看案例 1 少了哪一筆、案例 2 少了哪一筆。",
            "選「修正版」執行，確認 4 個案例全部答對，包含「只有一個事件」與「沒有事件」。",
            "想一想：開始寫之前，要向面試官確認哪一個邊界？（下一個事件剛好在 time + wait 到達，算不算取消）",
        ],
        expectBug: "案例 1 只得到 [(150, bt)]（少了最後的 btc），案例 2 得到 []，案例 3 得到 []；4 組只答對 1 組（空輸入）。",
        expectFixed: "4 組全部答對：「答對 4/4」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("答對") }) { return "演練沒有跑完：要用 scenario(log, debounce:) 執行" }
            if !lines.contains("案例 1（打字停頓）：得到 [(150, bt)]，期待 [(150, bt), (400, btc)] ✗") {
                return "Bug 還沒重現：迴圈只跑到倒數第二個事件（用 events.count - 1），最後的 btc 才會漏掉"
            }
            return lines.contains("案例 2（剛好間隔 wait）：得到 []，期待 [(100, a), (200, ab)] ✗")
                ? nil : "Bug 還沒重現：判斷寫成 > wait，剛好相差 100 毫秒的 a 就被當成取消"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("答對") }) { return "演練沒有跑完：要用 scenario(log, debounce:) 執行" }
            if let wrong = lines.first(where: { $0.hasPrefix("案例") && $0.hasSuffix("✗") }) { return "還有案例答錯：\(wrong)" }
            return lines.contains("答對 4/4") ? nil : "4 個案例都要答對：最後一個事件一定送出，間隔 >= wait 就送出"
        }
    )
}
