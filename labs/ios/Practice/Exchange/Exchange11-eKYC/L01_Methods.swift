// L01：沒有 NFC 的手機看得到「讀卡」，伺服器停用的方式還在選單上（可用的驗證方式寫死在 App）
// 對應複習站：Exchange11eKYC 第 1 題「在日本開戶為什麼要做本人確認？ホ、ヘ、ワ三種方式差在哪裡？」
//
// 情境：線上本人確認有三種方式：ホ（證件影像加臉部影像，用相機）、ヘ（證件的 IC 晶片資訊加臉部影像，要用 NFC 讀卡）、ワ（電子證明書，要用 NFC 讀卡）。
//       複習站的設計重點：可用的方式不寫死在 App，改由伺服器下發（功能旗標）；法規或公司政策改變時，不用發版。
//       畫面上要顯示哪些方式 = 伺服器開放的方式 ∩ 這支手機做得到的方式（沒有 NFC 的手機不能用 ヘ、ワ）；一個都沒有時，要顯示說明頁，不能留一個空白選單。
//       演練有四種情況：①一般手機（有 NFC）、伺服器開放三種 ②沒有 NFC 的手機、伺服器開放三種 ③有 NFC、伺服器日後停用了 ホ（只開放 ヘ、ワ）
//       ④沒有 NFC、伺服器只開放 ヘ、ワ。（方式與規則是演練用的示範，實際細節以法務確認為準。）
//
// 你要寫：
//   ① Bug 版（L01Bug.screen）：不管伺服器和手機，一律回傳 .choose(Method.allCases)（三種方式都顯示）。
//   ② 修正版（L01Fixed.screen）：從 Method.allCases 留下「server 有開放，而且（不需要 NFC，或手機有 NFC）」的方式；
//      留下來的是空的就回傳 .noMethod，否則回傳 .choose(留下來的方式)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange11-eKYC/L01_Methods.swift
import Foundation
import LabKit

private enum Method: String, CaseIterable {
    case ho = "ホ"          // 證件影像加臉部影像（相機）
    case he = "ヘ"          // IC 晶片資訊加臉部影像（NFC）
    case wa = "ワ"          // 電子證明書（NFC）

    var needsNFC: Bool { self != .ho }
}

private enum Screen: Equatable {
    case choose([Method])   // 選擇驗證方式的畫面
    case noMethod           // 沒有可用的方式：顯示說明與客服入口
}

private func text(_ screen: Screen) -> String {
    switch screen {
    case .choose(let methods): return "選單 " + methods.map(\.rawValue).joined(separator: "、")
    case .noMethod: return "「沒有可用方式」說明頁"
    }
}

/// （描述, 伺服器開放的方式, 這支手機有沒有 NFC, 正確的畫面）
private let cases: [(String, [Method], Bool, Screen)] = [
    ("有 NFC 的手機，伺服器開放三種", [.ho, .he, .wa], true, .choose([.ho, .he, .wa])),
    ("沒有 NFC 的手機，伺服器開放三種", [.ho, .he, .wa], false, .choose([.ho])),
    ("有 NFC 的手機，伺服器停用了 ホ", [.he, .wa], true, .choose([.he, .wa])),
    ("沒有 NFC 的手機，伺服器只開放 ヘ、ワ", [.he, .wa], false, .noMethod),
]

/// 演練流程（兩個版本共用）：四種情況逐一決定畫面，和正確答案比對
private func scenario(_ log: LabLog, screen: (_ server: [Method], _ hasNFC: Bool) -> Screen) {
    var wrong = 0
    for (name, server, hasNFC, expected) in cases {
        let shown = screen(server, hasNFC)
        if shown == expected {
            log("\(name) → 顯示\(text(shown)) ✓")
        } else {
            wrong += 1
            log("\(name) → 顯示\(text(shown)) ✗（應該顯示\(text(expected))）")
        }
    }
    log("4 種情況，畫面判斷錯的有 \(wrong) 種")
}

// MARK: - Bug 版
private enum L01Bug {
    fileprivate static func screen(server: [Method], hasNFC: Bool) -> Screen {
        // TODO ①：一律回傳 .choose(Method.allCases)
        return .noMethod
    }

    static func run(_ log: LabLog) async {
        scenario(log, screen: screen(server:hasNFC:))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    fileprivate static func screen(server: [Method], hasNFC: Bool) -> Screen {
        // TODO ②：用 Method.allCases.filter 留下「server.contains($0)，而且（!$0.needsNFC || hasNFC）」的方式；
        //        留下來的是空的就回傳 .noMethod，否則回傳 .choose(留下來的方式)
        return .noMethod
    }

    static func run(_ log: LabLog) async {
        scenario(log, screen: screen(server:hasNFC:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("4 種情況") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange11eKYC {
    static let l01 = Lab(
        id: "L01",
        title: "沒有 NFC 的手機也看得到讀卡，停用的方式還在選單上",
        question: "第 1 題：在日本開戶為什麼要做本人確認？ホ、ヘ、ワ三種方式差在哪裡？",
        goal: "可用的本人確認方式由伺服器下發，再依這支手機做得到的功能（有沒有 NFC）過濾；一個都沒有時顯示說明頁。不要把選單寫死在 App。",
        steps: [
            "打開 Practice/Exchange/Exchange11-eKYC/L01_Methods.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看四種情況的選單：哪些情況顯示了不能用或已停用的方式？",
            "想一想：法規或政策改變、伺服器停用某個方式時，寫死的選單要怎麼處理？要等使用者更新 App 嗎？",
            "寫 TODO ②（修正版），選「修正版」執行，四種情況都要顯示正確的畫面（最後一種是說明頁）。",
            "進階：NFC 讀卡的成功率也要追蹤，依機型和失敗原因分類；沒有 NFC 的使用者要有替代方式，不能卡在這一步。",
        ],
        expectBug: "第 1 種情況是對的；沒有 NFC 的手機顯示了 ホ、ヘ、ワ ✗，伺服器停用 ホ 之後選單還有 ホ ✗，沒有可用方式時顯示選單而不是說明頁 ✗：「4 種情況，畫面判斷錯的有 3 種」。",
        expectFixed: "選單依序是 ホ、ヘ、ワ／只有 ホ／ヘ、ワ，最後一種顯示「沒有可用方式」說明頁：「4 種情況，畫面判斷錯的有 0 種」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, screen:) 執行" }
            if !lines.contains("有 NFC 的手機，伺服器開放三種 → 顯示選單 ホ、ヘ、ワ ✓") { return "Bug 版的選單永遠是 ホ、ヘ、ワ 三種（有 NFC、伺服器開放三種時剛好是對的）" }
            return n == 3 ? nil : "Bug 還沒重現：寫死三種方式，後三種情況都會判斷錯（目前錯 \(n) 種）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, screen:) 執行" }
            return n == 0 ? nil : "還有 \(n) 種情況畫面不對：留下「伺服器有開放，而且手機做得到」的方式，空的就顯示說明頁"
        }
    )
}
