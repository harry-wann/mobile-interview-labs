// L03：打開資產畫面，餘額是空白的（PassthroughSubject 不保留目前的值）
// 對應複習站：Swift12Combine 第 3 題「PublishRelay、BehaviorRelay 對應什麼？
// 為什麼 bind(to:) 不能直接換成 subscribe(_:)？」
//
// 情境：登入後先用 REST 拿到餘額 1200000，送進共用的 BalanceStore；之後
// 使用者才打開資產畫面、訂閱餘額；接著 WebSocket 推來新的餘額 1500000。
// Bug 版：BalanceStore 用 PassthroughSubject（對應 PublishRelay）。畫面訂閱時
//        那一筆已經送完了，畫面顯示「—」，要等下一次推送才有數字。
// 修正版：改用 CurrentValueSubject<Int?, Never>(nil)（對應 BehaviorRelay），
//        晚到的訂閱者先收到目前的餘額；nil 代表還沒載入，用 compactMap 濾掉。
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
        // 不保留目前的值（對應 RxSwift 的 PublishRelay）
        private let subject = PassthroughSubject<Int, Never>()

        var balance: AnyPublisher<Int, Never> {
            subject.eraseToAnyPublisher()
        }

        func update(_ value: Int) {
            subject.send(value)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, BalanceStore())
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class BalanceStore: L03Store {
        // 保留目前的值（對應 BehaviorRelay<Int?>(value: nil)）；
        // nil 代表還沒載入
        private let subject = CurrentValueSubject<Int?, Never>(nil)

        var balance: AnyPublisher<Int, Never> {
            // 晚到的訂閱者先收到目前的餘額；還沒載入的 nil 不送給畫面
            subject.compactMap { $0 }.eraseToAnyPublisher()
        }

        func update(_ value: Int) {
            subject.send(value)
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
            "選「Bug 版」執行，看第一行：畫面打開時顯示「—」，餘額明明已經載入了。",
            "看第二行：要等下一次推送，畫面才有數字。",
            "選「修正版」執行：畫面打開時就顯示 1200000，推送後變成 1500000。",
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
