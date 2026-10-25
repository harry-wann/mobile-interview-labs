// L06：價格在門檻附近上下跳，同一個提醒連續送了好幾次（去重與重新武裝）
// 對應複習站：Platform03BackgroundPush 第 6 題「價格提醒要怎麼設計？」
//
// 情境：使用者設定「BTC/JPY 漲到 1,000 萬日圓就通知我」。伺服器逐筆比對行情，
// 觸發時發 alert 推播。
//        演練送進一串價格：在 1,000 萬附近上下跳了一陣，後來跌到 980 萬，
// 再漲到 1,010 萬。看一共發了幾次推播。
//        （價格提醒要由伺服器比對，這是第 1、6 題的結論；
// 這個演練練的是比對邏輯本身，不管 Swift 還是 Kotlin 都一樣。）
//
// 你要寫：
//   ① Bug 版：價格 >= 門檻就回傳 true（每一筆都發）。
//   ② 修正版：只在從下方穿過門檻時發一次，進入「已觸發」；價格跌到門檻 99%
// 以下才重新武裝。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform03-BackgroundPush/L06_AlertRepeat.swift
import Foundation
import LabKit

/// 價格序列（兩個版本共用）：在 1,000 萬附近跳了一陣，跌到 980 萬，再漲到 1,
/// 010 萬
private let prices = [
    9_900_000, 9_990_000, 10_000_000, 10_010_000, 9_995_000, 10_020_000,
    10_030_000, 9_998_000, 10_050_000, 9_800_000, 10_100_000,
]
private let threshold = 10_000_000

/// 提醒引擎（兩個版本各自實作）
private protocol AlertEngine: AnyObject {
    /// 收到一筆價格，回傳「這一筆要不要發推播」
    func onPrice(_ price: Int) -> Bool
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, engine: AlertEngine) {
    var sent: [Int] = []
    for price in prices where engine.onPrice(price) { sent.append(price) }
    log("門檻 \(threshold)，\(prices.count) 筆價格，一共發了 \(sent.count) 次推播")
    log("發推播的價格：\(sent.map(String.init).joined(separator: "、"))")
}

// MARK: - Bug 版
private enum L06Bug {
    final class Engine: AlertEngine {
        func onPrice(_ price: Int) -> Bool {
            // TODO ①：價格 >= threshold 就回傳 true（每一筆都發）。現在先回傳
            // false
            false
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, engine: Engine())
    }
}

// MARK: - 修正版
private enum L06Fixed {
    final class Engine: AlertEngine {
        // 已觸發：價格回到門檻 99% 以下之前不再發
        private var triggered = false

        func onPrice(_ price: Int) -> Bool {
            // TODO ②：用 triggered 記住「已觸發」。已觸發：價格跌到 threshold
            // 的 99% 以下（threshold * 99 / 100）就重新武裝（triggered =
            // false），這一筆不發；
            //        還沒觸發：價格 >= threshold 就設成已觸發並回傳 true；
            // 其他情況回傳 false
            return false
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, engine: Engine())
    }
}

// MARK: - 演練設定
private func sentCount(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("門檻 ") }
        .flatMap { $0.components(separatedBy: "一共發了 ").last }
        .flatMap { Int($0.replacingOccurrences(of: " 次推播", with: "")) }
}

extension Platform03BackgroundPush {
    static let l06 = Lab(
        id: "L06",
        title: "價格在門檻附近跳，同一個提醒響了好幾次",
        question: "第 6 題：價格提醒要怎麼設計？",
        goal:
            "價格穿過門檻只通知一次；價格在門檻附近上下跳時不重複通知；跌回去之後再漲過門檻，才通知第二次。先寫出重複發送的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform03-BackgroundPush/L06_AlertRepeat.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「一共發了幾次推播」。",
            "寫 TODO ②（修正版），選「修正版」執行：應該只發 2 次（第一次穿過 1,000 萬、跌到 980 萬重新武裝後再穿過 1,010 萬）。",
            "想一想：真的系統還要加哪些設計？（apns-collapse-id／collapse_key 讓過時的提醒被新的取代、TTL 設短、冷卻時間、伺服器端用 alertId 去重）",
        ],
        expectBug: "11 筆價格一共發了 6 次推播（價格在 1,000 萬以上的每一筆都發）。",
        expectFixed: "一共發了 2 次推播：10000000 與 10100000。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = sentCount(lines) else { return "演練沒有跑完" }
            return n >= 5 ? nil : "Bug 還沒重現（只發了 \(n) 次）：價格 >= 門檻就回傳 true，每一筆都發"
        },
        checkFixed: { lines in
            guard let n = sentCount(lines) else { return "演練沒有跑完" }
            if n == 0 { return "一次都沒有發：價格從下方穿過門檻時要回傳 true" }
            if n > 2 { return "發了 \(n) 次，太多了：穿過之後要進入「已觸發」，價格跌到門檻 99% 以下才重新武裝" }
            if n < 2 {
                return "只發了 \(n) 次：價格跌到 980 萬（門檻的 99% 以下）要重新武裝，再漲到 1,010 萬才發第二次"
            }
            return nil
        }
    )
}
