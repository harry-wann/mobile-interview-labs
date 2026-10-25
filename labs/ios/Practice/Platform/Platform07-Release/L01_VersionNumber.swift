// L01：發 3.10.0 時，商店說這個版本號比以前上傳的小（用字串拼出來的 build 號）
// 對應複習站：Platform07Release 第 3 題「測試版怎麼發給測試人員？TestFlight 和
// Google Play 的測試軌差在哪？」（版本號要遞增）
//
// 情境：CI 腳本要自動從版本名稱算出 build 號（iOS 的 build 號，Android 的
// versionCode），免得人工忘記遞增。
//       商店規定：新上傳的 build 號必須比以前上傳過的都大，否則拒絕。
// 演練依序發布 7 個版本：
//       3.2.9、3.2.10、3.9.0、3.9.10、3.10.0、3.20.0、3.21.0，
// 看有幾個版本被商店拒絕。
//
// 你要寫：
//   ① Bug 版：buildNumber 把版本名稱的「.」拿掉，直接轉成整數（3.2.10 →
// 3210）。
//   ② 修正版：用「.」切成三段整數（每段要小於 1000），回傳 主版號 × 1_000_000
// ＋ 次版號 × 1_000 ＋ 修訂號。
// 只改有 TODO 的地方。解答：
// Solution/Platform/Platform07-Release/L01_VersionNumber.swift
import Foundation
import LabKit

/// 要依序發布的版本（兩個版本共用）
private let releases = [
    "3.2.9", "3.2.10", "3.9.0", "3.9.10", "3.10.0", "3.20.0", "3.21.0",
]

/// 演練流程（兩個版本共用）：依序「上傳」，build 號要比之前上傳過的最大值還大
private func scenario(_ log: LabLog, buildNumber: (String) -> Int) {
    var highest = 0
    var rejected = 0
    for version in releases {
        let build = buildNumber(version)
        if build > highest {
            highest = build
            log("✓ \(version) → build 號 \(build)")
        } else {
            rejected += 1
            log("✗ \(version) → build 號 \(build)，不比之前上傳過的最大值 \(highest) 大，商店拒絕")
        }
    }
    log("共 \(releases.count) 個版本，被商店拒絕 \(rejected) 個")
}

// MARK: - Bug 版
private enum L01Bug {
    static func buildNumber(for version: String) -> Int {
        // TODO ①：把 version 裡的 "." 拿掉（replacingOccurrences），直接轉成
        // Int（轉不成就回傳 0）
        0
    }

    static func run(_ log: LabLog) async {
        scenario(log, buildNumber: buildNumber(for:))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func buildNumber(for version: String) -> Int {
        // TODO ②：用 "." 切成三段整數 parts（不是三段、或有一段 >= 1000 就回傳
        // 0），
        //        回傳 parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        0
    }

    static func run(_ log: LabLog) async {
        scenario(log, buildNumber: buildNumber(for:))
    }
}

// MARK: - 演練設定
private func rejectedCount(_ lines: [String]) -> Int? {
    guard
        let line = lines.first(where: {
            $0.hasPrefix("共 ") && $0.contains("被商店拒絕")
        })
    else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Platform07Release {
    static let l01 = Lab(
        id: "L01",
        title: "發 3.10.0 時，商店說版本號比以前的小",
        question: "第 3 題：測試版怎麼發給測試人員？TestFlight 和 Google Play 的測試軌差在哪？（版本號要遞增）",
        goal: "自動從版本名稱算出 build 號，而且每個新版本的 build 號一定比以前所有版本都大。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看哪些版本被商店拒絕、build 號是多少，出現 ✓。",
            "想一想：3.9.0 和 3.21.0 去掉點之後各是幾？數字大小跟版本新舊一致嗎？",
            "寫 TODO ②，選「修正版」執行，7 個版本的 build 號都要遞增。",
            "進階：Android 的 versionCode 上限是 2,100,000,000；修正版把每一段限制在 3 位數，主版號最多到 2099。",
        ],
        expectBug:
            "「3.9.0 → build 號 390」「3.10.0 → build 號 3100」「3.20.0 → build 號 3200」「3.21.0 → build 號 3210」被商店拒絕，「共 7 個版本，被商店拒絕 4 個」。",
        expectFixed:
            "build 號依序是 3002009、3002010、3009000、3009010、3010000、3020000、3021000，「共 7 個版本，被商店拒絕 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = rejectedCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, buildNumber:) 執行"
            }
            if !lines.contains("✓ 3.2.10 → build 號 3210") {
                return "Bug 版把版本名稱的點拿掉當整數：3.2.10 要變成 3210"
            }
            return n >= 3
                ? nil
                : "Bug 還沒重現：把點拿掉直接轉成整數，3.9.0、3.10.0 等版本的 build 號會比以前的小（目前只有 \(n) 個被拒絕）"
        },
        checkFixed: { lines in
            guard let n = rejectedCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, buildNumber:) 執行"
            }
            if n == 0 { return nil }
            return
                "還有 \(n) 個版本被拒絕：每一段固定 3 位數：主版號 × 1_000_000 ＋ 次版號 × 1_000 ＋ 修訂號"
        }
    )
}
