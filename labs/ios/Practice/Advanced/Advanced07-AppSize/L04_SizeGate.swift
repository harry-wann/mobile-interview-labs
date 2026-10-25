// L04：每一版只大了一點點，兩年後 App 卻大了 12 MB（CI 的體積檢查要跟基準比）
// 對應複習站：Advanced07AppSize 第 6
// 題「縮小體積有什麼風險？怎麼確認沒有弄壞東西？」（把體積檢查加進 CI）
//
// 情境：團隊在 CI 加了體積檢查，避免 App 悄悄變大。基準版本是 100.0 MB；
// 之後連續 12 個 build，每個都只比上一個大 0.6 MB（新功能、新圖片的正常成長），
//       第 13 個 build 一次大了 4.5 MB（有人不小心把原圖放進去）。
// 團隊的預算是：整體不超過基準 + 3 MB，單一 build 不超過上一個 + 1 MB。
//
// 你要寫：
//   ① Bug 版：只檢查這個 build 比上一個大多少（超過 1 MB 才擋下）。每次只大 0.
// 6 MB，一路放行。
//   ② 修正版：除了單一 build 的增幅，還要檢查比基準版本大多少（整體預算 3
// MB）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced07-AppSize/L04_SizeGate.swift
import Foundation
import LabKit

private let baselineKB = 100_000
private let perBuildLimitKB = 1_000
private let budgetKB = 3_000

/// 13 個 build 的大小（KB）：前 12 個每次 +600 KB，第 13 個 +4500
/// KB（兩個版本共用）
private let builds: [Int] = {
    var sizes: [Int] = []
    var size = baselineKB
    for _ in 1...12 { size += 600; sizes.append(size) }
    sizes.append(size + 4_500)
    return sizes
}()

/// 演練流程（兩個版本共用）：passes(這個 build 的大小, 上一個 build 的大小)
/// 回傳這個 build 能不能通過檢查
private func scenario(
    _ log: LabLog, passes: (_ size: Int, _ previous: Int) -> Bool
) {
    var blocked: [Int] = []
    var previous = baselineKB
    for (index, size) in builds.enumerated() {
        if !passes(size, previous) { blocked.append(index + 1) }
        // 擋下的 build 在演練裡仍然當作「已經存在」，只是被標記
        previous = size
    }
    log(
        "共 \(builds.count) 個 build，被擋下 \(blocked.count) 個：\(blocked.map { "#\($0)" }.joined(separator: "、"))"
    )
    log(
        "最後一個 build 比基準版本大 \(String(format: "%.1f", Double(builds.last! - baselineKB) / 1_000)) MB"
    )
}

// MARK: - Bug 版
private enum L04Bug {
    static func passes(size: Int, previous: Int) -> Bool {
        // TODO ①：只檢查「這個 build 比上一個大多少」：size - previous 不超過
        // perBuildLimitKB 才通過
        return true
    }

    static func run(_ log: LabLog) async {
        scenario(log) { passes(size: $0, previous: $1) }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func passes(size: Int, previous: Int) -> Bool {
        // TODO ②：除了單一 build 的增幅（size - previous 不超過
        // perBuildLimitKB），
        //         還要檢查整體預算：size - baselineKB 不超過 budgetKB。
        // 兩個都成立才通過
        return true
    }

    static func run(_ log: LabLog) async {
        scenario(log) { passes(size: $0, previous: $1) }
    }
}

// MARK: - 演練設定
extension Advanced07AppSize {
    static let l04 = Lab(
        id: "L04",
        title: "每版只大一點點，兩年後大了 12 MB",
        question: "第 6 題：縮小體積有什麼風險？怎麼確認沒有弄壞東西？（把體積檢查加進 CI）",
        goal:
            "體積檢查不能只比上一個 build：要有基準版本與整體預算，才擋得住「一次只大一點」的慢性成長。 先寫出只跟上一個 build 比的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced07-AppSize/L04_SizeGate.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看被擋下幾個 build，以及最後一個 build 比基準大多少。",
            "想一想：12 個 build 每個都只大 0.6 MB，為什麼檢查完全沒發現？",
            "寫 TODO ②，選「修正版」執行：加上「比基準版本大多少」的預算，看從第幾個 build 開始被擋下。",
            "進階：真正的檢查可以放在 CI 裡，iOS 讀 App Thinning Size Report，Android 用 bundletool get-size total；基準放在版本控制裡，要調整就留下紀錄。",
        ],
        expectBug: "「共 13 個 build，被擋下 1 個：#13」，最後一個 build 比基準版本大 11.7 MB。",
        expectFixed:
            "「共 13 個 build，被擋下 8 個：#6、#7、#8、#9、#10、#11、#12、#13」：從累積超過預算（3.6 MB）的第 6 個 build 開始擋下。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("共 13 個 build") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains("共 13 個 build，被擋下 1 個：#13") {
                return "Bug 還沒重現：只比上一個 build 的增幅（> 1 MB 才擋），應該只擋下 #13"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("共 13 個 build") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains(
                "共 13 個 build，被擋下 8 個：#6、#7、#8、#9、#10、#11、#12、#13")
            {
                return
                    "應該從 #6 開始擋下（比基準大 3.6 MB，超過 3 MB 預算）：除了單一 build 的增幅，還要檢查 size - baselineKB 不超過 budgetKB"
            }
            return nil
        }
    )
}
