// L02：輸錯一次卡片密碼，App 自動重試了好幾次，卡片被鎖住了（NFC
// 讀卡失敗要依狀態碼分類處理）
// 對應複習站：Exchange11eKYC 第 3 題「NFC 讀卡失敗時要怎麼處理？」
//
// 情境：用 NFC 讀個人編號卡之類的 IC 卡時，App 送 APDU 指令給卡片，
// 卡片回應的最後兩個位元組是狀態碼（SW1、SW2）。
//       這裡用一張假卡片，狀態碼用的是 ISO 7816-4 的通用定義：9000 成功；63Cx
// 密碼驗證失敗、還剩 x 次機會；6983 密碼已被鎖定。
//       假卡片的密碼是 1234（演練用，不是真的），剩餘 3 次，錯到 0 次就鎖定。
// （卡片的實際規格以官方資料為準，這裡只演練「依狀態碼分類」。）
//       App 的做法是：送出密碼，
// 依狀態碼決定下一步（成功／自動重試／顯示密碼錯誤與剩餘次數／顯示已鎖定／顯示讀取失敗），
// 整個流程最多送 5 次。
//       兩個情境：①使用者輸錯一次密碼（輸入 1243）②使用者輸入正確密碼，
// 但卡片在中途移開了一次（第一次送出沒有收到回應）。
//
// 你要寫：
//   ① Bug 版（L02Bug.classify）：sw 是 0x9000 回傳 done，其他（包含 nil）
// 一律回傳 retry。
//   ② 修正版（L02Fixed.classify）：sw 是 nil → retry；0x9000 → done；高位的 12
// 位元是 0x63C（sw & 0xFFF0 == 0x63C0）→ 剩餘次數是 sw & 0x000F，
//      大於 0 回傳 showWrongPin(left:)，等於 0 回傳 showLocked；0x6983 →
// showLocked；其他 → showFailed。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange11-eKYC/L02_CardPin.swift
import Foundation
import LabKit

/// 假卡片
private final class FakeCard {
    private let correctPin = "1234"
    private(set) var triesLeft = 3
    private(set) var locked = false
    private(set) var sendCount = 0
    // 前幾次送出會因為卡片移開而沒有回應
    private var dropNext: Int

    init(dropFirst: Int = 0) { dropNext = dropFirst }

    /// 送出密碼。回傳狀態碼；nil 代表沒有收到回應（卡片移開），
    /// 這種情況不會消耗剩餘次數
    func verify(pin: String) -> UInt16? {
        sendCount += 1
        if dropNext > 0 { dropNext -= 1; return nil }
        if locked { return 0x6983 }
        if pin == correctPin { triesLeft = 3; return 0x9000 }
        triesLeft -= 1
        if triesLeft == 0 { locked = true }
        return 0x63C0 | UInt16(triesLeft)
    }
}

private enum Action: Equatable {
    case done
    case retry
    case showWrongPin(left: Int)
    case showLocked
    case showFailed
}

/// 送出密碼並依分類結果處理，最多送 5 次。回傳畫面上要顯示的訊息
private func submit(
    pin: String, card: FakeCard, classify: (UInt16?) -> Action
) -> String {
    for _ in 1...5 {
        switch classify(card.verify(pin: pin)) {
        case .done: return "讀取成功"
        case .retry: continue
        case .showWrongPin(let left): return "密碼錯誤，還剩 \(left) 次"
        case .showLocked: return "卡片已鎖定，請到市區町村的窗口解鎖"
        case .showFailed: return "讀取失敗，請再試一次"
        }
    }
    return "讀取失敗，請再試一次"
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, classify: (UInt16?) -> Action) {
    let card1 = FakeCard()
    let message1 = submit(pin: "1243", card: card1, classify: classify)
    log(
        "情境 1（輸錯一次密碼）：卡片收到 \(card1.sendCount) 次密碼，剩餘嘗試次數 \(card1.triesLeft)，卡片鎖定：\(card1.locked ? "是" : "否")"
    )
    log("情境 1 畫面顯示：\(message1)")

    let card2 = FakeCard(dropFirst: 1)
    let message2 = submit(pin: "1234", card: card2, classify: classify)
    log("情境 2（卡片中途移開一次）：卡片收到 \(card2.sendCount) 次指令，畫面顯示：\(message2)")
}

// MARK: - Bug 版
private enum L02Bug {
    fileprivate static func classify(_ sw: UInt16?) -> Action {
        // TODO ①：sw 等於 0x9000 回傳 .done，其他（包含 nil）一律回傳 .retry
        return .showFailed
    }

    static func run(_ log: LabLog) async {
        scenario(log, classify: classify(_:))
    }
}

// MARK: - 修正版
private enum L02Fixed {
    fileprivate static func classify(_ sw: UInt16?) -> Action {
        // TODO ②：sw 是 nil → retry；0x9000 → done；sw & 0xFFF0 == 0x63C0 →
        // left = Int(sw & 0x000F)，大於 0 回傳 showWrongPin(left: left)，等於
        // 0 回傳 showLocked；
        //        0x6983 → .showLocked；其他 → .showFailed
        return .showFailed
    }

    static func run(_ log: LabLog) async {
        scenario(log, classify: classify(_:))
    }
}

// MARK: - 演練設定
extension Exchange11eKYC {
    static let l02 = Lab(
        id: "L02",
        title: "輸錯一次密碼，卡片被自動重試鎖住",
        question: "第 3 題：NFC 讀卡失敗時要怎麼處理？",
        goal: "讀卡失敗要依狀態碼分類：卡片移開可以重試；密碼錯誤要停下來告訴使用者還剩幾次，不能自動重試；已鎖定要給明確指引。",
        steps: [
            "打開 Practice/Exchange/Exchange11-eKYC/L02_CardPin.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看情境 1：使用者輸入一次，卡片收到幾次密碼？剩餘次數和鎖定狀態是什麼？",
            "想一想：為什麼「卡片移開」可以放心重試，「密碼錯誤」不行？兩者的狀態碼差在哪裡？",
            "寫 TODO ②（修正版），選「修正版」執行，情境 1 只送一次、顯示「密碼錯誤，還剩 2 次」，情境 2 重試一次後成功。",
            "進階：卡片密碼和 APDU 的內容都不能寫進 log，也不能送進分析工具；密碼只在記憶體裡處理。",
        ],
        expectBug:
            "「情境 1（輸錯一次密碼）：卡片收到 5 次密碼，剩餘嘗試次數 0，卡片鎖定：是」（同一個錯的密碼被自動送了 5 次），畫面「讀取失敗，請再試一次」；情境 2 重試一次後成功。",
        expectFixed:
            "「情境 1：卡片收到 1 次密碼，剩餘嘗試次數 2，卡片鎖定：否」，畫面「密碼錯誤，還剩 2 次」；「情境 2：卡片收到 2 次指令，畫面顯示：讀取成功」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("情境 2") }) else {
                return "演練沒有跑完：要用 scenario(log, classify:) 執行"
            }
            if !lines.contains("情境 2（卡片中途移開一次）：卡片收到 2 次指令，畫面顯示：讀取成功") {
                return "Bug 版的卡片移開要自動重試，第二次送出後讀取成功"
            }
            return lines.contains("情境 1（輸錯一次密碼）：卡片收到 5 次密碼，剩餘嘗試次數 0，卡片鎖定：是")
                ? nil : "Bug 還沒重現：不是 9000 就一律自動重試，同一個錯的密碼會被送 5 次，卡片被鎖定"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("情境 2") }) else {
                return "演練沒有跑完：要用 scenario(log, classify:) 執行"
            }
            if !lines.contains("情境 2（卡片中途移開一次）：卡片收到 2 次指令，畫面顯示：讀取成功") {
                return "情境 2：沒有回應（nil）要重試，重試後會成功"
            }
            if !lines.contains("情境 1（輸錯一次密碼）：卡片收到 1 次密碼，剩餘嘗試次數 2，卡片鎖定：否") {
                return "情境 1：密碼錯誤（63Cx）要停止，不能自動重試，卡片只應該收到 1 次密碼"
            }
            return lines.contains("情境 1 畫面顯示：密碼錯誤，還剩 2 次")
                ? nil : "情境 1 的畫面要顯示「密碼錯誤，還剩 2 次」（剩餘次數是狀態碼最後一位）"
        }
    )
}
