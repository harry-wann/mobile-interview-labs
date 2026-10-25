// L01：寫入一筆價格，App 直接當掉（os_unfair_lock 不可重入：
// 持鎖的時候又呼叫會再拿同一把鎖的函式）
// 對應複習站：Advanced04Races 第 2 題「iOS 有哪些鎖和同步工具？怎麼選？」
//
// 情境：價格表 PriceBook 用 OSAllocatedUnfairLock 保護字典。寫入一筆價格之後，
// 要順便檢查有沒有觸發價格提醒（價格 ≥ 15,000,000），
//        檢查的函式 isAlertTriggered(for:) 平常也會被別的地方呼叫，
// 所以它自己也會 withLock 去讀最新價。
//
// 你要寫：
//   ① Bug 版 updatePrice：在 withLock 裡面存好價格，然後呼叫
// isAlertTriggered(for:)（它又 withLock 一次）：
// 同一條執行緒持鎖時再拿同一把鎖，
//      os_unfair_lock 不可重入，App 會直接當掉。這個版本寫對了會當掉，要從
// Xcode 執行才看得到當掉的位置（所以自動檢查略過 Bug 版）。
//   ② 修正版：把「已經持鎖」的邏輯拆成內部函式 alertTriggered(in:symbol:)
// （直接用傳進來的字典，不拿鎖），
//      updatePrice 在 withLock 裡呼叫它；對外的 isAlertTriggered 才自己
// withLock 再呼叫它。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced04-Races/L01_UnfairLockReentry.swift
import Foundation
import os
import LabKit

private let alertPrice: Decimal = 15_000_000

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, updatePrice: (String, Decimal) -> Bool) {
    log("寫入 BTC/JPY 15,000,000")
    let triggered = updatePrice("BTC/JPY", 15_000_000)
    log("寫入完成，價格提醒觸發：\(triggered ? "是" : "否")")
}

// MARK: - Bug 版
private enum L01Bug {
    final class PriceBook {
        private let prices = OSAllocatedUnfairLock(
            initialState: [String: Decimal]())

        func updatePrice(_ symbol: String, _ price: Decimal) -> Bool {
            // TODO ①：prices.withLock { state in … }：state[symbol] = price，
            // 然後 return isAlertTriggered(for: symbol)
            //        （還拿著鎖，又呼叫會再拿同一把鎖的函式：會當掉）
            false
        }

        func isAlertTriggered(for symbol: String) -> Bool {
            prices.withLock { ($0[symbol] ?? 0) >= alertPrice }
        }
    }

    static func run(_ log: LabLog) async {
        let book = PriceBook()
        scenario(log) { book.updatePrice($0, $1) }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class PriceBook {
        private let prices = OSAllocatedUnfairLock(
            initialState: [String: Decimal]())

        func updatePrice(_ symbol: String, _ price: Decimal) -> Bool {
            // TODO ②-1：prices.withLock { state in … }：state[symbol] = price，
            // 然後 return alertTriggered(in: state, symbol: symbol)
            false
        }

        /// 對外的版本：自己拿鎖，再呼叫內部的版本
        func isAlertTriggered(for symbol: String) -> Bool {
            // TODO ②-2：prices.withLock { … } 裡呼叫 alertTriggered(in: $0,
            // symbol: symbol)
            false
        }

        /// 內部的版本：呼叫者必須已經持有鎖
        private func alertTriggered(
            in state: [String: Decimal], symbol: String
        ) -> Bool {
            // TODO ②-3：傳進來的字典裡這個交易對的價格（沒有就當 0）大於等於
            // alertPrice 就是 true
            false
        }
    }

    static func run(_ log: LabLog) async {
        let book = PriceBook()
        scenario(log) { book.updatePrice($0, $1) }
        log("另外單獨查詢提醒：\(book.isAlertTriggered(for: "BTC/JPY") ? "是" : "否")")
    }
}

// MARK: - 演練設定
extension Advanced04Races {
    static let l01 = Lab(
        id: "L01",
        title: "寫入一筆價格，App 直接當掉",
        question: "第 2 題：iOS 有哪些鎖和同步工具？怎麼選？",
        goal:
            "os_unfair_lock（包含 OSAllocatedUnfairLock）不可重入。持鎖的時候不要呼叫會再拿同一把鎖的函式：把「已持鎖」的邏輯拆成內部函式。先寫出會當掉的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced04-Races/L01_UnfairLockReentry.swift，寫 TODO ①。",
            "選「練習」「Bug 版」按「執行」：寫對的話，畫面上會先提醒「這個版本會當掉」。要從 Xcode 執行才看得到當掉的位置：Xcode 會停在 withLock 那一行，Console 印出 Trying to recursively lock an os_unfair_lock。",
            "（想用指令確認：`./check.sh run Advanced04Races L01 solution bug` 會印出「App 當掉了」。）",
            "寫 TODO ②，選「修正版」執行：寫入完成，提醒觸發「是」，單獨查詢也是「是」。",
            "想一想：換成 NSRecursiveLock 也能不當掉，為什麼通常不建議？（需要重入表示臨界區太大，持鎖時呼叫了不該呼叫的東西，見這個單元的第 2、4 題）",
        ],
        expectBug:
            "log 只有「寫入 BTC/JPY 15,000,000」，然後 App 當掉（Xcode 停在 withLock，Console：Trying to recursively lock an os_unfair_lock）。",
        expectFixed: "寫入 BTC/JPY 15,000,000；寫入完成，價格提醒觸發：是；另外單獨查詢提醒：是。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: nil,
        checkFixed: { lines in
            if !lines.contains("寫入完成，價格提醒觸發：是") {
                return
                    "寫入後要判斷價格 ≥ 15,000,000 觸發提醒：updatePrice 要回傳 alertTriggered(in: state, symbol: symbol)"
            }
            if !lines.contains("另外單獨查詢提醒：是") {
                return "對外的 isAlertTriggered(for:) 要自己拿鎖，再呼叫內部的 alertTriggered"
            }
            return nil
        }
    )
}
