// L03：VIP 的手續費說明，顯示的卻是一般會員的文字（protocol extension 裡的方法沒有被覆寫）
// 對應複習站：Swift03Generics 第 3 題「protocol extension 裡的方法，到底會呼叫到哪一個版本？」
//
// 情境：手續費計算器遵守 FeeCalculator 協定，extension 提供預設的 summary 說明文字。VIP 計算器自己寫了一個同名的 summary，顯示「VIP 免手續費」。
//       畫面上用 [any FeeCalculator] 放不同的計算器，一個一個顯示說明。
// Bug 版：summary 只寫在 extension 裡，不是協定的 requirement。透過 any FeeCalculator 呼叫時，編譯器直接呼叫 extension 的版本，VIP 自己的版本被忽略，也沒有任何警告。
// 修正版：把 summary 也宣告進協定，變成 requirement，執行時才查表找到 VIP 自己的版本。
import LabKit

// MARK: - Bug 版
private enum L03Bug {
    protocol FeeCalculator {
        func fee(for amount: Int) -> Int                // requirement：只有宣告在這裡的才是動態派發
    }

    struct MakerFee: FeeCalculator {
        func fee(for amount: Int) -> Int { amount / 1_000 }
    }

    struct VIPFee: FeeCalculator {
        func fee(for amount: Int) -> Int { 0 }
        func summary(for amount: Int) -> String { "VIP 免手續費" }     // 想覆寫 extension 的預設版本
    }

    static func run(_ log: LabLog) async {
        let vip = VIPFee()
        log("直接用 VIPFee 呼叫：\(vip.summary(for: 1_000_000))")

        let calculators: [any FeeCalculator] = [MakerFee(), VIPFee()]
        for calculator in calculators {
            log("用 any FeeCalculator 呼叫 \(type(of: calculator))：\(calculator.summary(for: 1_000_000))")
        }
    }
}

/// 預設的說明文字：summary 不在協定宣告裡，只存在於這個 extension
extension L03Bug.FeeCalculator {
    func summary(for amount: Int) -> String { "手續費 \(fee(for: amount)) 円" }
}

// MARK: - 修正版
private enum L03Fixed {
    protocol FeeCalculator {
        func fee(for amount: Int) -> Int
        func summary(for amount: Int) -> String         // 也宣告進協定：變成 requirement，執行時查表
    }

    struct MakerFee: FeeCalculator {
        func fee(for amount: Int) -> Int { amount / 1_000 }
    }

    struct VIPFee: FeeCalculator {
        func fee(for amount: Int) -> Int { 0 }
        func summary(for amount: Int) -> String { "VIP 免手續費" }
    }

    static func run(_ log: LabLog) async {
        let vip = VIPFee()
        log("直接用 VIPFee 呼叫：\(vip.summary(for: 1_000_000))")

        let calculators: [any FeeCalculator] = [MakerFee(), VIPFee()]
        for calculator in calculators {
            log("用 any FeeCalculator 呼叫 \(type(of: calculator))：\(calculator.summary(for: 1_000_000))")
        }
    }
}

/// 預設的說明文字：沒有自己版本的計算器用這個
extension L03Fixed.FeeCalculator {
    func summary(for amount: Int) -> String { "手續費 \(fee(for: amount)) 円" }
}

// MARK: - 演練設定
extension Swift03Generics {
    static let l03 = Lab(
        id: "L03",
        title: "VIP 的說明文字被預設文字蓋掉",
        question: "第 3 題：protocol extension 裡的方法，到底會呼叫到哪一個版本？",
        goal: "VIP 計算器自己的 summary 要在 [any FeeCalculator] 裡也被呼叫到。找出為什麼會跑到 extension 的預設版本，並修好。",
        steps: [
            "選「Bug 版」執行，比較兩種呼叫：直接用 VIPFee 呼叫是「VIP 免手續費」，用 any FeeCalculator 呼叫卻是「手續費 0 円」。",
            "想一想：summary 有寫在協定宣告裡嗎？編譯器看到的是 VIPFee，還是 any FeeCalculator？",
            "選「修正版」執行，兩種呼叫都應該是「VIP 免手續費」。",
        ],
        expectBug: "直接呼叫是「VIP 免手續費」，透過 any FeeCalculator 呼叫 VIPFee 卻是「手續費 0 円」。",
        expectFixed: "兩種呼叫 VIPFee 都是「VIP 免手續費」，MakerFee 是「手續費 1000 円」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("直接用 VIPFee 呼叫：VIP 免手續費") else { return "VIPFee 要有自己的 summary，直接呼叫時顯示「VIP 免手續費」" }
            guard lines.contains("用 any FeeCalculator 呼叫 MakerFee：手續費 1000 円") else { return "extension 要提供預設的 summary：「手續費 N 円」" }
            return lines.contains("用 any FeeCalculator 呼叫 VIPFee：手續費 0 円")
                ? nil : "Bug 還沒重現：透過 any FeeCalculator 呼叫 VIPFee 時，應該跑到 extension 的預設版本（summary 不要宣告進協定）"
        },
        checkFixed: { lines in
            guard lines.contains("用 any FeeCalculator 呼叫 MakerFee：手續費 1000 円") else { return "沒有自己版本的 MakerFee 要用預設的 summary：「手續費 N 円」" }
            return lines.contains("用 any FeeCalculator 呼叫 VIPFee：VIP 免手續費")
                ? nil : "透過 any FeeCalculator 呼叫 VIPFee 時，應該跑到 VIP 自己的版本：summary 要宣告進協定"
        }
    )
}
