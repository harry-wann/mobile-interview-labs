// L01：整個換成新的手續費計算，使用者被多收、少收了幾圓，
// 卻沒有人發現（strangler：新舊並存、先比對再切換）
// 對應複習站：Advanced09Architecture 第 1 題「面對已經上線的舊專案，
// 怎麼逐步演進，而不是整個重寫？」
//
// 情境：舊的手續費計算（legacy）是「每一筆成交各自算 0.1%，四捨五入到圓，
// 再加總」。團隊把它重寫成乾淨的新實作，
//       新實作「先把成交金額加總，再算一次 0.1% 並四捨五入」。
// 看起來只是整理程式碼，但四捨五入的時機不同，結果就不同。
//       演練有 6 筆委託，每筆有 1 到 3 筆成交（金額單位日圓）。
// 這個差異沒有測試保護（沒有 characterization test）。
//
// 你要寫：
//   ① Bug 版：big bang：直接把呼叫端換成新實作。
// 新舊結果不一樣的委託沒有任何記錄，沒人發現。
//   ② 修正版：shadow run：使用者看到的仍然是舊實作的結果，同時跑新實作比對，
// 不一致就記錄。不一致降到 0 才切換。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced09-Architecture/L01_ShadowRun.swift
import Foundation
import LabKit

private typealias FeeFunction = ([Int]) -> Int
private typealias OnMismatch = (_ fills: [Int], _ old: Int, _ new: Int) -> Void

/// 0.1% 的手續費，四捨五入到圓
private func roundedFee(_ notional: Int) -> Int { (notional + 500) / 1000 }

/// 舊實作：每筆成交各自算、各自四捨五入，再加總
private func legacyFee(_ fills: [Int]) -> Int {
    fills.map(roundedFee).reduce(0, +)
}

/// 新實作的第一版：先加總，再算一次四捨五入（和舊實作不一樣）
private func newFeeV1(_ fills: [Int]) -> Int { roundedFee(fills.reduce(0, +)) }

/// 新實作修好之後：行為和舊實作一致
private func newFeeV2(_ fills: [Int]) -> Int {
    fills.map(roundedFee).reduce(0, +)
}

private let orders: [[Int]] = [
    [10_000],
    [4_500, 4_500, 4_500],
    [20_000, 30_000],
    [1_499, 1_499, 1_499],
    [250_000],
    [2_600, 2_600],
]

/// 演練流程（兩個版本共用）：makeCalculator(舊實作, 新實作,
/// 不一致時的記錄函式) 回傳「呼叫端實際使用的計算函式」
private func scenario(
    _ log: LabLog,
    makeCalculator: (
        @escaping FeeFunction, @escaping FeeFunction, @escaping OnMismatch
    ) -> FeeFunction
) {
    let legacy = orders.map(legacyFee)
    log("舊實作的手續費：\(legacy)")

    var recorded = 0
    let calculator = makeCalculator(legacyFee, newFeeV1) { _, _, _ in
        recorded += 1
    }
    let charged = orders.map(calculator)
    log("使用者被收的手續費：\(charged)")
    log("和舊實作不一樣的有 \(zip(charged, legacy).filter { $0 != $1 }.count) 筆")
    log("系統記錄到的不一致：\(recorded) 筆")

    // 修好新實作（V2）之後再比對一輪
    recorded = 0
    let calculatorAfterFix = makeCalculator(legacyFee, newFeeV2) { _, _, _ in
        recorded += 1
    }
    _ = orders.map(calculatorAfterFix)
    log("修正新實作之後再比對，系統記錄到的不一致：\(recorded) 筆")
}

// MARK: - Bug 版
private enum L01Bug {
    static func makeCalculator(
        legacy: @escaping FeeFunction, candidate: @escaping FeeFunction,
        onMismatch: @escaping OnMismatch
    ) -> FeeFunction {
        // TODO ①：big bang：直接把呼叫端換成新實作，回傳
        // candidate（沒有任何比對）
        return legacy
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeCalculator: makeCalculator)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func makeCalculator(
        legacy: @escaping FeeFunction, candidate: @escaping FeeFunction,
        onMismatch: @escaping OnMismatch
    ) -> FeeFunction {
        // TODO ②：回傳一個函式 { fills in … }：同時算 legacy(fills) 與
        // candidate(fills)（shadow run）；
        //         兩者不一樣就呼叫 onMismatch(fills, old, new) 記錄下來；
        // 最後回傳「舊實作」的結果，使用者看到的仍然是舊的
        return legacy
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeCalculator: makeCalculator)
    }
}

// MARK: - 演練設定
extension Advanced09Architecture {
    static let l01 = Lab(
        id: "L01",
        title: "整個換成新實作，手續費悄悄變了",
        question: "第 1 題：面對已經上線的舊專案，怎麼逐步演進，而不是整個重寫？",
        goal:
            "新舊並存的期間，使用者要繼續拿到舊實作的結果，同時比對新實作；不一致降到 0 才切換。 先寫出直接換掉的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced09-Architecture/L01_ShadowRun.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，比較「使用者被收的手續費」和舊實作的手續費，以及「系統記錄到的不一致」。",
            "想一想：新實作哪裡和舊的不一樣？如果上線之後才發現，要怎麼知道有多少人受影響？",
            "寫 TODO ②，選「修正版」執行：使用者仍是舊結果，系統記錄到 3 筆不一致；修正新實作之後再比對，是 0 筆。",
            "進階：真正的做法再加上 feature flag 控制切換、記錄不一致的細節（輸入、舊結果、新結果）上傳監控，並在 CI 用 characterization test 鎖住舊行為。",
        ],
        expectBug:
            "「使用者被收的手續費：[10, 14, 50, 4, 250, 5]」，「和舊實作不一樣的有 3 筆」，但「系統記錄到的不一致：0 筆」：沒有人發現。",
        expectFixed:
            "「使用者被收的手續費：[10, 15, 50, 3, 250, 6]」（和舊實作一致），「系統記錄到的不一致：3 筆」；修正新實作之後再比對是 0 筆。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("使用者被收的手續費") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains("使用者被收的手續費：[10, 14, 50, 4, 250, 5]") {
                return
                    "Bug 還沒重現：makeCalculator 直接回傳新實作 candidate，使用者被收的會是 [10, 14, 50, 4, 250, 5]"
            }
            if !lines.contains("系統記錄到的不一致：0 筆") { return "Bug 版不該有任何比對記錄" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("使用者被收的手續費") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains("使用者被收的手續費：[10, 15, 50, 3, 250, 6]") {
                return "使用者看到的應該仍是舊實作的結果：回傳 legacy(fills)"
            }
            if !lines.contains("系統記錄到的不一致：3 筆") {
                return "新舊不一致的 3 筆要記錄下來：legacy 和 candidate 都算，不一樣就呼叫 onMismatch"
            }
            if !lines.contains("修正新實作之後再比對，系統記錄到的不一致：0 筆") {
                return "新實作修好之後應該是 0 筆不一致"
            }
            return nil
        }
    )
}
