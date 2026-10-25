// L05：畫面上的手續費合計和伺服器的帳單差了 2 円（formatter 預設是銀行家捨入）
// 對應複習站：Exchange02Precision 第 5 題「數字要怎麼顯示？」（捨入模式）
//
// 情境：日圓沒有小數，手續費要四捨五入到整數。四筆成交，金額 12,500、37,500、62,500、87,500 円，手續費率 0.1%（演練假設），
//       手續費剛好是 12.5、37.5、62.5、87.5 円。伺服器的帳單規則（演練假設）是四捨五入，剛好 0.5 進位：13、38、63、88，合計 202 円。
//       App 用 NumberFormatter 把手續費顯示成整數。
//
// 你要寫：
//   ① Bug 版（L05Bug.format）：建立 NumberFormatter 顯示成整數，不設 roundingMode（預設是 .halfEven）。
//   ② 修正版（L05Fixed.format）：明確設定 roundingMode = .halfUp。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange02-Precision/L05_HalfEven.swift
import Foundation
import LabKit

private let amounts = ["12500", "37500", "62500", "87500"]
private let serverFees = [13, 38, 63, 88]          // 伺服器帳單（四捨五入）
private let feeRate = e02("0.001")                  // 演練假設：0.1%

/// 演練流程（兩個版本共用）：逐筆算手續費、用 formatter 顯示，和帳單比對
private func scenario(_ log: LabLog, format: (Decimal) -> String) {
    var shownTotal = 0
    for (amount, serverFee) in zip(amounts, serverFees) {
        let fee = e02(amount) * feeRate
        let shown = format(fee)
        shownTotal += Int(shown) ?? 0
        log("成交 \(amount) 円，手續費 \(fee) 円 → 畫面顯示 \(shown)（伺服器帳單 \(serverFee)）")
    }
    let serverTotal = serverFees.reduce(0, +)
    log("畫面手續費合計 \(shownTotal) 円，伺服器帳單合計 \(serverTotal) 円，差 \(serverTotal - shownTotal) 円")
}

// MARK: - Bug 版
private enum L05Bug {
    static func format(_ value: Decimal) -> String {
        // TODO ①：建立 NumberFormatter：numberStyle = .decimal、locale 用 en_US_POSIX、usesGroupingSeparator = false、
        //        maximumFractionDigits = 0（日圓沒有小數）；不要設 roundingMode（預設是 .halfEven）。
        //        用 formatter.string(from: NSDecimalNumber(decimal: value)) 轉成文字回傳
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, format: format(_:))
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func format(_ value: Decimal) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.usesGroupingSeparator = false
        formatter.maximumFractionDigits = 0
        // TODO ②：明確設定 formatter.roundingMode 為 .halfUp（剛好 0.5 時進位）
        return formatter.string(from: NSDecimalNumber(decimal: value)) ?? ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, format: format(_:))
    }
}

// MARK: - 演練設定
extension Exchange02Precision {
    static let l05 = Lab(
        id: "L05",
        title: "畫面上的手續費合計和帳單差了 2 円",
        question: "第 5 題：數字要怎麼顯示？",
        goal: "畫面顯示的捨入結果要和伺服器的規則一致。formatter 的捨入模式不要靠預設值，要明確指定。先寫出靠預設捨入模式的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange02-Precision/L05_HalfEven.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看哪幾筆的顯示和伺服器帳單不一樣。",
            "想一想：12.5 和 62.5 剛好在一半。NumberFormatter 預設的 roundingMode 是哪一種？",
            "寫 TODO ②（修正版），選「修正版」執行，四筆都要和帳單一樣。",
            "進階：Android 的 DecimalFormat 預設也是 HALF_EVEN；兩個平台都要明確指定，而且捨入規則也要放進共用測試資料。",
        ],
        expectBug: "12.5 顯示 12、62.5 顯示 62（37.5、87.5 剛好一樣），「畫面手續費合計 200 円，伺服器帳單合計 202 円，差 2 円」。",
        expectFixed: "四筆都和帳單一樣：13、38、63、88，「畫面手續費合計 202 円，伺服器帳單合計 202 円，差 0 円」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("畫面手續費合計") }) { return "演練沒有跑完：要用 scenario(log, format:) 執行" }
            return lines.contains("畫面手續費合計 200 円，伺服器帳單合計 202 円，差 2 円")
                ? nil : "Bug 還沒重現：NumberFormatter 不要設 roundingMode（預設 .halfEven），12.5 會顯示 12、62.5 會顯示 62"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("畫面手續費合計") }) { return "演練沒有跑完：要用 scenario(log, format:) 執行" }
            return lines.contains("畫面手續費合計 202 円，伺服器帳單合計 202 円，差 0 円")
                ? nil : "formatter 要設 roundingMode = .halfUp，剛好 0.5 才會進位"
        }
    )
}
