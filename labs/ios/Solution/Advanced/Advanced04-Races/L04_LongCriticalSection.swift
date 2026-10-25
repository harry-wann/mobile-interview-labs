// L04：存快照的時候，畫面讀一下價格就被卡住（鎖裡面做 I/O；DispatchSemaphore 當鎖不會捐贈優先權）
// 對應複習站：Advanced04Races 第 5 題「priority inversion 是什麼？」
//
// 情境：價格表同時被兩個地方使用：背景的低優先權（.utility）執行緒每隔一陣子把整份價格寫到檔案（存快照，約 400 毫秒的 I/O），
//        主執行緒（最高優先權）要讀一筆最新價來顯示。
// Bug 版：用 DispatchSemaphore(value: 1) 當鎖，背景執行緒「持鎖的期間寫檔案」。主執行緒要讀價格，得等整個寫檔案做完，卡約 400 毫秒；
//        而且 DispatchSemaphore 沒有「誰持有它」的概念，系統不會把主執行緒的優先權借給持鎖的背景執行緒，
//        背景執行緒如果又被中優先權的工作搶走 CPU，主執行緒會等更久（priority inversion）。Xcode 的 Thread Performance Checker 可能會對這種情形警告。
// 修正版：用 OSAllocatedUnfairLock（會做優先權捐贈），而且持鎖期間只複製資料（幾微秒），寫檔案放到鎖外面。主執行緒幾乎不用等。
import Foundation
import os
import LabKit

/// 演練流程（兩個版本共用）：背景開始存快照，主執行緒在存的途中讀價格，量主執行緒等了多久
private func scenario(
    _ log: LabLog,
    saveSnapshot: @escaping (_ started: DispatchSemaphore) -> Void,
    readLatest: () -> Int
) {
    let started = DispatchSemaphore(value: 0)
    let finished = DispatchSemaphore(value: 0)
    Thread {
        saveSnapshot(started)
        finished.signal()
    }.start()
    started.wait()                                              // 等背景開始存（Bug 版是已經拿到鎖）
    Thread.sleep(forTimeInterval: 0.02)
    let t0 = a04NowMs()
    let price = readLatest()                                    // 主執行緒讀一筆價格
    let waited = a04NowMs() - t0
    log("主執行緒讀到價格 \(price)，等了 \(Int(waited)) ms")
    finished.wait()
    log("快照寫完了")
}

// MARK: - Bug 版
private enum L04Bug {
    final class PriceTable {
        private let semaphore = DispatchSemaphore(value: 1)       // 當成鎖用：沒有「誰持有」的概念
        private var prices = ["BTC/JPY": 15_000_000, "ETH/JPY": 400_000]

        func saveSnapshot(started: DispatchSemaphore) {
            semaphore.wait()
            started.signal()
            Thread.sleep(forTimeInterval: 0.4)                  // 持鎖的期間寫檔案（示意）：I/O 在鎖裡面
            semaphore.signal()
        }

        func latest() -> Int {
            semaphore.wait()
            defer { semaphore.signal() }
            return prices["BTC/JPY"] ?? 0
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        let table = PriceTable()
        scenario(log, saveSnapshot: { table.saveSnapshot(started: $0) }, readLatest: { table.latest() })
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class PriceTable {
        private let prices = OSAllocatedUnfairLock(initialState: ["BTC/JPY": 15_000_000, "ETH/JPY": 400_000])

        func saveSnapshot(started: DispatchSemaphore) {
            let copy = prices.withLock { $0 }                   // 持鎖期間只複製資料
            started.signal()
            Thread.sleep(forTimeInterval: 0.4)                  // 寫檔案在鎖外面
            _ = copy
        }

        func latest() -> Int {
            prices.withLock { $0["BTC/JPY"] ?? 0 }
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        let table = PriceTable()
        scenario(log, saveSnapshot: { table.saveSnapshot(started: $0) }, readLatest: { table.latest() })
    }
}

// MARK: - 演練設定
extension Advanced04Races {
    static let l04 = Lab(
        id: "L04",
        title: "存快照的時候，畫面讀一下價格就被卡住",
        question: "第 5 題：priority inversion 是什麼？",
        goal: "鎖裡面只放很短的資料讀寫，不要做 I/O；需要同步讀寫時用會做優先權捐贈的鎖（OSAllocatedUnfairLock），不要用 DispatchSemaphore 當鎖。",
        steps: [
            "選「Bug 版」按「執行」，看「主執行緒讀到價格，等了幾 ms」：約 380 ms。執行的時候畫面會卡一下。",
            "選「修正版」按「執行」：等了不到幾毫秒。",
            "進階：Xcode Edit Scheme › Run › Diagnostics 勾選 Thread Performance Checker，從 Xcode 執行 Bug 版，Issues navigator 可能會警告主執行緒（User-interactive）正在等低優先權的執行緒（Utility）；有沒有出現警告要看系統版本，這個檢查只在從 Xcode 執行時才啟用。",
            "想一想：為什麼 os_unfair_lock 可以把等待者的優先權借給持鎖的人，DispatchSemaphore 卻不行？（unfair lock 記錄了持有者，見這個單元的第 5 題）",
        ],
        expectBug: "主執行緒讀到價格 15000000，等了約 380 ms（背景持鎖寫檔案 400 ms，扣掉啟動後的 20 ms）。",
        expectFixed: "主執行緒讀到價格 15000000，等了不到 5 ms。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let ms = a04Number(in: lines, prefix: "主執行緒讀到價格 15000000，等了 ") else { return "演練沒有跑完：latest() 要回傳 BTC/JPY 的價格 15000000" }
            return ms >= 200 ? nil : "主執行緒只等了 \(Int(ms)) ms：Bug 版要在持鎖的期間寫檔案（Thread.sleep 0.4 秒），主執行緒的 latest() 要拿同一個 semaphore"
        },
        checkFixed: { lines in
            guard let ms = a04Number(in: lines, prefix: "主執行緒讀到價格 15000000，等了 ") else { return "演練沒有跑完：latest() 要回傳 BTC/JPY 的價格 15000000" }
            return ms <= 150 ? nil : "主執行緒等了 \(Int(ms)) ms，太久：寫檔案要放到鎖外面，持鎖期間只複製資料"
        }
    )
}
