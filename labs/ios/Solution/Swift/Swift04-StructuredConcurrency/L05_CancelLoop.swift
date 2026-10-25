// L05：使用者按了取消，解析價格的迴圈卻跑完全部（自己寫的迴圈沒有檢查取消）
// 對應複習站：Swift04StructuredConcurrency 第 4 題「Task 的取消是怎麼運作的？」
//
// 情境：匯入 20 萬行歷史價格的 CSV，逐行解析。解析到一千行左右，
// 使用者按下「取消」。
// Bug 版：迴圈裡只有一般的計算，沒有任何 await，也沒有檢查取消。cancel()
// 只是舉旗子，沒人去看，20 萬行全部解析完才停。
// 修正版：每一圈呼叫 try Task.checkCancellation()。旗子舉起來就丟出
// CancellationError，迴圈馬上結束。
import Foundation
import LabKit

private let csvLines = (0..<200_000).map { "15000\($0 % 1_000).25" }

/// 在背景任務跑到一千行時按下取消，等任務結束，回傳 log
/// 要印的內容（兩個版本共用）
private func cancelWhileParsing(
    _ progress: Swift04StructuredConcurrency.Counter,
    parse: @escaping @Sendable () async -> Void
) async {
    let worker = Task { await parse() }
    // 模擬使用者：看到進度到一千行就按取消
    let user = Task {
        while progress.count < 1_000 {
            try? await Task.sleep(for: .milliseconds(1))
        }
        worker.cancel()
    }
    await worker.value
    user.cancel()
}

// MARK: - Bug 版
private enum L05Bug {
    /// 逐行解析；沒有檢查取消
    static func parsePrices(
        _ lines: [String], progress: Swift04StructuredConcurrency.Counter
    ) async -> [Decimal] {
        var result: [Decimal] = []
        for line in lines {
            result.append(Decimal(string: line) ?? 0)
            progress.hit()
        }
        return result
    }

    static func run(_ log: LabLog) async {
        let progress = Swift04StructuredConcurrency.Counter()
        await cancelWhileParsing(progress) {
            _ = await parsePrices(csvLines, progress: progress)
        }
        log("取消後，已解析 \(progress.count) / \(csvLines.count) 行")
    }
}

// MARK: - 修正版
private enum L05Fixed {
    /// 逐行解析；每一圈檢查一次取消
    static func parsePrices(
        _ lines: [String], progress: Swift04StructuredConcurrency.Counter
    ) async throws -> [Decimal] {
        var result: [Decimal] = []
        for line in lines {
            // 已被取消就丟出 CancellationError
            try Task.checkCancellation()
            result.append(Decimal(string: line) ?? 0)
            progress.hit()
        }
        return result
    }

    static func run(_ log: LabLog) async {
        let progress = Swift04StructuredConcurrency.Counter()
        await cancelWhileParsing(progress) {
            _ = try? await parsePrices(csvLines, progress: progress)
        }
        log("取消後，已解析 \(progress.count) / \(csvLines.count) 行")
    }
}

// MARK: - 演練設定
private func parsedCount(_ lines: [String]) -> Int? {
    lines.last.flatMap { line in
        let prefix = "取消後，已解析 "
        guard line.hasPrefix(prefix) else { return nil }
        return Int(line.dropFirst(prefix.count).prefix { $0.isNumber })
    }
}

extension Swift04StructuredConcurrency {
    static let l05 = Lab(
        id: "L05",
        title: "按了取消，迴圈還是跑完全部",
        question: "第 4 題：Task 的取消是怎麼運作的？",
        goal: "解析 20 萬行價格，使用者在解析到一千行左右按下取消，迴圈要很快停下來。",
        steps: [
            "選「Bug 版」執行，看最後一行：取消後，已解析幾行。",
            "想一想：cancel() 做了什麼？這個迴圈裡有誰會去看旗子？",
            "選「修正版」執行，已解析的行數應該遠少於 20 萬。",
        ],
        expectBug: "取消後，已解析 200000 / 200000 行：整個迴圈跑完。",
        expectFixed: "取消後，已解析幾千行（遠少於 200000）就停了。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = parsedCount(lines) else { return "找不到解析行數" }
            guard n >= 1_000 else { return "迴圈要逐行解析，每解析一行呼叫 progress.hit()" }
            return n == 200_000 ? nil : "Bug 還沒重現：迴圈只解析了 \(n) 行就停了（不要在迴圈裡檢查取消）"
        },
        checkFixed: { lines in
            guard let n = parsedCount(lines) else { return "找不到解析行數" }
            guard n >= 1_000 else { return "迴圈要逐行解析，每解析一行呼叫 progress.hit()" }
            return n < 100_000
                ? nil : "取消後還是解析了 \(n) 行：每一圈要呼叫 try Task.checkCancellation()"
        }
    )
}
