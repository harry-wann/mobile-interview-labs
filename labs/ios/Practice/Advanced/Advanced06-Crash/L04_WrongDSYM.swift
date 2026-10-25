// L04：crash 報告看得懂了，卻指到完全不相干的函式（dSYM 要用 UUID 對，
// 不是版本號）
// 對應複習站：Advanced06Crash 第 3 題「crash 報告為什麼一開始看不懂？dSYM 和
// mapping 檔是什麼？」
//
// 情境：App 3.1.0 先打包了 build 1，後來為了補一個修正又重新 archive 了 build
// 2，版本號還是 3.1.0。
//       兩次編譯的二進位檔 UUID 不同，函式的位址也不同（build 2 多了程式碼，
// 函式往後挪）。
//       上架的是 build 2，CI 卻只上傳了 build 1 的 dSYM。使用者的 crash 發生在
// build 2 的 ChartRenderer.draw()。
//
// 你要寫：
//   ① Bug 版：用版本號（version 一樣）找 dSYM，找到第一份就拿來還原。
// 位址對不上卻不會報錯，還原出一個錯的函式名稱。
//   ② 修正版：用 crash 報告裡的 Binary UUID 找 uuid 一樣的 dSYM。
// 找不到就回「未還原（缺少 UUID … 的 dSYM）」。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced06-Crash/L04_WrongDSYM.swift
import Foundation
import LabKit

/// 一份 dSYM：UUID、版本，以及「位址 → 函式名稱」的對照表
private struct DSYM {
    let uuid: String
    let version: String
    let symbols: [(offset: Int, name: String)]  // 依位址由小到大

    /// 位址落在哪個函式裡：找「起點 ≤ 位址」的最後一個
    func symbolicate(_ offset: Int) -> String {
        symbols.last { $0.offset <= offset }?.name ?? "（不認得的位址）"
    }
}

private struct CrashReport {
    let appVersion: String
    // crash 報告裡的 Binary UUID：這個 crash 是哪一次編譯的
    let binaryUUID: String
    let offset: Int
}

private let build1 = DSYM(
    uuid: "1A2B3C4D", version: "3.1.0",
    symbols: [
        (0x000, "AppDelegate.didFinishLaunching()"),
        (0x300, "OrderViewModel.submit()"),
        (0x600, "ChartRenderer.draw()"),
    ])
/// 重新編譯後，ChartRenderer 被挪到 0x500，OrderViewModel 被挪到 0x700
private let build2 = DSYM(
    uuid: "9C3F5E7A", version: "3.1.0",
    symbols: [
        (0x000, "AppDelegate.didFinishLaunching()"),
        (0x500, "ChartRenderer.draw()"),
        (0x700, "OrderViewModel.submit()"),
    ])
private let report = CrashReport(
    appVersion: "3.1.0", binaryUUID: "9C3F5E7A", offset: 0x540)

/// 演練流程（兩個版本共用）：symbolicate(報告, 已上傳的 dSYM)
/// 回傳還原後的函式名稱
private func scenario(
    _ log: LabLog, symbolicate: (CrashReport, [DSYM]) -> String
) {
    log(
        "crash 報告：App \(report.appVersion)，Binary UUID \(report.binaryUUID)，位址 +0x\(String(report.offset, radix: 16))"
    )
    // CI 只上傳了 build 1
    var uploaded = [build1]
    log("只有 build 1 的 dSYM：\(symbolicate(report, uploaded))")
    // 發現漏傳，補上傳 build 2
    uploaded.append(build2)
    log("補上傳 build 2 的 dSYM 之後：\(symbolicate(report, uploaded))")
    log("正確答案：\(build2.symbolicate(report.offset))")
}

// MARK: - Bug 版
private enum L04Bug {
    static func symbolicate(_ report: CrashReport, _ store: [DSYM]) -> String {
        // TODO ①：在 store 裡找第一個 version 等於 report.appVersion 的
        // dSYM（找不到回「未還原（沒有這個版本的 dSYM）」），
        //         找到就回傳 dsym.symbolicate(report.offset)
        return "未還原（還沒寫）"
    }

    static func run(_ log: LabLog) async {
        scenario(log, symbolicate: symbolicate)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func symbolicate(_ report: CrashReport, _ store: [DSYM]) -> String {
        // TODO ②：改成用 UUID 找：store 裡 uuid 等於 report.binaryUUID 的
        // dSYM。
        //         找不到就回傳「未還原（缺少 UUID \(report.binaryUUID) 的
        // dSYM）」，不要猜；找到就回傳 dsym.symbolicate(report.offset)
        return "未還原（還沒寫）"
    }

    static func run(_ log: LabLog) async {
        scenario(log, symbolicate: symbolicate)
    }
}

// MARK: - 演練設定
extension Advanced06Crash {
    static let l04 = Lab(
        id: "L04",
        title: "crash 報告指到不相干的函式",
        question: "第 3 題：crash 報告為什麼一開始看不懂？dSYM 和 mapping 檔是什麼？",
        goal:
            "crash 報告要用「同一次編譯」的 dSYM 還原：用 UUID 對，對不上就老實說還原不了，不要還原出一個錯的名字。 先寫出用版本號找 dSYM 的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced06-Crash/L04_WrongDSYM.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看兩次還原的結果，和最後一行的「正確答案」比較。",
            "想一想：build 1 和 build 2 的版本號一樣、UUID 不一樣。dSYM 內的位址表為什麼會不同？",
            "寫 TODO ②，選「修正版」執行：只有 build 1 的時候要回「未還原」；補上傳 build 2 之後要還原出 ChartRenderer.draw()。",
            "進階：在 Terminal 用 `dwarfdump --uuid MyApp.app.dSYM` 可以印出 dSYM 的 UUID，和 crash 報告的 Binary UUID 比對。",
        ],
        expectBug:
            "兩次都還原成「OrderViewModel.submit()」，和正確答案 ChartRenderer.draw() 不同；補上傳了正確的 dSYM 也沒有改善。",
        expectFixed:
            "第一次：「未還原（缺少 UUID 9C3F5E7A 的 dSYM）」；補上傳之後：「ChartRenderer.draw()」，和正確答案一致。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("只有 build 1 的 dSYM：") })
            else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("補上傳 build 2 的 dSYM 之後：OrderViewModel.submit()")
            {
                return
                    "Bug 還沒重現：用版本號（version 相同）找 dSYM，會找到 build 1 的那一份，還原出 OrderViewModel.submit()"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("只有 build 1 的 dSYM：") })
            else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("只有 build 1 的 dSYM：未還原（缺少 UUID 9C3F5E7A 的 dSYM）")
            {
                return "只有 build 1 的 dSYM 時應該回「未還原」：UUID 對不上，不能拿別次編譯的 dSYM 來猜"
            }
            if !lines.contains("補上傳 build 2 的 dSYM 之後：ChartRenderer.draw()") {
                return
                    "補上傳 build 2 的 dSYM 之後應該還原出 ChartRenderer.draw()：要用 binaryUUID 找 uuid 一樣的 dSYM"
            }
            return nil
        }
    )
}
