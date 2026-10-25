// L02：120 Hz 的 iPhone 上，掉了一格卻沒被偵測到（每一格的時間預算 = 1000 ÷ 刷新率）
// 對應複習站：Platform02Threading 第 2 題「每一格畫面有多少時間？60 Hz 和 120 Hz 差在哪？」
//
// 情境：App 裡有一個卡頓偵測，量每一格花了多少 ms，超過「時間預算」的 1.5 倍就算掉格（hitch）。
//        演練用兩份錄下來的資料：60 Hz 與 120 Hz 的手機各量了一段，每一格花的毫秒數已經寫好。
//
// 你要寫：
//   ① Bug 版：每一格的時間預算寫死 16.7 ms。
//   ② 修正版：預算 = 1000 ÷ 刷新率。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform02-Threading/L02_FrameBudget.swift
import Foundation
import LabKit

/// 卡頓偵測（兩個版本共用）：一格花的時間超過預算的 1.5 倍，就算掉格
private func hitchCount(_ frameMs: [Double], budgetMs: Double) -> Int {
    frameMs.filter { $0 > budgetMs * 1.5 }.count
}

/// 錄下來的資料（兩個版本共用）：每一格花的毫秒數
private let frames60: [Double] = [16.67, 16.67, 33.33, 16.67, 50.0, 16.67]                        // 實際掉了 2 次
private let frames120: [Double] = [8.33, 8.33, 16.67, 8.33, 16.67, 8.33, 33.33, 8.33]            // 實際掉了 3 次

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, budgetMs: (_ refreshRate: Double) -> Double) {
    let on60 = hitchCount(frames60, budgetMs: budgetMs(60))
    let on120 = hitchCount(frames120, budgetMs: budgetMs(120))
    log("60 Hz 的手機：偵測到 \(on60) 次掉格（實際 2 次）")
    log("120 Hz 的手機：偵測到 \(on120) 次掉格（實際 3 次）")
}

// MARK: - Bug 版
private enum L02Bug {
    /// 這一格的時間預算
    static func budgetMs(refreshRate: Double) -> Double {
        // TODO ①：回傳寫死的 16.7（只想到 60 Hz）。現在先回傳 0
        return 0
    }

    static func run(_ log: LabLog) async {
        scenario(log, budgetMs: budgetMs(refreshRate:))
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func budgetMs(refreshRate: Double) -> Double {
        // TODO ②：回傳 1000 除以 refreshRate（一秒 ÷ 每秒幾格）。現在先回傳 0
        return 0
    }

    static func run(_ log: LabLog) async {
        scenario(log, budgetMs: budgetMs(refreshRate:))
    }
}

// MARK: - 演練設定
private func detected(_ lines: [String], prefix: String) -> Int? {
    lines.first { $0.hasPrefix(prefix) }
        .flatMap { Int($0.replacingOccurrences(of: prefix, with: "").components(separatedBy: " ").first ?? "") }
}

extension Platform02Threading {
    static let l02 = Lab(
        id: "L02",
        title: "120 Hz 的手機上，掉格沒被偵測到",
        question: "第 2 題：每一格畫面有多少時間？60 Hz 和 120 Hz 差在哪？",
        goal: "每一格的時間預算 = 1000 ÷ 刷新率。卡頓偵測要用手機實際的刷新率算預算，兩種手機都要抓到所有掉格。先寫出漏掉 120 Hz 掉格的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform02-Threading/L02_FrameBudget.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，比較兩種手機「偵測到幾次」和「實際幾次」。",
            "算一算：120 Hz 的預算是幾 ms？掉一格（16.67 ms）要超過預算的 1.5 倍才算掉格，門檻是多少？",
            "寫 TODO ②（修正版），選「修正版」執行：兩種手機都抓到實際的次數。",
        ],
        expectBug: "60 Hz 的手機偵測到 2 次（正確）；120 Hz 的手機只偵測到 1 次，漏了 2 次（16.67 ms 的掉格都被當成正常）。",
        expectFixed: "60 Hz 偵測到 2 次、120 Hz 偵測到 3 次，都和實際相同。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let a = detected(lines, prefix: "60 Hz 的手機：偵測到 "), let b = detected(lines, prefix: "120 Hz 的手機：偵測到 ") else { return "演練沒有跑完" }
            if a != 2 { return "Bug 版的預算寫死 16.7 ms，60 Hz 的手機應該剛好偵測到 2 次（現在 \(a) 次）" }
            if b >= 3 { return "Bug 還沒重現：Bug 版的預算要寫死 16.7，120 Hz 的手機就會漏掉掉格" }
            return nil
        },
        checkFixed: { lines in
            guard let a = detected(lines, prefix: "60 Hz 的手機：偵測到 "), let b = detected(lines, prefix: "120 Hz 的手機：偵測到 ") else { return "演練沒有跑完" }
            if a != 2 { return "60 Hz 的手機應該偵測到 2 次（現在 \(a) 次）" }
            if b != 3 { return "120 Hz 的手機應該偵測到 3 次（現在 \(b) 次）：預算要依刷新率算，1000 ÷ refreshRate" }
            return nil
        }
    )
}
