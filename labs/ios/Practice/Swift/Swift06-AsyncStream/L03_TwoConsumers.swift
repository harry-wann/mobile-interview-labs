// L03：兩個畫面各自 for await 同一個 stream，各自只收到一半的價格（AsyncStream
// 只支援單一消費者）
// 對應複習站：Swift06AsyncStream 第 2 題「行情來得比畫面處理得快時怎麼辦？」
// （最後一個限制：AsyncStream 只支援單一消費者）
//
// 情境：同一份價格 stream，報價標籤和走勢圖兩個地方都想收到每一筆價格（每 10
// 毫秒一筆，共 20 筆）。
//
// 你要寫：
//   ① Bug 版：開兩個 Task（報價標籤、走勢圖），各自對同一個 stream 做 for
// await，每收到一筆就 hit() 自己的計數器。
//   ② 修正版：寫 Broadcaster：subscribe() 回傳各訂閱者自己的 stream；
// run(source:) 只用一個 Task 讀來源，每筆轉送給所有訂閱者，來源結束時 finish
// 所有訂閱者。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift06-AsyncStream/L03_TwoConsumers.swift
import Foundation
import LabKit

private let tickCount = 20

/// 每 10 毫秒送一筆價格，共 tickCount 筆（兩個版本共用）
private func startProducing(_ continuation: AsyncStream<Int>.Continuation) {
    Task {
        for tick in 1...tickCount {
            continuation.yield(tick)
            try? await Task.sleep(for: .milliseconds(10))
        }
        continuation.finish()
    }
}

// MARK: - Bug 版
private enum L03Bug {
    static func run(_ log: LabLog) async {
        let (stream, continuation) = AsyncStream.makeStream(of: Int.self)
        let labelCount = Swift06AsyncStream.Counter()
        let chartCount = Swift06AsyncStream.Counter()

        // TODO ①：label（報價標籤）與 chart（走勢圖）兩個 Task，各自對同一個
        // stream 做 for await，每收到一筆就 labelCount.hit()／chartCount.hit()
        let label = Task {}
        let chart = Task {}
        startProducing(continuation)
        await label.value
        await chart.value
        log("共送出 \(tickCount) 筆")
        log("報價標籤收到 \(labelCount.count) 筆、走勢圖收到 \(chartCount.count) 筆")
    }
}

// MARK: - 修正版
private enum L03Fixed {
    /// 簡單的 fan-out：每個訂閱者拿到自己的 stream，
    /// 來源的每一筆都轉送給每一個訂閱者
    final class Broadcaster: @unchecked Sendable {
        private let lock = NSLock()
        private var continuations: [AsyncStream<Int>.Continuation] = []

        func subscribe() -> AsyncStream<Int> {
            // TODO ②-1：建立 AsyncStream.makeStream(of: Int.self)，把
            // continuation 存進 continuations（用 lock.withLock 保護），回傳
            // stream
            let (stream, continuation) = AsyncStream.makeStream(of: Int.self)
            continuation.finish()
            return stream
        }

        func run(source: AsyncStream<Int>) {
            // TODO ②-2：開一個 Task：for await 讀 source，每一筆 yield 給
            // continuations 裡的每一個；source 結束後，把每一個 continuation
            // finish()
        }
    }

    static func run(_ log: LabLog) async {
        let (stream, continuation) = AsyncStream.makeStream(of: Int.self)
        let broadcaster = Broadcaster()
        let labelStream = broadcaster.subscribe()
        let chartStream = broadcaster.subscribe()
        let labelCount = Swift06AsyncStream.Counter()
        let chartCount = Swift06AsyncStream.Counter()

        let label = Task { for await _ in labelStream { labelCount.hit() } }
        let chart = Task { for await _ in chartStream { chartCount.hit() } }
        broadcaster.run(source: stream)
        startProducing(continuation)
        await label.value
        await chart.value
        log("共送出 \(tickCount) 筆")
        log("報價標籤收到 \(labelCount.count) 筆、走勢圖收到 \(chartCount.count) 筆")
    }
}

// MARK: - 演練設定
extension Swift06AsyncStream {
    static let l03 = Lab(
        id: "L03",
        title: "兩個畫面各只收到一半價格",
        question: "第 2 題：行情來得比畫面處理得快時怎麼辦？（AsyncStream 只支援單一消費者）",
        goal: "報價標籤和走勢圖都要收到每一筆價格。找出為什麼各自只收到一部分，改成每個訂閱者都收到全部。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：兩個消費者各收到幾筆，加起來是不是剛好 20 筆，出現 ✓。",
            "想一想：AsyncStream 是廣播嗎？兩個 for await 同時等同一個 stream，一筆價格會給誰？",
            "寫 TODO ②，選「修正版」執行：兩個消費者都收到 20 筆。",
        ],
        expectBug: "報價標籤與走勢圖各收到約一半（兩個加起來剛好 20 筆），分食同一個 stream。",
        expectFixed: "報價標籤收到 20 筆、走勢圖收到 20 筆。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let last = lines.last, last.hasPrefix("報價標籤收到 ") else {
                return "找不到兩個消費者收到的筆數"
            }
            let numbers = last.split(whereSeparator: { !$0.isNumber })
                .compactMap { Int($0) }
            guard numbers.count == 2 else { return "找不到兩個消費者收到的筆數" }
            guard numbers[0] + numbers[1] == 20 else {
                return "兩個消費者收到的筆數加起來應該是 20（分食），實際是 \(numbers[0] + numbers[1])"
            }
            return numbers[0] < 20 && numbers[1] < 20
                ? nil : "Bug 還沒重現：兩個消費者應該各只收到一部分（兩個 Task 對同一個 stream for await）"
        },
        checkFixed: { lines in
            lines.last == "報價標籤收到 20 筆、走勢圖收到 20 筆"
                ? nil
                : "兩個消費者都應該收到 20 筆：只讓一個 Task 讀來源 stream，每筆轉送給每個訂閱者各自的 stream"
        }
    )
}
