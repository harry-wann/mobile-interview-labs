// L01：資料一大包進來，心跳來不及回，伺服器把連線切掉（管線切段：socket 這一段不能被解析和畫面卡住）
// 對應複習站：Advanced05HighFrequency 第 2 題「行情從伺服器到畫面，整條處理管線怎麼切？」
//
// 情境：伺服器的規則：每 150 毫秒送一次 ping，client 要在 300 毫秒內回 pong；連續兩次逾時就切斷連線。
//        連線一開始，伺服器就推來一大包行情（解析 JSON 要花 800 毫秒）。後面接著是 4 次 ping。
//        （演練把真實的「約 5 秒一次、連續兩次沒回」縮小了，邏輯一樣。）
//
// 你要寫（makeClient 回傳的 closure，就是「收到一則訊息」的處理）：
//   ① Bug 版：所有訊息都 queue.async 排進同一條序列佇列。.data：Thread.sleep(0.8 秒)（解析 JSON）後呼叫 onParsed()；
//      .ping(sentAt)：pingIndex 加 1，呼叫 onPong(pingIndex, a05NowMs() - sentAt)。ping 排在解析後面，pong 就晚了。
//   ② 修正版：.data 交給另一條序列佇列 parser.async（Thread.sleep(0.8 秒) 後 onParsed()）；
//      .ping 不排隊，在收到的當下直接 pingIndex 加 1、onPong(pingIndex, a05NowMs() - sentAt)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced05-HighFrequency/L01_HeartbeatStarvation.swift
import Foundation
import LabKit

private let pingIntervalMs = 150
private let pongDeadlineMs = 300
private let parseCostMs = 800
private let pingCount = 4

/// 伺服器送來的訊息（兩個版本共用）
private enum Message {
    case data
    case ping(sentAt: Double)
}

/// 演練流程（兩個版本共用）：模擬伺服器，把訊息交給 client 的 onMessage（在「socket 執行緒」上呼叫）；最後依 pong 的延遲判斷有沒有被切斷
private func scenario(_ log: LabLog, makeClient: (_ onPong: @escaping (Int, Double) -> Void, _ onParsed: @escaping () -> Void) -> (Message) -> Void) {
    let lock = NSLock()
    var delays: [Int: Double] = [:]
    var parsed = 0
    let allPongs = DispatchSemaphore(value: 0)
    let parsedDone = DispatchSemaphore(value: 0)
    let onMessage = makeClient({ index, delay in
        lock.lock(); delays[index] = delay; let n = delays.count; lock.unlock()
        if n == pingCount { allPongs.signal() }
    }, {
        lock.lock(); parsed += 1; lock.unlock()
        parsedDone.signal()
    })

    let server = Thread {
        onMessage(.data)                                           // 連線一開始：一大包行情
        for _ in 1...pingCount {
            Thread.sleep(forTimeInterval: Double(pingIntervalMs) / 1000)
            onMessage(.ping(sentAt: a05NowMs()))
        }
    }
    server.name = "socket"
    server.start()
    _ = allPongs.wait(timeout: .now() + 3)                          // 等 4 次 pong 都回了
    _ = parsedDone.wait(timeout: .now() + 3)                        // 等解析做完

    lock.lock()
    let snapshot = delays
    let parsedCount = parsed
    lock.unlock()
    var misses = 0, disconnected = false
    for i in 1...pingCount {
        let delay = snapshot[i] ?? 99_999
        let late = delay > Double(pongDeadlineMs)
        misses = late ? misses + 1 : 0
        log("第 \(i) 次 ping，pong 延遲 \(Int(min(delay, 99_999))) ms\(late ? "（逾時）" : "")")
        if misses >= 2 { disconnected = true }
    }
    log(disconnected ? "連續 2 次逾時：伺服器切斷連線" : "每次 pong 都在 \(pongDeadlineMs) ms 內送出：連線正常")
    log("資料解析完成：\(parsedCount) 包")
}

// MARK: - Bug 版
private enum L01Bug {
    static func run(_ log: LabLog) async {
        await Task.detached {
            scenario(log) { onPong, onParsed in
                let queue = DispatchQueue(label: "everything")               // 所有訊息都排進同一條序列佇列
                var pingIndex = 0
                return { message in
                    // TODO ①：queue.async { switch message { … } }：
                    //   .data：Thread.sleep(forTimeInterval: Double(parseCostMs) / 1000)，然後 onParsed()
                    //   .ping(let sentAt)：pingIndex += 1，onPong(pingIndex, a05NowMs() - sentAt)
                    _ = (queue, pingIndex, onPong, onParsed, message)
                }
            }
        }.value
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func run(_ log: LabLog) async {
        await Task.detached {
            scenario(log) { onPong, onParsed in
                let parser = DispatchQueue(label: "parser")                      // 解析另外用一條序列佇列
                var pingIndex = 0
                return { message in
                    // TODO ②：switch message { … }（這裡就是 socket 這一段，不要整個包進 async）：
                    //   .data：parser.async { Thread.sleep(forTimeInterval: Double(parseCostMs) / 1000)；onParsed() }
                    //   .ping(let sentAt)：不排隊，直接 pingIndex += 1，onPong(pingIndex, a05NowMs() - sentAt)
                    _ = (parser, pingIndex, onPong, onParsed, message)
                }
            }
        }.value
    }
}

// MARK: - 演練設定
extension Advanced05HighFrequency {
    static let l01 = Lab(
        id: "L01",
        title: "資料一大包進來，心跳來不及回，連線被切掉",
        question: "第 2 題：行情從伺服器到畫面，整條處理管線怎麼切？",
        goal: "心跳（ping／pong）要在 socket 這一段立刻回，解析與畫面更新放到別的佇列。解析再慢，連線也不會被切掉。先寫出全部排同一條佇列的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced05-HighFrequency/L01_HeartbeatStarvation.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看每次 ping 的 pong 延遲：前幾次排在 800 毫秒的解析後面，延遲都超過 300 毫秒，連續逾時，連線被切斷。",
            "寫 TODO ②，選「修正版」執行：每次 pong 延遲幾乎是 0 ms，連線正常，資料照樣解析完成。",
            "進階：Instruments 的 Time Profiler 或 Points of Interest 錄製，看 socket 的執行緒與解析的佇列各自在做什麼；Bug 版的 ping 處理會出現在被解析占滿的那條佇列上。",
            "想一想：gzip 解壓縮和 JSON 解析為什麼都屬於「socket 這一段之後」？主執行緒的畫面更新又該在管線的哪一段？（見這個單元的第 2 題）",
        ],
        expectBug: "4 次 ping 的 pong 延遲約 650、490、340、180 ms，前三次逾時（超過 300 ms），連續 2 次逾時：伺服器切斷連線。",
        expectFixed: "4 次 ping 的 pong 延遲都是 0 ms 左右，連線正常；資料解析完成：1 包。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("資料解析完成：1") }) { return "資料要解析完成（onParsed 要被呼叫）：Bug 版也會解析，只是排在同一條佇列" }
            return lines.contains("連續 2 次逾時：伺服器切斷連線") ? nil : "Bug 還沒重現：資料與 ping 要排進同一條序列佇列，ping 才會排在 800 毫秒的解析後面"
        },
        checkFixed: { lines in
            if lines.contains("連續 2 次逾時：伺服器切斷連線") { return "還是被切斷：ping 要在收到的當下立刻回 pong，不能排在解析後面" }
            if !lines.contains(where: { $0.hasPrefix("每次 pong 都在") }) { return "演練沒有跑完" }
            if !lines.contains(where: { $0.hasPrefix("資料解析完成：1") }) { return "資料沒有解析完成：.data 要交給 parser 佇列去解析" }
            return nil
        }
    )
}
