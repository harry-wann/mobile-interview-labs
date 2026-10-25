import Foundation

/// 自動檢查（不用點畫面）。指令列範例：
/// ```
/// xcrun simctl launch --console-pty <模擬器> tw.harry.MobileLabs -selftest
/// Swift01
/// ```
enum LabSelfTest {
    @MainActor
    static func runIfRequested(
        practice: [LabSection], solution: [LabSection]
    ) async {
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "-run"), i + 4 < args.count {
            await single(
                code: args[i + 1], labID: args[i + 2], source: args[i + 3],
                variant: args[i + 4],
                practice: practice, solution: solution)
        } else if args.contains("-selftest") {
            await run(
                solution, label: "解答",
                filter: LabLaunch.value(after: "-selftest"), strict: true)
        } else if args.contains("-selftest-practice") {
            await run(
                practice, label: "練習",
                filter: LabLaunch.value(after: "-selftest-practice"),
                strict: false)
        }
    }

    /// strict：解答模式，Bug 版要重現問題、修正版要正確，有任何不符就 exit 1。
    /// 練習模式只確認骨架能跑完、不會當掉（還沒寫的演練顯示 FAIL 是正常的）。
    @MainActor
    private static func run(
        _ sections: [LabSection], label: String, filter: String?, strict: Bool
    ) async {
        var pass = 0, fail = 0, skip = 0
        print("[SELFTEST] 開始：\(label)\(filter.map { "（\($0)）" } ?? "")")
        for section in sections {
            for unit in section.units where filter == nil || unit.code == filter
            {
                for lab in unit.labs {
                    let name = "\(unit.code) \(lab.id)"
                    guard let run = lab.run else {
                        print("[SELFTEST] \(name) SKIP（只有畫面，需要手動操作）")
                        skip += 1
                        continue
                    }
                    for variant in LabVariant.allCases {
                        guard let check = lab.check(variant) else {
                            print(
                                "[SELFTEST] \(name) \(variant.title) SKIP（沒有自動檢查）"
                            )
                            skip += 1
                            continue
                        }
                        if variant == .bug && lab.bugCrashes {
                            print(
                                "[SELFTEST] \(name) \(variant.title) SKIP（會當掉，用 -run 確認）"
                            )
                            skip += 1
                            continue
                        }
                        let log = LabLog(echo: false)
                        await runWithTimeout(run, variant, log)
                        let lines = log.texts
                        if let reason = check(lines) {
                            fail += 1
                            print(
                                "[SELFTEST] \(name) \(variant.title) FAIL：\(reason)"
                            )
                            if strict {
                                print("[SELFTEST]   log：\(lines.suffix(8))")
                            }
                        } else {
                            pass += 1
                            print(
                                "[SELFTEST] \(name) \(variant.title) PASS　\(lines.last ?? "")"
                            )
                        }
                    }
                }
            }
        }
        print("[SELFTEST] DONE pass=\(pass) fail=\(fail) skip=\(skip)")
        fflush(stdout)
        exit(strict && fail > 0 ? 1 : 0)
    }

    @MainActor
    private static func single(
        code: String, labID: String, source: String, variant: String,
        practice: [LabSection], solution: [LabSection]
    ) async {
        let sections = source == "practice" ? practice : solution
        guard
            let lab = findUnit(code, in: sections)?.labs.first(where: {
                $0.id == labID
            }),
            let run = lab.run, let v = LabVariant(rawValue: variant)
        else {
            print("[SELFTEST] 找不到 \(code) \(labID) \(source) \(variant)")
            exit(2)
        }
        let log = LabLog(echo: true)
        await run(v, log)
        print("[SELFTEST] \(code) \(labID) \(source) \(variant) 跑完，沒有當掉")
        fflush(stdout)
        exit(0)
    }

    /// 每個版本最多跑 30 秒，避免卡住整個檢查
    @MainActor
    private static func runWithTimeout(
        _ run: @escaping (LabVariant, LabLog) async -> Void,
        _ variant: LabVariant,
        _ log: LabLog
    ) async {
        let work = Task { await run(variant, log) }
        let timer = Task {
            try? await Task.sleep(for: .seconds(30))
            if !Task.isCancelled {
                log("[SELFTEST] 逾時 30 秒")
                work.cancel()
            }
        }
        await work.value
        timer.cancel()
    }
}
