// L04：100 萬筆報價每一筆都多配置一塊記憶體（熱點迴圈用 [any]，要先查表、也不能 inline）
// 對應複習站：Swift03Generics 第 4 題「泛型什麼時候會變慢？」（被問到的對照：什麼時候該用 any、什麼時候不該）
//
// 情境：行情推播解析出 100 萬筆報價（Quote 有五個 Double 欄位，共 40 bytes），要算每一筆的中間價並加總。
//
// 你要寫：
//   ① Bug 版：totalMid 的參數改成 [any Priced]；run 裡把 makeQuotes 的結果標註成 [any Priced]。
//   ② 修正版：totalMid 寫成泛型函式 <Q: Priced>，參數是 [Q]，算出所有 mid 的總和。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift03-Generics/L04_ExistentialBox.swift
//
// 時間差距和建置設定有關（Debug 沒有最佳化，看不出差距，甚至相反），所以本演練用「heap 配置次數」判斷，兩種建置設定結果都一樣。
import Foundation
import LabKit

/// 有中間價的報價（兩個版本共用）
private protocol Priced {
    var mid: Double { get }
}

private struct Quote: Priced {
    var bid: Double
    var ask: Double
    var bidSize: Double
    var askSize: Double
    var timestamp: Double

    var mid: Double { (bid + ask) / 2 }
}

private func makeQuotes(_ count: Int) -> [Quote] {
    (0..<count).map { i in
        let base = 15_000_000 + Double(i % 1_000)
        return Quote(bid: base, ask: base + 1_000, bidSize: 0.5, askSize: 0.5, timestamp: Double(i))
    }
}

/// 目前 heap 裡使用中的記憶體區塊數（兩個版本共用）
private func heapBlocks() -> Int {
    var stats = malloc_statistics_t()
    malloc_zone_statistics(nil, &stats)
    return Int(stats.blocks_in_use)
}

// MARK: - Bug 版
private enum L04Bug {
    /// 參數是 [any Priced]：每個元素都是 existential，讀取 mid 要查表
    // TODO ①-1：把參數型別改成 [any Priced]，其餘不變（加總每一筆的 mid）
    static func totalMid(_ quotes: [Quote]) -> Double {
        var sum = 0.0
        for quote in quotes { sum += quote.mid }
        return sum
    }

    static func run(_ log: LabLog) async {
        let (blocks, total) = await Task.detached { () -> (Int, Double) in
            let before = heapBlocks()
            // TODO ①-2：把 quotes 的型別標註成 [any Priced]（每筆報價包進 existential 容器）
            let quotes = makeQuotes(1_000_000)
            let blocks = heapBlocks() - before
            return (blocks, totalMid(quotes))
        }.value
        log("合計中間價：\(total)")
        log(String(format: "每筆報價平均多配置 %.2f 個 heap 區塊", Double(max(blocks, 0)) / 1_000_000))
    }
}

// MARK: - 修正版
private enum L04Fixed {
    /// 泛型：用 [Quote] 呼叫時，編譯器可以產生專用版本（specialization），直接讀欄位
    // TODO ②：改成泛型函式 totalMid<Q: Priced>，參數是 [Q]，加總每一筆的 mid
    static func totalMid(_ quotes: [Quote]) -> Double {
        return 0
    }

    static func run(_ log: LabLog) async {
        let (blocks, total) = await Task.detached { () -> (Int, Double) in
            let before = heapBlocks()
            let quotes = makeQuotes(1_000_000)                    // 具體型別的陣列：報價直接放在陣列裡
            let blocks = heapBlocks() - before
            return (blocks, totalMid(quotes))
        }.value
        log("合計中間價：\(total)")
        log(String(format: "每筆報價平均多配置 %.2f 個 heap 區塊", Double(max(blocks, 0)) / 1_000_000))
    }
}

// MARK: - 演練設定
private func blocksPerQuote(_ lines: [String]) -> Double? {
    lines.last.flatMap { line in
        let prefix = "每筆報價平均多配置 "
        return line.hasPrefix(prefix) ? Double(line.dropFirst(prefix.count).dropLast(" 個 heap 區塊".count)) : nil
    }
}

extension Swift03Generics {
    static let l04 = Lab(
        id: "L04",
        title: "100 萬筆報價，每筆多配置一塊記憶體",
        question: "第 4 題：泛型什麼時候會變慢？（比較少考，被問到能答就好）",
        goal: "同樣是算 100 萬筆報價的中間價，比較 [any Priced] 和「泛型函式加具體型別的陣列」。看每筆報價要不要多配置一塊 heap 記憶體。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：每筆報價平均多配置幾個 heap 區塊，出現 ✓。",
            "寫 TODO ②，選「修正版」執行，比較這個數字（合計中間價兩個版本要一樣）。",
            "進階：用 Instruments 的 Allocations 錄 Bug 版，會看到 100 萬個約 48 bytes 的配置；錄 Time Profiler 並把 Build Configuration 改成 Release，會看到時間花在 witness table 查表與 swift_retain／swift_release。",
            "注意：Debug 建置沒有最佳化，量毫秒看不出差距，所以這個演練不比時間。",
        ],
        expectBug: "合計中間價相同；每筆報價平均多配置 1.00 個 heap 區塊（100 萬個）。",
        expectFixed: "合計中間價相同；每筆報價平均多配置 0.00 個 heap 區塊。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("合計中間價：15000999500000.0") else { return "合計中間價應該是 15000999500000.0：totalMid 要把每筆報價的 mid 加起來" }
            guard let blocks = blocksPerQuote(lines) else { return "找不到 heap 區塊數" }
            return blocks >= 0.9 ? nil : String(format: "每筆只多配置 %.2f 個區塊：Bug 還沒重現（陣列要是 [any Priced]）", blocks)
        },
        checkFixed: { lines in
            guard lines.contains("合計中間價：15000999500000.0") else { return "合計中間價應該是 15000999500000.0：totalMid 要把每筆報價的 mid 加起來" }
            guard let blocks = blocksPerQuote(lines) else { return "找不到 heap 區塊數" }
            return blocks < 0.1 ? nil : String(format: "每筆還是多配置 %.2f 個區塊：陣列要是具體型別 [Quote]", blocks)
        }
    )
}
