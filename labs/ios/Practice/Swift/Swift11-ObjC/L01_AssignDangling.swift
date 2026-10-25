// L01：關掉下單畫面之後，成交通知讓 App 當掉（Objective-C 的 assign 用在物件上）
// 對應複習站：Swift11ObjC 第 1 題「`@property` 後面的 `nonatomic`、`strong`、`weak`、`copy`、`assign` 各是什麼意思？」
//
// 情境：舊的 Objective-C 下單控制器（OrderController）用 delegate 通知畫面「委託成交了」。畫面關掉之後，控制器還活著，成交通知又來了一次。
//
// 你要寫：
//   ① Bug 版：在 PRLabL01.h，把 Bug 版 OrderController 的 delegate 屬性改成 assign（不持有，物件釋放後變成野指標）。
//   ② 修正版：把修正版 OrderController 的 delegate 屬性改成「不持有，而且物件釋放後會自動變成 nil」的特性。
//   Objective-C 的程式在 Practice/SwiftObjC/include/PRLabL01.h 與 PRLabL01.m。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift11-ObjC/L01_AssignDangling.swift
//      Objective-C 的解答：Solution/SwiftObjC/include/SOLabL01.h、Solution/SwiftObjC/SOLabL01.m
import Foundation
import LabKit
import PracticeSwiftObjC

/// 下單畫面（兩個版本共用）：實作 Objective-C 的協定，收到通知就印一行
private final class OrderScreen: NSObject, PRLabL01OrderDelegate {
    let log: LabLog
    init(log: LabLog) { self.log = log }

    func orderDidFill(_ symbol: String) {
        log("畫面收到通知：\(symbol) 成交")
    }
}

// MARK: - Bug 版
private enum L01Bug {
    static func run(_ log: LabLog) async {
        let controller = PRLabL01BugOrderController()
        do {
            let screen = OrderScreen(log: log)
            controller.delegate = screen
            _ = controller.notifyFilled()                 // 畫面還在：正常
            log("畫面關掉了（OrderScreen 被釋放）")
        }
        log("第二次成交通知…")
        let notified = controller.notifyFilled()           // delegate 是野指標：對已釋放的記憶體傳訊息，App 當掉
        log("第二次通知結果：\(notified ? "已通知" : "沒有人可以通知")")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func run(_ log: LabLog) async {
        let controller = PRLabL01FixedOrderController()
        do {
            let screen = OrderScreen(log: log)
            controller.delegate = screen
            _ = controller.notifyFilled()
            log("畫面關掉了（OrderScreen 被釋放）")
        }
        log("第二次成交通知…")
        let notified = controller.notifyFilled()           // weak：delegate 已經自動變成 nil，不會通知
        log("第二次通知結果：\(notified ? "已通知" : "沒有人可以通知")")
    }
}

// MARK: - 演練設定
extension Swift11ObjC {
    static let l01 = Lab(
        id: "L01",
        title: "關掉畫面之後，成交通知讓 App 當掉",
        question: "第 1 題：`@property` 後面的 `nonatomic`、`strong`、`weak`、`copy`、`assign` 各是什麼意思？",
        goal: "Objective-C 的 delegate 屬性用 assign 會變成野指標。先寫出會當掉的 Bug 版，再改成 weak。",
        steps: [
            "打開 Practice/SwiftObjC/include/PRLabL01.h，寫 TODO ①。",
            "從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "選「練習」「Bug 版」執行：App 當掉（EXC_BAD_ACCESS），Xcode 停在 Objective-C 的 [self.delegate orderDidFill:] 那一行。",
            "按 ■ 停止，寫 TODO ②，重新執行，選「修正版」：第二次通知時「沒有人可以通知」，不會當掉。",
            "對照 PRLabL01.h：Bug 版與修正版只差在 delegate 屬性是 assign 還是 weak。",
        ],
        expectBug: "第一次通知正常；畫面釋放後第二次通知，App 當掉（EXC_BAD_ACCESS）：assign 變成野指標。",
        expectFixed: "第二次通知結果：沒有人可以通知（weak 屬性已自動變成 nil），不會當掉。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { _ in
            // 寫對的話 App 會當掉，不會走到這裡
            "App 沒有當掉：delegate 屬性要用 assign（不是 weak）"
        },
        checkFixed: { lines in
            if !lines.contains("畫面收到通知：BTC/JPY 成交") { return "第一次通知，畫面應該收到「BTC/JPY 成交」" }
            if !lines.contains("第二次通知結果：沒有人可以通知") { return "畫面釋放後，delegate 應該自動變成 nil（沒有人可以通知）：屬性要用 weak" }
            return nil
        }
    )
}
