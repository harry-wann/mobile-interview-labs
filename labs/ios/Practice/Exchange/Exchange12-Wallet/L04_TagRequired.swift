// L04：沒選網路也能出金、XRP 沒填 tag 被自動補成 0（網路不設預設值、必填的 tag 不能自動補）
// 對應複習站：Exchange12Wallet 第 3 題「memo／destination tag 和網路選擇，為什麼最容易出事？」
//
// 情境：出金表單有兩個容易出事的欄位：網路（同一個資產可能在多條鏈上，選錯就送到錯的鏈）和 tag（XRP 這類網路入金要帶 tag，漏帶資產會進交易所的共用地址）。
//       規則：網路不設預設值，沒選就不能送出；該網路必填 tag 時，沒填就不能送出，除非使用者明確勾選「我確認不需要 tag」；
//       勾選後送出時 tag 是空的（nil），不能自動補 0（tag 0 本身也是合法的值，補 0 會改變意義）；不需要 tag 的網路，tag 也是 nil。
//       prepare 把表單整理成「可以送出的內容」或「問題清單」。五種表單：①沒選網路 ②XRP 沒填 tag 也沒勾「不需要」③XRP 沒填 tag 但勾了「不需要」
//       ④XRP 填了 tag 5555 ⑤Bitcoin（不需要 tag）。
//
// 你要寫：
//   ① Bug 版（L04Bug.prepare）：network = form.network ?? .bitcoin；tag = form.tag 是空的就用 "0"，否則用 form.tag；
//      回傳 .ok(network: network, tag: tag)（所有網路都帶 tag）。
//   ② 修正版（L04Fixed.prepare）：form.network 是 nil → .problems(["請選擇網路"])；network.requiresTag 而且 tag 去掉空白後是空的、
//      又沒有 form.tagNotNeededConfirmed → .problems(["缺少 tag"])；否則 .ok(network: network, tag: 去掉空白後的 tag，空的就是 nil)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange12-Wallet/L04_TagRequired.swift
import Foundation
import LabKit

private enum Network: String, CaseIterable {
    case bitcoin = "Bitcoin"
    case xrp = "XRP Ledger"

    var requiresTag: Bool { self == .xrp }
}

private struct Form {
    var network: Network?
    var tag = ""
    var tagNotNeededConfirmed = false     // 使用者明確勾選「我確認這筆不需要 tag」
}

private enum Prepared: Equatable {
    case ok(network: Network, tag: String?)
    case problems([String])
}

private func text(_ p: Prepared) -> String {
    switch p {
    case .ok(let network, let tag): return "可以送出（\(network.rawValue)，tag \(tag.map { "\"\($0)\"" } ?? "沒有")）"
    case .problems(let list): return "不能送出（\(list.joined(separator: "、"))）"
    }
}

/// （描述, 表單, 正確的結果）
private let cases: [(String, Form, Prepared)] = [
    ("沒選網路", Form(network: nil), .problems(["請選擇網路"])),
    ("XRP，沒填 tag 也沒勾「不需要」", Form(network: .xrp), .problems(["缺少 tag"])),
    ("XRP，沒填 tag 但勾了「不需要」", Form(network: .xrp, tagNotNeededConfirmed: true), .ok(network: .xrp, tag: nil)),
    ("XRP，tag 5555", Form(network: .xrp, tag: "5555"), .ok(network: .xrp, tag: "5555")),
    ("Bitcoin，不需要 tag", Form(network: .bitcoin), .ok(network: .bitcoin, tag: nil)),
]

/// 演練流程（兩個版本共用）：五種表單逐一整理，和正確答案比對
private func scenario(_ log: LabLog, prepare: (Form) -> Prepared) {
    var wrong = 0
    for (name, form, expected) in cases {
        let result = prepare(form)
        if result == expected {
            log("\(name) → \(text(result)) ✓")
        } else {
            wrong += 1
            log("\(name) → \(text(result)) ✗（應該是：\(text(expected))）")
        }
    }
    log("5 種表單，整理結果錯的有 \(wrong) 種")
}

// MARK: - Bug 版
private enum L04Bug {
    fileprivate static func prepare(_ form: Form) -> Prepared {
        // TODO ①：network = form.network ?? .bitcoin；tag = form.tag 空的就用 "0"，否則用 form.tag；回傳 .ok(network: network, tag: tag)
        return .problems([])
    }

    static func run(_ log: LabLog) async {
        scenario(log, prepare: prepare(_:))
    }
}

// MARK: - 修正版
private enum L04Fixed {
    fileprivate static func prepare(_ form: Form) -> Prepared {
        // TODO ②：form.network 是 nil → .problems(["請選擇網路"])；tag = form.tag 去掉空白（.whitespaces）；
        //        network.requiresTag 而且 tag 是空的、form.tagNotNeededConfirmed 又是 false → .problems(["缺少 tag"]);
        //        否則 .ok(network: network, tag: tag.isEmpty ? nil : tag)
        return .problems([])
    }

    static func run(_ log: LabLog) async {
        scenario(log, prepare: prepare(_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("5 種表單") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange12Wallet {
    static let l04 = Lab(
        id: "L04",
        title: "沒選網路也能出金，XRP 沒填 tag 被補成 0",
        question: "第 3 題：memo／destination tag 和網路選擇，為什麼最容易出事？",
        goal: "網路不設預設值，沒選不能送出；必填 tag 的網路，沒填也沒明確確認時不能送出，而且不能自動補 0。",
        steps: [
            "打開 Practice/Exchange/Exchange12-Wallet/L04_TagRequired.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看 5 種表單：沒選網路、XRP 沒填 tag 時，Bug 版讓它們變成什麼？",
            "想一想：tag 0 是合法的值嗎？把「沒填」補成 0，伺服器和交易所收到後會當成什麼？",
            "寫 TODO ②（修正版），選「修正版」執行，5 種表單都要和規則一致。",
            "進階：地址欄和確認頁都要顯示網路名稱；地址簿的每個項目要記錄資產、網路、地址、tag 四樣；地址格式和網路明顯不相容（例如 Tron 的 T 開頭地址不可能是 EVM 地址）時直接擋下。",
        ],
        expectBug: "沒選網路、XRP 沒填 tag 也沒勾「不需要」、XRP 勾了「不需要」都變成「可以送出」並帶 tag \"0\" ✗，連 Bitcoin 也帶 tag \"0\" ✗，只有填了 5555 的是對的：「5 種表單，整理結果錯的有 4 種」。",
        expectFixed: "5 種表單都符合規則：沒選網路、XRP 缺 tag 會被擋下，勾了「不需要」的 tag 是「沒有」，不是 \"0\"：「5 種表單，整理結果錯的有 0 種」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, prepare:) 執行" }
            if !lines.contains("XRP，tag 5555 → 可以送出（XRP Ledger，tag \"5555\"） ✓") {
                return "Bug 版有填的 tag 要原樣送出（5555）"
            }
            return n == 4 ? nil : "Bug 還沒重現：網路沒選就用 Bitcoin、tag 空白就補 \"0\"，會有 4 種表單結果錯（目前 \(n) 種）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, prepare:) 執行" }
            return n == 0 ? nil : "還有 \(n) 種表單結果錯：沒選網路要擋、必填 tag 沒填也沒確認要擋，勾了「不需要」時 tag 是 nil（不補 0）"
        }
    )
}
