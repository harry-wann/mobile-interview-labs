// L04：整個 ViewModel 標了 @MainActor，解析 K 線時畫面卡住（重運算放在主 actor 上）
// 對應複習站：Swift05Actors 第 3 題「@MainActor 與 global actor 是什麼？該怎麼用？」
//
// 情境：K 線圖的 ViewModel 為了更新畫面狀態，整個標了 @MainActor。載入時要解析 40 萬行 K 線資料（重運算）。
//
// 你要寫：
//   ① Bug 版：@MainActor 的 ChartViewModel.load 同步呼叫 sumCloses(lines)，把結果存進 total。
//   ② 修正版：寫一個 @concurrent 的 parse(_:) async 函式呼叫 sumCloses；ChartViewModel.load 改成 async，await parse 之後把結果存進 total。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift05-Actors/L04_MainActorStall.swift
import Foundation
import LabKit

/// 40 萬行收盤價資料（兩個版本共用）
private let candleLines = (0..<400_000).map { "15000\($0 % 1_000).25" }

/// 解析並加總收盤價：重運算（兩個版本共用）
private func sumCloses(_ lines: [String]) -> Double {
    var total: Decimal = 0
    for line in lines { total += Decimal(string: line) ?? 0 }
    return NSDecimalNumber(decimal: total).doubleValue
}

// MARK: - Bug 版
private enum L04Bug {
    @MainActor
    final class ChartViewModel {
        private(set) var total = 0.0

        func load(_ lines: [String]) {
            // TODO ①：total = sumCloses(lines)（直接在 @MainActor 的方法裡算）
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        let meter = Swift05Actors.StallMeter()
        meter.start()
        let viewModel = ChartViewModel()
        viewModel.load(candleLines)
        let stall = meter.stop()
        log("解析完成，合計 \(Int(viewModel.total))")
        log("主執行緒最長被卡住 \(Int(stall)) 毫秒")
    }
}

// MARK: - 修正版
private enum L04Fixed {
    /// @concurrent：明確離開呼叫者的 actor，在背景執行緒池執行
    // TODO ②-1：在 static func parse 前面加 @concurrent，讓它離開呼叫者的 actor；函式內容回傳 sumCloses(lines)
    static func parse(_ lines: [String]) async -> Double {
        0
    }

    @MainActor
    final class ChartViewModel {
        private(set) var total = 0.0

        func load(_ lines: [String]) async {
            // TODO ②-2：total = await L04Fixed.parse(lines)（重運算在背景；算完回到主 actor 更新狀態）
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        let meter = Swift05Actors.StallMeter()
        meter.start()
        let viewModel = ChartViewModel()
        await viewModel.load(candleLines)
        let stall = meter.stop()
        log("解析完成，合計 \(Int(viewModel.total))")
        log("主執行緒最長被卡住 \(Int(stall)) 毫秒")
    }
}

// MARK: - 演練設定
private func stallMs(_ lines: [String]) -> Int? {
    lines.last.flatMap { line in
        let prefix = "主執行緒最長被卡住 "
        return line.hasPrefix(prefix) ? Int(line.dropFirst(prefix.count).dropLast(" 毫秒".count)) : nil
    }
}

extension Swift05Actors {
    static let l04 = Lab(
        id: "L04",
        title: "載入 K 線時畫面卡住",
        question: "第 3 題：@MainActor 與 global actor 是什麼？該怎麼用？",
        goal: "ViewModel 標了 @MainActor，載入時要解析 40 萬行資料。解析不能佔住主執行緒。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：主執行緒最長被卡住幾毫秒，出現 ✓。執行的時候試著捲動 log：畫面會卡住。",
            "寫 TODO ②，選「修正版」執行：主執行緒只被卡住一點點，合計中間價一樣。",
            "想一想：@MainActor 的 ViewModel 裡，哪一類工作該留在主 actor、哪一類該移出去？",
            "進階：打開 Xcode 的 FPS 計（Debug navigator › FPS）或 Instruments 的 Time Profiler，看 Bug 版主執行緒那一段被佔滿。",
        ],
        expectBug: "解析完成，合計相同；主執行緒最長被卡住幾百毫秒（Debug 建置本機模擬器實測約 430 毫秒）。",
        expectFixed: "解析完成，合計相同；主執行緒最長只被卡住幾十毫秒以內（實測約 10 毫秒）。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("解析完成，合計 ") && !$0.hasSuffix("合計 0") }) else { return "解析完成後，ViewModel 的 total 要是 sumCloses 的結果" }
            guard let ms = stallMs(lines) else { return "找不到主執行緒被卡住的毫秒數" }
            return ms >= 150 ? nil : "主執行緒只被卡住 \(ms) 毫秒：Bug 還沒重現（解析要直接寫在 @MainActor 的 load 裡）"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("解析完成，合計 ") && !$0.hasSuffix("合計 0") }) else { return "解析完成後，ViewModel 的 total 要是 sumCloses 的結果" }
            guard let ms = stallMs(lines) else { return "找不到主執行緒被卡住的毫秒數" }
            return ms < 100 ? nil : "主執行緒被卡住 \(ms) 毫秒，還是太久：重運算要放進 @concurrent 的函式"
        }
    )
}
