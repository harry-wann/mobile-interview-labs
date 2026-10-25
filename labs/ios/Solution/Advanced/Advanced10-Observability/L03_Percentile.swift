// L03：看板上平均延遲 311 ms 很健康，但每 100 個使用者就有 6 個等了 3 秒（平均值掩蓋長尾，要看 p95／p99）
// 對應複習站：Advanced10Observability 第 2 題「上線之後要監控哪些指標？用什麼工具？」（p95 與業務指標）
//
// 情境：新版本放量時，release gate 看「下單 API 的延遲」。演練有 200 筆下單請求的延遲：188 筆在 100 到 180 毫秒之間，
//       12 筆（6%）因為伺服器逾時重試，花了 3000 毫秒。gate 的門檻是 1000 毫秒，超過就暫停放量。
// Bug 版：看板顯示平均延遲。平均 311 ms，低於門檻，gate 通過；但 6% 的使用者下單時要乾等 3 秒。
// 修正版：看 p95（95th percentile：95% 的請求比這個數字快）。用 nearest-rank 法：排序後取第 ⌈0.95 × 筆數⌉ 筆。
//        p95 是 3000 ms，超過門檻，gate 不通過，暫停放量。
import Foundation
import LabKit

/// 200 筆延遲（毫秒）：188 筆正常、12 筆 3000
private let latencies: [Int] = (0..<188).map { 100 + ($0 % 9) * 10 } + Array(repeating: 3_000, count: 12)
private let gateThresholdMs = 1_000

/// 演練流程（兩個版本共用）：dashboard 回傳看板顯示的指標名稱與數值（毫秒）
private func scenario(_ log: LabLog, dashboard: ([Int]) -> (name: String, value: Int)) {
    let slow = latencies.filter { $0 >= 3_000 }.count
    log("實際情況：\(latencies.count) 筆請求裡有 \(slow) 筆（\(slow * 100 / latencies.count)%）等了 3000 ms")
    let (name, value) = dashboard(latencies)
    log("看板顯示：\(name) \(value) ms")
    log("release gate（門檻 \(gateThresholdMs) ms）：\(value < gateThresholdMs ? "通過，繼續放量" : "不通過，暫停放量")")
}

// MARK: - Bug 版
private enum L03Bug {
    static func dashboard(_ latencies: [Int]) -> (name: String, value: Int) {
        ("平均", latencies.reduce(0, +) / latencies.count)      // 平均值
    }

    static func run(_ log: LabLog) async {
        scenario(log, dashboard: dashboard)
    }
}

// MARK: - 修正版
private enum L03Fixed {
    /// nearest-rank：排序後取第 ⌈p% × 筆數⌉ 筆（從 1 開始算）
    static func percentile(_ values: [Int], _ p: Int) -> Int {
        let sorted = values.sorted()
        let rank = Int((Double(p) / 100 * Double(sorted.count)).rounded(.up))
        return sorted[max(rank, 1) - 1]
    }

    static func dashboard(_ latencies: [Int]) -> (name: String, value: Int) {
        ("p95", percentile(latencies, 95))
    }

    static func run(_ log: LabLog) async {
        scenario(log, dashboard: dashboard)
    }
}

// MARK: - 演練設定
extension Advanced10Observability {
    static let l03 = Lab(
        id: "L03",
        title: "平均 311 ms 很健康，卻有 6% 的人等了 3 秒",
        question: "第 2 題：上線之後要監控哪些指標？用什麼工具？（p95 與長尾）",
        goal: "看延遲要看百分位數（p95、p99），不是平均值：平均會把少數很慢的請求稀釋掉。",
        steps: [
            "選「Bug 版」執行，看看板顯示的平均值，以及 release gate 的結果。",
            "想一想：6% 的使用者下單要等 3 秒，這個版本該繼續放量嗎？平均值為什麼看不出來？",
            "選「修正版」執行：p95 是多少？gate 的結果呢？",
            "進階：p50 看一般人的感受，p95、p99 看最慢的那群人；延遲的分布通常不是常態分布，平均值很少有代表性。",
        ],
        expectBug: "「看板顯示：平均 311 ms」，「release gate（門檻 1000 ms）：通過，繼續放量」。",
        expectFixed: "「看板顯示：p95 3000 ms」，「release gate（門檻 1000 ms）：不通過，暫停放量」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("看板顯示") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("看板顯示：平均 311 ms") { return "Bug 還沒重現：看板顯示平均延遲（總和除以筆數），應該是 311 ms" }
            if !lines.contains("release gate（門檻 1000 ms）：通過，繼續放量") { return "Bug 版的 gate 應該通過（平均值低於門檻）" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("看板顯示") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("看板顯示：p95 3000 ms") { return "看板應該顯示 p95 = 3000 ms：排序後取第 ⌈0.95 × 200⌉ = 190 筆" }
            if !lines.contains("release gate（門檻 1000 ms）：不通過，暫停放量") { return "p95 超過門檻，gate 應該不通過" }
            return nil
        }
    )
}
