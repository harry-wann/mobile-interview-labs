// L04：Release 版也能打開「略過憑證檢查」的除錯開關（#if DEBUG）
// 對應複習站：Platform07Release 第 7 題「一個完整的發布流程是什麼樣子？」（用 Release 設定建置並驗證）
//
// 這題 Debug 建置看不出差別，要用 Release 建置執行：
//   Xcode：Product > Scheme > Edit Scheme > Run > Build Configuration 改成 Release，再按 ⌘R。
//   指令列：LABS_CONFIG=Release ./check.sh selftest Platform07Release
//
// 情境：測試人員為了用 Charles 看封包，工程師加了一個除錯開關：在除錯選單把 UserDefaults 的 "skipPinning" 打開，
//       App 就不檢查伺服器的憑證（直接信任）。出貨前沒有人記得拿掉。演練把開關打開，再讓攻擊者的假憑證（pin 對不上）來連線。
//
// 你要寫：
//   ① Bug 版：skipPinningEnabled 直接回傳 UserDefaults.standard.bool(forKey: switchKey)。
//   ② 修正版：用 #if DEBUG 包起來：Debug 建置回傳 UserDefaults 的值，#else（Release）回傳 false。
// 只改有 TODO 的地方。解答：Solution/Platform/Platform07-Release/L04_DebugSwitch.swift
import Foundation
import LabKit

private let switchKey = "P07SkipPinning"

/// 憑證檢查（兩個版本共用）：pin 對不上時，除錯開關有開才放行
private func evaluate(pinMatches: Bool, skipPinning: Bool) -> String {
    (pinMatches || skipPinning) ? "放行" : "擋下"
}

/// 目前是 Debug 還是 Release 建置（兩個版本共用）
private let buildConfiguration: String = {
    #if DEBUG
    "Debug"
    #else
    "Release"
    #endif
}()

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, skipPinningEnabled: () -> Bool) {
    UserDefaults.standard.set(true, forKey: switchKey)                  // 除錯選單把開關打開
    defer { UserDefaults.standard.removeObject(forKey: switchKey) }
    log("建置組態：\(buildConfiguration)")
    log("除錯開關已打開，攻擊者的假憑證（pin 對不上）：\(evaluate(pinMatches: false, skipPinning: skipPinningEnabled()))")
    if buildConfiguration == "Debug" { log("（Debug 建置看不出差別，請改用 Release 建置執行）") }
}

// MARK: - Bug 版
private enum L04Bug {
    static func skipPinningEnabled() -> Bool {
        // TODO ①：直接回傳 UserDefaults.standard.bool(forKey: switchKey)，沒有任何建置組態的限制
        false
    }

    static func run(_ log: LabLog) async {
        scenario(log, skipPinningEnabled: skipPinningEnabled)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func skipPinningEnabled() -> Bool {
        // TODO ②：用 #if DEBUG … #else … #endif：Debug 建置回傳 UserDefaults.standard.bool(forKey: switchKey)，
        //        Release 建置（#else）回傳 false
        false
    }

    static func run(_ log: LabLog) async {
        scenario(log, skipPinningEnabled: skipPinningEnabled)
    }
}

// MARK: - 演練設定
extension Platform07Release {
    static let l04 = Lab(
        id: "L04",
        title: "Release 版也能打開「略過憑證檢查」的除錯開關",
        question: "第 7 題：一個完整的發布流程是什麼樣子？（用 Release 設定建置並驗證）",
        goal: "除錯用的開關只能存在於 Debug 建置。Release 建置裡，即使有人把開關打開，假憑證也要被擋下。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看第一行是 Debug 還是 Release，以及假憑證有沒有被放行，出現 ✓。",
            "寫 TODO ②，把建置組態改成 Release（Xcode：Product > Scheme > Edit Scheme > Run > Build Configuration 選 Release），再執行 Bug 版和修正版。",
            "想一想：Release 建置裡，修正版的 #if DEBUG 區塊還在不在？",
            "進階：Android 的對應是 BuildConfig.DEBUG 與 debug 專用的 source set／manifest（debug-overrides）；它們也要在 release build 驗證過。",
        ],
        expectBug: "Debug、Release 都是「除錯開關已打開，攻擊者的假憑證（pin 對不上）：放行」。",
        expectFixed: "Debug 建置：放行（除錯用得到）；Release 建置：「攻擊者的假憑證（pin 對不上）：擋下」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("除錯開關已打開，攻擊者的假憑證（pin 對不上）：放行")
                ? nil : "Bug 還沒重現：skipPinningEnabled() 直接讀 UserDefaults.standard.bool(forKey: switchKey)，不管哪種建置都放行"
        },
        checkFixed: { lines in
            let expected = lines.contains("建置組態：Release") ? "擋下" : "放行"
            if lines.contains("除錯開關已打開，攻擊者的假憑證（pin 對不上）：\(expected)") { return nil }
            return expected == "擋下"
                ? "Release 建置不該有這個開關：把讀 UserDefaults 的那行放進 #if DEBUG，#else 回傳 false"
                : "Debug 建置的除錯開關還要能用：#if DEBUG 裡面讀 UserDefaults.standard.bool(forKey: switchKey)"
        }
    )
}
