// L05：扣手續費時順便通知畫面，App 直接當掉（exclusivity）
// 對應複習站：Swift01ValueSemantics 第 5 題「inout 與 exclusivity：
// 同一個變數不能同時被兩個地方修改」
//
// 情境：用 inout 扣手續費，扣的同時呼叫 callback 通知畫面更新，畫面會讀餘額。
//
// 你要寫：
//   ① Bug 版：applyFee 扣完錢後呼叫 onChange；呼叫端在 onChange 裡讀 wallet.
// balance。從 Xcode 執行，App 會當掉。
//   ② 修正版：applyFee 只扣款、不呼叫外部程式；扣完之後再通知畫面。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift01-ValueSemantics/L05_Exclusivity.swift
import LabKit

/// 錢包（已寫好）
private final class Wallet {
    var balance = 1_000
}

// MARK: - Bug 版
private enum L05Bug {
    /// 扣手續費，扣完呼叫 onChange 通知畫面
    static func applyFee(to amount: inout Int, onChange: () -> Void) {
        // TODO ①-1：amount 減 10，接著呼叫 onChange()
    }

    static func run(_ log: LabLog) async {
        let wallet = Wallet()
        log("扣款前：\(wallet.balance)")
        // TODO ①-2：呼叫 applyFee(to: &wallet.balance) { … }，在大括號裡
        // log("通知畫面：餘額 \(wallet.balance)")
        log("扣款後：\(wallet.balance)")
    }
}

// MARK: - 修正版
private enum L05Fixed {
    /// 只負責扣款，不在修改期間呼叫外部的程式
    static func applyFee(to amount: inout Int) {
        // TODO ②-1：amount 減 10
    }

    static func run(_ log: LabLog) async {
        let wallet = Wallet()
        log("扣款前：\(wallet.balance)")
        // TODO ②-2：呼叫 applyFee(to: &wallet.balance)，這一行結束後再
        // log("通知畫面：餘額 \(wallet.balance)")
        log("扣款後：\(wallet.balance)")
    }
}

// MARK: - 演練設定
extension Swift01ValueSemantics {
    static let l05 = Lab(
        id: "L05",
        title: "inout 期間又讀同一個值，App 當掉",
        question: "第 5 題：inout 與 exclusivity：同一個變數不能同時被兩個地方修改",
        goal: "扣手續費的同時通知畫面，看 Bug 版在執行時發生什麼事，再找出不會重疊存取的寫法。",
        steps: [
            "寫 TODO ①，從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "選「練習」「Bug 版」執行：App 會當掉，Xcode 停下來，主控台印出 Simultaneous accesses…。",
            "在左邊的 Debug navigator 往下找，會看到 run 裡讀 wallet.balance 的那一行。",
            "按 ■ 停止，寫 TODO ②，重新執行，選「修正版」。",
        ],
        expectBug:
            "App 當掉，主控台：Simultaneous accesses to 0x…, but modification requires exclusive access.",
        expectFixed: "依序印出扣款前 1000、通知畫面 990、扣款後 990。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { _ in
            // 寫對的話 App 會當掉，不會走到這裡
            "App 沒有當掉：要在 applyFee 的 callback 裡讀 wallet.balance"
        },
        checkFixed: { lines in
            lines == ["扣款前：1000", "通知畫面：餘額 990", "扣款後：990"]
                ? nil : "應該依序印出扣款前 1000、通知畫面 990、扣款後 990"
        }
    )
}
