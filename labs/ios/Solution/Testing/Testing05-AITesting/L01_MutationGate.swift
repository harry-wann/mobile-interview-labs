// L01：AI 產生的 5 個測試全過、覆蓋率 100%，被改壞的程式卻只抓到 1
// 個（用覆蓋率當合併條件）
// 對應複習站：Testing05AITesting 第 2 題「怎麼確保 AI 產生的測試是可信的？」
//
// 情境：AI 幫手續費函式 T05Fee.calculate 產生了 5 個單元測試，全部通過，
// 每個檢查點都跑到（覆蓋率 100%）。
//       審查規則 accepts(suite) 決定「這批測試能不能合併」。
//       另外有 5 個「故意改壞的版本」（T05Mutation.allMutants：>= 改成 >、
// 費率對調、四捨五入、拿掉最少 1 日圓、VIP 沒減半），
//       suite.passes(on: 某個版本) 會用這個版本跑一次全部測試，
// 沒有通過代表測試「抓到」這個壞版本。
//       演練分別審查兩批測試：AI 產生的、人補強過的（檢查精確數字與邊界）。
// Bug 版：「測試全過，而且覆蓋率 ≥ 80%」就允許合併。AI 產生的測試被放行，
// 但它只抓到 5 個壞版本裡的 1 個。
// 修正版：加上 mutation score：對每個改壞的版本跑一次測試，
// 被抓到的比例（mutation score）要 ≥ 80% 才允許合併。
//        AI 產生的測試（20%）被擋下；補強後的測試（100%）通過。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：審查兩批測試，再印出實際上抓到幾個壞版本
private func scenario(_ log: LabLog, accepts: (T05Suite) -> Bool) {
    for suite in [T05Suite.aiGenerated, T05Suite.strengthened] {
        let allPass = suite.passes(on: .none)
        log(
            "\(suite.name)：\(allPass ? "全部通過" : "有測試失敗")，覆蓋率 \(Int(suite.coverage() * 100))%"
        )
        log("\(suite.name)：審查結果：\(accepts(suite) ? "允許合併" : "擋下")")
        let killed = suite.killedMutants()
        let total = T05Mutation.allMutants.count
        log(
            "\(suite.name)：被改壞的版本抓到 \(killed)／\(total)（mutation score \(killed * 100 / total)%）"
        )
    }
}

// MARK: - Bug 版
private enum L01Bug {
    static func accepts(_ suite: T05Suite) -> Bool {
        // 測試全過、覆蓋率夠高，就允許合併
        suite.passes(on: .none) && suite.coverage() >= 0.8
    }

    static func run(_ log: LabLog) async {
        scenario(log, accepts: accepts)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func accepts(_ suite: T05Suite) -> Bool {
        // 在正確的實作上也要全過
        guard suite.passes(on: .none) else { return false }
        var killed = 0
        for mutant in T05Mutation.allMutants where !suite.passes(on: mutant) {
            // 這個改壞的版本讓測試失敗：抓到了
            killed += 1
        }
        // mutation score 要夠高
        return Double(killed) / Double(T05Mutation.allMutants.count) >= 0.8
    }

    static func run(_ log: LabLog) async {
        scenario(log, accepts: accepts)
    }
}

// MARK: - 演練設定
extension Testing05AITesting {
    static let l01 = Lab(
        id: "L01",
        title: "AI 的測試全過、覆蓋率 100%，卻抓不到壞掉的程式",
        question: "第 2 題：怎麼確保 AI 產生的測試是可信的？",
        goal: "審查 AI 產生的測試時，不能只看「全過」和覆蓋率，要故意把程式改壞，看測試抓得到幾個（mutation score）。",
        steps: [
            "選「Bug 版」執行，看兩批測試的審查結果，以及各自抓到幾個壞版本。",
            "想一想：AI 產生的那批測試，哪些改壞的版本沒被抓到？為什麼？（對照 T05Suite.aiGenerated 的檢查條件）",
            "選「修正版」執行：AI 產生的那批要被擋下，補強後的要通過。",
        ],
        expectBug:
            "AI 產生的測試：全部通過、覆蓋率 100%、審查結果允許合併，卻只抓到 1／5（mutation score 20%）；補強後的測試也允許合併。",
        expectFixed: "AI 產生的測試被擋下（mutation score 20%）；補強後的測試允許合併（抓到 5／5，100%）。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("AI 產生的測試：被改壞的版本抓到 1／5（mutation score 20%）")
            else { return "找不到實際抓到的數量：要用 scenario 審查" }
            guard lines.contains("AI 產生的測試：審查結果：允許合併") else {
                return "Bug 還沒重現：AI 產生的測試全過、覆蓋率 100%，只看這兩項就應該允許合併"
            }
            return lines.contains("補強後的測試：審查結果：允許合併")
                ? nil : "補強後的測試全過、覆蓋率也夠高，應該允許合併"
        },
        checkFixed: { lines in
            guard lines.contains("AI 產生的測試：審查結果：擋下") else {
                return "AI 產生的測試只抓到 1／5，mutation score 20% 不到 80%，應該擋下"
            }
            return lines.contains("補強後的測試：審查結果：允許合併")
                ? nil
                : "補強後的測試抓到 5／5，應該允許合併：對每個 T05Mutation.allMutants 跑 suite.passes(on:)，沒通過就算抓到"
        }
    )
}
