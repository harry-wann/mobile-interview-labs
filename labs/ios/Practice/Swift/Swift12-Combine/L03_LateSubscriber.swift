// L03：打開資產畫面，餘額是空白的（PassthroughSubject 不保留目前的值）
// 對應複習站：Swift12Combine 第 3 題「PublishRelay、BehaviorRelay 對應什麼？
// 為什麼 bind(to:) 不能直接換成 subscribe(_:)？」
//
// 情境：登入後先用 REST 拿到餘額 1200000，送進共用的 BalanceStore；之後
// 使用者才打開資產畫面、訂閱餘額；接著 WebSocket 推來新的餘額 1500000。
//
// 你要寫：
//   ① Bug 版：BalanceStore 用 PassthroughSubject（對應 PublishRelay）：update
//     時 send，balance 回傳 subject.eraseToAnyPublisher()。
//   ② 修正版：改用初始值為 nil 的 CurrentValueSubject（對應 BehaviorRelay），
//     balance 用 compactMap 濾掉還沒載入的 nil。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift12-Combine/L03_LateSubscriber.swift
import Combine
import LabKit

/// 共用的餘額 store（兩個版本各自實作）
private protocol L03Store: AnyObject {
    var balance: AnyPublisher<Int, Never> { get }
    func update(_ value: Int)
}

/// 演練流程（兩個版本共用）：先送餘額，之後才打開畫面，再推一筆
private func scenario(_ log: LabLog, _ store: L03Store) {
    // 登入後，REST 先拿到餘額，送進共用的 store
    store.update(1_200_000)

    // 之後使用者才打開資產畫面，訂閱餘額
    let shown = Swift12Combine.Box("—")
    let cancellable = store.balance.sink { value in
        shown.value = "\(value)"
    }
    log("打開資產畫面時顯示：\(shown.value)")

    // WebSocket 推來新的餘額
    store.update(1_500_000)
    log("下一次推送後顯示：\(shown.value)")
    withExtendedLifetime(cancellable) {}
}

// MARK: - Bug 版
private enum L03Bug {
    final class BalanceStore: L03Store {
        // TODO ①-1：宣告一個不保留目前值的 subject（對應 PublishRelay）

        var balance: AnyPublisher<Int, Never> {
            // TODO ①-2：把 subject 轉成 AnyPublisher 回傳
            Empty().eraseToAnyPublisher()
        }

        func update(_ value: Int) {
            // TODO ①-3：把 value 送進 subject
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, BalanceStore())
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class BalanceStore: L03Store {
        // TODO ②-1：宣告一個保留目前值的 subject，型別是 Int?，初始值 nil
        // （對應 BehaviorRelay）

        var balance: AnyPublisher<Int, Never> {
            // TODO ②-2：用 compactMap 濾掉 nil，再轉成 AnyPublisher 回傳
            Empty().eraseToAnyPublisher()
        }

        func update(_ value: Int) {
            // TODO ②-3：把 value 送進 subject
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, BalanceStore())
    }
}

// MARK: - 演練設定
extension Swift12Combine {
    static let l03 = Lab(
        id: "L03",
        title: "打開資產畫面，餘額是空白的",
        question:
            "第 3 題：PublishRelay、BehaviorRelay 對應什麼？為什麼 bind(to:) 不能直接換成 subscribe(_:)？",
        goal: "畫面晚一點才訂閱，也要馬上顯示目前的餘額。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看第一行：畫面打開時顯示「—」，餘額明明已經載入了，出現 ✓。",
            "看第二行：要等下一次推送，畫面才有數字。",
            "寫 TODO ②，選「修正版」執行：畫面打開時就顯示 1200000，推送後變成 1500000。",
            "想一想：RxSwift 裡你會選 PublishRelay 還是 BehaviorRelay？什麼資料是「狀態」、什麼是「事件」？",
        ],
        expectBug: "打開資產畫面時顯示：—；下一次推送後顯示：1500000。",
        expectFixed: "打開資產畫面時顯示：1200000；下一次推送後顯示：1500000。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("下一次推送後顯示：1500000") else {
                return "store 還沒接起來：update 要把值 send 進 subject，balance 要回傳 subject"
            }
            return lines.contains("打開資產畫面時顯示：—")
                ? nil : "Bug 還沒重現：要用不保留目前值的 PassthroughSubject"
        },
        checkFixed: { lines in
            guard lines.contains("下一次推送後顯示：1500000") else {
                return "store 還沒接起來：update 要把值 send 進 subject，balance 要回傳 subject"
            }
            return lines.contains("打開資產畫面時顯示：1200000")
                ? nil
                : "晚到的訂閱者要先收到目前的餘額：改用初始值為 nil 的 CurrentValueSubject，balance 用 compactMap 濾掉 nil"
        }
    )
}
