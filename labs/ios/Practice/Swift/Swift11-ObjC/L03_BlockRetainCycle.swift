// L03：價格監聽器關掉之後沒有被釋放（Objective-C 的 block 裡直接用 self）
// 對應複習站：Swift11ObjC 第 3 題「ObjC 的 block 怎麼捕獲變數？weak／strong 的寫法是什麼？」
//
// 情境：PriceListener 把「收到價格要做的事」存成 block 屬性 handler，收到價格時更新 lastPrice。
//
// 你要寫：
//   ① Bug 版：在 PRLabL03.m，Bug 版 PriceListener 的 start 裡，handler 的 block 直接用 self 更新 lastPrice。
//   ② 修正版：修正版的 start 改成 weak／strong 兩步（block 外面 __weak，block 開頭轉 __strong 並檢查 nil）。
//   Objective-C 的程式在 Practice/SwiftObjC/include/PRLabL03.h 與 PRLabL03.m。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift11-ObjC/L03_BlockRetainCycle.swift
//      Objective-C 的解答：Solution/SwiftObjC/include/SOLabL03.h、Solution/SwiftObjC/SOLabL03.m
import Foundation
import LabKit
import PracticeSwiftObjC

// MARK: - Bug 版
private enum L03Bug {
    static func run(_ log: LabLog) async {
        func openAndClose() {
            let listener = PRLabL03BugPriceListener()
            listener.start()
            listener.handler?(101)                         // 還在的時候，handler 正常運作
            log("收到價格 101 之後，lastPrice：\(listener.lastPrice)")
        }                                                  // 離開：外面沒有人再持有 listener
        openAndClose()
        log("離開之後，還活著的 PriceListener：\(PRLabL03BugPriceListener.aliveCount()) 個")
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func run(_ log: LabLog) async {
        func openAndClose() {
            let listener = PRLabL03FixedPriceListener()
            listener.start()
            listener.handler?(101)
            log("收到價格 101 之後，lastPrice：\(listener.lastPrice)")
        }
        openAndClose()
        log("離開之後，還活著的 PriceListener：\(PRLabL03FixedPriceListener.aliveCount()) 個")
    }
}

// MARK: - 演練設定
extension Swift11ObjC {
    static let l03 = Lab(
        id: "L03",
        title: "價格監聽器關掉之後沒有被釋放",
        question: "第 3 題：ObjC 的 block 怎麼捕獲變數？weak／strong 的寫法是什麼？",
        goal: "PriceListener 把 handler 存成自己的 block 屬性。先寫出離開之後沒被釋放的 Bug 版，再改成離開後會被釋放、而且 handler 照常運作。",
        steps: [
            "打開 Practice/SwiftObjC/PRLabL03.m，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 log：離開之後還活著幾個 PriceListener？",
            "寫 TODO ②，選「修正版」執行：離開之後 0 個，而且 handler 照常更新了 lastPrice。",
            "打開 PRLabL03.m：Bug 版的 start 直接在 block 裡用 self，修正版是 weak／strong 兩步。",
            "進階：執行 Bug 版後，用 Xcode 的 Debug Memory Graph 看被洩漏的 PriceListener，會看到它和 handler block 互相指著對方。",
        ],
        expectBug: "收到價格 101 之後 lastPrice 是 101；離開之後還活著 1 個 PriceListener：物件與 block 互相持有。",
        expectFixed: "lastPrice 是 101；離開之後還活著 0 個。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("收到價格 101 之後，lastPrice：101.0") { return "handler 要能運作：收到價格 101 之後，lastPrice 應該是 101" }
            if !lines.contains("離開之後，還活著的 PriceListener：1 個") { return "Bug 還沒重現：start 裡的 block 要直接用 self，離開之後 PriceListener 不會被釋放" }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("收到價格 101 之後，lastPrice：101.0") { return "handler 要能運作：收到價格 101 之後，lastPrice 應該是 101（weak／strong 之後 block 還是要更新 lastPrice）" }
            if !lines.contains("離開之後，還活著的 PriceListener：0 個") { return "離開之後 PriceListener 應該被釋放：block 裡要用 weak／strong 兩步，不要直接用 self" }
            return nil
        }
    )
}
