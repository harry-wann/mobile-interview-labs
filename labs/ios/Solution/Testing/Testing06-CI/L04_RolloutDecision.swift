// L04：新版才放給 1% 的使用者就被暫停，
// 舊版本身有問題時又一直暫停（分階段發布的判斷太粗糙）
// 對應複習站：Testing06CI 第 4 題「發布的時候，
// 怎麼避免把有問題的版本推給所有使用者？」
//
// 情境：分階段發布時，用 crash-free session 比例（沒有 crash 的 session 佔幾
// %）決定下一步：擴大發布、再等一等、暫停發布。
//       decide(newVersion, baseline) 拿新版和舊版（baseline）的統計來判斷。
// 演練用四種情況：
//       ① 1% 階段：只有 40 個 session，其中 1 個 crash，crash-free 97.5%，舊版
// 99.4%。資料太少，還不能判斷。
//       ② 10% 階段：5,000 個 session，crash-free 99.5%，舊版 99.4%。正常，
// 該擴大。
//       ③ 25% 階段：12,000 個 session，crash-free 99.0%，舊版 99.4%。明顯變差，
// 該暫停。
//       ④ 舊版本身就只有 98.8%，新版 98.9%，10,000 個 session。新版沒有比較差，
// 該擴大。
// Bug 版：新版 crash-free 低於固定門檻 99.5% 就暫停，否則擴大。①
// 因為資料太少就暫停（誤判），④ 因為舊版本來就低於 99.5% 一直暫停。
// 修正版：session 不到 1,000 個先「再等一等」；夠多了就和舊版比，比舊版低超過
// 0.3 個百分點才暫停，否則擴大。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：四種情況各判斷一次
private func scenario(
    _ log: LabLog, decide: (T06Stats, T06Stats) -> T06Decision
) {
    let cases: [(String, T06Stats, T06Stats)] = [
        (
            "① 1% 階段，40 個 session（crash-free 97.5%）",
            T06Stats(sessions: 40, crashFreeSessions: 39),
            T06Stats(sessions: 100_000, crashFreeSessions: 99_400)
        ),
        (
            "② 10% 階段，5,000 個 session（crash-free 99.5%）",
            T06Stats(sessions: 5_000, crashFreeSessions: 4_975),
            T06Stats(sessions: 100_000, crashFreeSessions: 99_400)
        ),
        (
            "③ 25% 階段，12,000 個 session（crash-free 99.0%）",
            T06Stats(sessions: 12_000, crashFreeSessions: 11_880),
            T06Stats(sessions: 100_000, crashFreeSessions: 99_400)
        ),
        (
            "④ 舊版只有 98.8%，新版 98.9%，10,000 個 session",
            T06Stats(sessions: 10_000, crashFreeSessions: 9_890),
            T06Stats(sessions: 100_000, crashFreeSessions: 98_800)
        ),
    ]
    for (title, new, baseline) in cases {
        log("\(title)：決定\(decide(new, baseline).rawValue)")
    }
}

// MARK: - Bug 版
private enum L04Bug {
    static func decide(
        _ newVersion: T06Stats, _ baseline: T06Stats
    ) -> T06Decision {
        // 固定門檻 99.5%，不看資料夠不夠多，也不看舊版
        newVersion.crashFreeRate < 99.5 ? .halt : .expand
    }

    static func run(_ log: LabLog) async {
        scenario(log, decide: decide)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func decide(
        _ newVersion: T06Stats, _ baseline: T06Stats
    ) -> T06Decision {
        // 資料太少，先等
        guard newVersion.sessions >= 1_000 else { return .hold }
        // 比舊版差超過 0.3 個百分點才暫停
        return newVersion.crashFreeRate < baseline.crashFreeRate - 0.3
            ? .halt : .expand
    }

    static func run(_ log: LabLog) async {
        scenario(log, decide: decide)
    }
}

// MARK: - 演練設定
extension Testing06CI {
    static let l04 = Lab(
        id: "L04",
        title: "新版才放 1% 就被暫停，舊版有問題時一直暫停",
        question: "第 4 題：發布的時候，怎麼避免把有問題的版本推給所有使用者？",
        goal: "用 crash-free 比例決定要不要繼續擴大發布時，要看資料夠不夠多，並且和舊版比較，不是只看一個固定門檻。",
        steps: [
            "選「Bug 版」執行，看四種情況各自的決定。① 和 ④ 的決定合理嗎？",
            "想一想：1% 階段只有 40 個 session，1 個 crash 就是 2.5% 的 crash 率，這個數字可信嗎？",
            "選「修正版」執行：① 再等一等、② 擴大、③ 暫停、④ 擴大。",
        ],
        expectBug: "① 暫停發布（資料太少，誤判）；② 擴大發布；③ 暫停發布；④ 暫停發布（新版沒有比舊版差，誤判）。",
        expectFixed: "① 再等一等；② 擴大發布；③ 暫停發布；④ 擴大發布。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard
                lines.contains(
                    "② 10% 階段，5,000 個 session（crash-free 99.5%）：決定擴大發布")
            else { return "② crash-free 99.5% 不低於門檻 99.5%，應該擴大" }
            guard
                lines.contains(
                    "③ 25% 階段，12,000 個 session（crash-free 99.0%）：決定暫停發布")
            else { return "③ crash-free 99.0% 低於門檻，應該暫停" }
            return lines.contains(
                "① 1% 階段，40 個 session（crash-free 97.5%）：決定暫停發布")
                && lines.contains(
                    "④ 舊版只有 98.8%，新版 98.9%，10,000 個 session：決定暫停發布")
                ? nil : "Bug 還沒重現：只看新版的 crash-free 有沒有低於 99.5%，① 和 ④ 都會被暫停"
        },
        checkFixed: { lines in
            guard
                lines.contains("① 1% 階段，40 個 session（crash-free 97.5%）：決定再等一等")
            else { return "① 只有 40 個 session，資料太少（不到 1,000 個），應該再等一等" }
            guard
                lines.contains(
                    "② 10% 階段，5,000 個 session（crash-free 99.5%）：決定擴大發布")
            else { return "② 新版 99.5% 比舊版 99.4% 好，應該擴大" }
            guard
                lines.contains(
                    "③ 25% 階段，12,000 個 session（crash-free 99.0%）：決定暫停發布")
            else { return "③ 新版 99.0% 比舊版 99.4% 低 0.4 個百分點，超過 0.3，應該暫停" }
            return lines.contains(
                "④ 舊版只有 98.8%，新版 98.9%，10,000 個 session：決定擴大發布")
                ? nil : "④ 新版 98.9% 比舊版 98.8% 好，應該擴大"
        }
    )
}
