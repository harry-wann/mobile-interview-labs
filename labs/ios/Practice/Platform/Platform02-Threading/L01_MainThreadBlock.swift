// L01：按下「買入」之後，要等一下才有反應（重工作寫在主執行緒）
// 對應複習站：Platform02Threading 第 1
// 題「主執行緒到底在做什麼？為什麼不能被卡住？」
//
// 情境：行情頁收到一份訂單簿資料，解碼要花約 80
// ms（演練用忙碌迴圈代替真的解碼，每次花的時間固定）。
//        收到資料之後 10 ms，使用者按下「買入」。
// 按鈕的事件要排進主執行緒的佇列，等主執行緒有空才會處理。
//
// 你要寫：
//   ① Bug 版：在主執行緒直接解碼。按鈕的事件排在解碼後面，要等 70 到 80 ms。
//   ② 修正版：解碼放到背景（Task.detached），只有更新畫面回主執行緒。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform02-Threading/L01_MainThreadBlock.swift
import Foundation
import LabKit

/// 一份訂單簿（兩個版本共用）
private struct OrderBook {
    let bestBid: Int
}

/// 解碼要花約 80 ms（兩個版本共用）：用忙碌迴圈代替，Debug 與 Release
/// 花的時間一樣
private func decodeOrderBook(_ raw: Data) -> OrderBook {
    let end = DispatchTime.now().uptimeNanoseconds + 80_000_000
    while DispatchTime.now().uptimeNanoseconds < end {}
    return OrderBook(bestBid: raw.count)
}

/// 演練流程（兩個版本共用）：收到資料 → 10 ms 後使用者按下買入 →
/// 看按鈕的事件等了多久才被處理
@MainActor
private func scenario(
    _ log: LabLog,
    receive: @escaping (Data, @escaping @MainActor (OrderBook) -> Void) -> Void
) async {
    var rendered = false
    var tapDelayMs: Int?
    // 10 ms 後，使用者按下買入：事件從別的執行緒排進主執行緒的佇列，
    // 記下排進去的時間，等主執行緒處理時再量
    DispatchQueue.global().asyncAfter(deadline: .now() + 0.01) {
        let posted = DispatchTime.now().uptimeNanoseconds
        DispatchQueue.main.async {
            tapDelayMs = Int(
                (DispatchTime.now().uptimeNanoseconds - posted) / 1_000_000)
        }
    }
    // 收到一份訂單簿資料
    receive(Data(count: 1_000)) { _ in rendered = true }
    var waited = 0
    // 最多等 2 秒，避免沒寫完的時候卡住
    while (tapDelayMs == nil || !rendered) && waited < 200 {
        try? await Task.sleep(for: .milliseconds(10))
        waited += 1
    }
    log("訂單簿\(rendered ? "已經顯示" : "沒有顯示")")
    log("按下買入的事件，在佇列裡等了 \(tapDelayMs ?? -1) ms 才被處理")
}

// MARK: - Bug 版
private enum L01Bug {
    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { raw, render in
            // TODO ①：在主執行緒直接呼叫 decodeOrderBook(raw) 解碼，
            // 再把結果交給 render（解碼會佔住主執行緒 80 ms）
        }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { raw, render in
            // TODO ②：用 Task.detached 在背景執行緒呼叫 decodeOrderBook(raw)，
            // 解碼完再用 await MainActor.run 回主執行緒呼叫 render
        }
    }
}

// MARK: - 演練設定
private func delayMs(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("按下買入的事件，在佇列裡等了 ") }
        .flatMap {
            Int(
                $0.replacingOccurrences(of: "按下買入的事件，在佇列裡等了 ", with: "")
                    .replacingOccurrences(of: " ms 才被處理", with: ""))
        }
}

extension Platform02Threading {
    static let l01 = Lab(
        id: "L01",
        title: "按下買入之後，要等一下才有反應",
        question: "第 1 題：主執行緒到底在做什麼？為什麼不能被卡住？",
        goal:
            "解碼這類耗時的工作要放背景，只有更新畫面回主執行緒。使用者按下買入時，事件不該排在重工作後面。先寫出會卡住按鈕的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform02-Threading/L01_MainThreadBlock.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「按下買入的事件，在佇列裡等了幾 ms 才被處理」。",
            "寫 TODO ②（修正版），選「修正版」執行：解碼放背景之後，按鈕的事件幾乎立刻被處理。",
            "想一想：為什麼解碼 80 ms，使用者卻等了不到 80 ms？（按鈕在解碼開始後 10 ms 才按，之後的時間都在排隊）",
        ],
        expectBug:
            "按下買入的事件等了約 70 到 80 ms 才被處理（解碼佔住主執行緒約 80 ms，按鈕在解碼開始後 10 ms 才按）。",
        expectFixed: "按下買入的事件等了不到 5 ms，幾乎立刻被處理。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("訂單簿已經顯示") {
                return "訂單簿沒有顯示：要呼叫 decodeOrderBook(raw)，再呼叫 render"
            }
            guard let ms = delayMs(lines) else { return "演練沒有跑完" }
            return ms >= 40
                ? nil : "Bug 還沒重現（等了 \(ms) ms）：要在主執行緒直接呼叫 decodeOrderBook"
        },
        checkFixed: { lines in
            if !lines.contains("訂單簿已經顯示") {
                return "訂單簿沒有顯示：要呼叫 decodeOrderBook(raw)，再呼叫 render"
            }
            guard let ms = delayMs(lines) else { return "演練沒有跑完" }
            return ms < 25
                ? nil : "按鈕還是等了 \(ms) ms：解碼要放到背景（Task.detached），只有 render 回主執行緒"
        }
    )
}
