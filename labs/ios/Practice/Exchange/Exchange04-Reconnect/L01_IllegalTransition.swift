// L01：App 在背景，連線卻自己又連回來了（狀態機的非法轉移）
// 對應複習站：Exchange04Reconnect 第 1 題「為什麼重連要寫成狀態機？狀態和事件有哪些？」
//
// 情境：連線狀態機（state machine）：idle、connecting、connected、waiting（等一段時間再重連）、offline、suspended（App 在背景，主動斷線）。
//       轉移是純函式 reduce(狀態, 事件) → (新狀態, 要做的事)，要做的事（effect）只用資料描述，由外面去執行。
//       演練依序送進這些事件：開始連線 → 連上了 → 連線意外斷了（closed，狀態機要排重試）→ 等待結束（retryTimerFired，重新連線）→ 又連上了
//       → App 進背景（狀態機要求關掉 socket、取消重試的計時器）
//       → socket 關閉的通知（closed，我們自己關的，通知會晚一點才到）→ 先前排的計時器響了（retryTimerFired）→ App 回前景。
//
// 你要寫：
//   ① Bug 版（L01Bug.reduce）：closed 在任何狀態都當成連線失敗（排重試）；retryTimerFired 在任何狀態都連線。
//   ② 修正版（L01Fixed.reduce）：closed 只有在 connecting／connected 才算失敗；retryTimerFired 只有在 waiting 才連線；其他組合忽略。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange04-Reconnect/L01_IllegalTransition.swift
import Foundation
import LabKit

private enum State: Equatable {
    case idle
    case connecting(attempt: Int)
    case connected(attempt: Int, sinceMs: Int64)
    case waiting(attempt: Int, retryAtMs: Int64)
    case offline
    case suspended

    var attempt: Int {
        switch self {
        case .connecting(let n), .connected(let n, _), .waiting(let n, _): return n
        default: return 0
        }
    }
}

private enum Event {
    case start
    case opened(nowMs: Int64)
    case closed(nowMs: Int64)
    case retryTimerFired
    case networkLost
    case networkAvailable
    case enteredBackground
    case enteredForeground
}

private enum Effect: Equatable {
    case connect
    case scheduleRetry(delayMs: Int64)
    case cancelRetry
    case closeSocket

    /// 會讓連線或計時器開始動起來的事
    var startsWork: Bool {
        switch self {
        case .connect, .scheduleRetry: return true
        case .cancelRetry, .closeSocket: return false
        }
    }
}

/// 兩個版本共用的轉移（closed 與 retryTimerFired 以外的事件）。回傳 nil 代表這個組合這裡沒有處理
private func commonReduce(_ state: State, _ event: Event) -> (State, [Effect])? {
    switch (state, event) {
    case (.idle, .start):
        return (.connecting(attempt: 0), [.connect])
    case (.connecting(let n), .opened(let now)):
        return (.connected(attempt: n, sinceMs: now), [])
    case (.connecting, .networkLost), (.connected, .networkLost), (.waiting, .networkLost):
        return (.offline, [.cancelRetry, .closeSocket])
    case (.offline, .networkAvailable):
        return (.connecting(attempt: 0), [.connect])
    case (.connecting, .enteredBackground), (.connected, .enteredBackground), (.waiting, .enteredBackground), (.offline, .enteredBackground):
        return (.suspended, [.cancelRetry, .closeSocket])           // 進背景：主動斷線並取消計時器
    case (.suspended, .enteredForeground):
        return (.connecting(attempt: 0), [.connect])                // 回前景：當成一條全新的連線
    default:
        return nil
    }
}

/// 連線失敗後的下一個狀態：等待時間用正確的指數退避（亂數固定 0.5，結果每次一樣）
private func failed(from state: State, nowMs: Int64) -> (State, [Effect]) {
    let next = state.attempt + 1
    let delay = E04Backoff.delayMs(attempt: next, random: 0.5)
    return (.waiting(attempt: next, retryAtMs: nowMs + delay), [.scheduleRetry(delayMs: delay)])
}

/// 演練流程（兩個版本共用）：依序送進事件，記下每一步的結果，數「App 在背景時，狀態機要求連線或排計時器」的次數
private func scenario(_ log: LabLog, reduce: (State, Event) -> (State, [Effect])) {
    let events: [(String, Event)] = [
        ("start", .start), ("opened", .opened(nowMs: 100)),
        ("closed（連線意外斷了）", .closed(nowMs: 1000)), ("retryTimerFired（等待結束）", .retryTimerFired), ("opened", .opened(nowMs: 1600)),
        ("enteredBackground", .enteredBackground),
        ("closed（我們自己關 socket 的通知）", .closed(nowMs: 1800)), ("retryTimerFired（先前排的計時器）", .retryTimerFired),
        ("enteredForeground", .enteredForeground),
    ]
    var state = State.idle
    var inBackground = false
    var violations = 0
    for (name, event) in events {
        if case .enteredBackground = event { inBackground = true }
        if case .enteredForeground = event { inBackground = false }
        let (next, effects) = reduce(state, event)
        let bad = inBackground && effects.contains(where: { $0.startsWork })
        if bad { violations += 1 }
        log("\(name) → \(next)，要做的事：\(effects.isEmpty ? "無" : effects.map { "\($0)" }.joined(separator: "、"))\(bad ? "  ← App 在背景，不該連線或排計時器" : "")")
        state = next
    }
    log("App 在背景時，狀態機要求連線或排計時器 \(violations) 次；最後狀態：\(state)")
}

// MARK: - Bug 版
private enum L01Bug {
    static func reduce(_ state: State, _ event: Event) -> (State, [Effect]) {
        if let result = commonReduce(state, event) { return result }
        // TODO ①：除了 commonReduce 處理的事件，只剩下 closed 和 retryTimerFired（其他事件維持 (state, [])）：
        //   .closed(let now)：不管現在是什麼狀態，都當成「連線失敗」，回傳 failed(from: state, nowMs: now)
        //   .retryTimerFired：不管現在是什麼狀態，都回傳 (.connecting(attempt: state.attempt), [.connect])
        return (state, [])
    }

    static func run(_ log: LabLog) async {
        scenario(log, reduce: reduce(_:_:))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func reduce(_ state: State, _ event: Event) -> (State, [Effect]) {
        if let result = commonReduce(state, event) { return result }
        switch (state, event) {
        // TODO ②：用 (state, event) 配對，只處理這兩種合理的組合，其他組合都落到 default（忽略）：
        //   (.connecting 或 .connected, .closed(let now))：連線失敗，回傳 failed(from: state, nowMs: now)
        //   (.waiting(let n, _), .retryTimerFired)：等待結束，回傳 (.connecting(attempt: n), [.connect])
        default:
            return (state, [])                                                      // 其他組合一律忽略
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, reduce: reduce(_:_:))
    }
}

// MARK: - 演練設定
private func violationCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("App 在背景時") }) else { return nil }
    return line.components(separatedBy: " 次").first?.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange04Reconnect {
    static let l01 = Lab(
        id: "L01",
        title: "App 在背景，連線卻自己又連回來了",
        question: "第 1 題：為什麼重連要寫成狀態機？狀態和事件有哪些？",
        goal: "狀態機要明確列出每個狀態可以接受哪些事件。不合理的組合（例如在背景時收到 socket 關閉的通知、計時器響了）一律忽略，不能排計時器或連線。先寫出任何狀態都處理 closed 的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange04-Reconnect/L01_IllegalTransition.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看進背景之後的 closed、retryTimerFired 讓狀態機做了什麼事、最後狀態是什麼。",
            "想一想：進背景時我們自己關了 socket，socket 的 closed 通知遲早會來。這時是「連線失敗」嗎？",
            "寫 TODO ②（修正版），選「修正版」執行，進背景之後不該有任何連線或排計時器的動作，回前景才連線。",
            "進階：狀態機只回傳「要做什麼」，真正連線、排計時器由外面執行，所以可以用一串事件寫單元測試，不用真的連網路。",
        ],
        expectBug: "進背景後 closed 讓狀態變成 waiting 並排了重試計時器、retryTimerFired 又要求連線，「App 在背景時，狀態機要求連線或排計時器 2 次」，回前景時狀態早就不是 suspended，沒有重新連線。",
        expectFixed: "進背景後的 closed、retryTimerFired 都被忽略，「App 在背景時，狀態機要求連線或排計時器 0 次；最後狀態：connecting(attempt: 0)」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = violationCount(lines) else { return "演練沒有跑完：要用 scenario(log, reduce:) 執行" }
            if !lines.contains(where: { $0.hasPrefix("start → connecting(attempt: 0)") }) { return "Bug 版也要能開始連線：commonReduce 已經處理了 start，reduce 要先呼叫它" }
            return n == 2 ? nil : "Bug 還沒重現：closed 在任何狀態都要排重試（waiting + scheduleRetry），retryTimerFired 在任何狀態都要連線（目前違規 \(n) 次）"
        },
        checkFixed: { lines in
            guard let n = violationCount(lines) else { return "演練沒有跑完：要用 scenario(log, reduce:) 執行" }
            if n != 0 { return "App 在背景時還是連線或排了計時器 \(n) 次：closed 只有 connecting／connected 才算失敗，retryTimerFired 只有 waiting 才連線" }
            if !lines.contains("closed（連線意外斷了） → waiting(attempt: 1, retryAtMs: 1500)，要做的事：scheduleRetry(delayMs: 500)") { return "連線意外斷了（connecting／connected 時收到 closed）要排重試：回傳 failed(from: state, nowMs: now)" }
            if !lines.contains("retryTimerFired（等待結束） → connecting(attempt: 1)，要做的事：connect") { return "等待中（waiting）收到 retryTimerFired，要重新連線：(.connecting(attempt: n), [.connect])" }
            return lines.contains("App 在背景時，狀態機要求連線或排計時器 0 次；最後狀態：connecting(attempt: 0)") ? nil : "回前景時 suspended 要變成 connecting(attempt: 0)（commonReduce 已經處理，reduce 要先呼叫它）"
        }
    )
}
