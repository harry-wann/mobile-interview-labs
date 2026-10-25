// L02：從背景呼叫沒事，在主執行緒呼叫 App 就當掉（DispatchQueue.main.sync）
// 對應複習站：Advanced04Races 第 4 題「deadlock 是什麼？怎麼避免、怎麼診斷？」
//
// 情境：團隊寫了一個小工具函式 showPrice，用來「確保更新畫面是在主執行緒做」。
// 它先在背景的行情執行緒用過，一直沒事。
//        後來有人在按鈕的處理函式（本來就在主執行緒）裡也呼叫它。
//
// 你要寫：
//   ① Bug 版 showPrice：用 DispatchQueue.main.sync { update() }。
// 從背景呼叫沒問題；從主執行緒呼叫，區塊排不進被自己占著的主佇列，
//      libdispatch 偵測到 deadlock 直接終止 App。這個版本寫對了會當掉，要從
// Xcode 執行才看得到當掉的位置（所以自動檢查略過 Bug 版）。
//   ② 修正版 showPrice：改成 DispatchQueue.main.async { update() }：
// 不管從哪一條執行緒呼叫，都只是把工作排進主佇列，不用等。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced04-Races/L02_MainSync.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：先從背景執行緒呼叫，再從主執行緒呼叫
@MainActor
private func scenario(
    _ log: LabLog,
    showPrice: @escaping @Sendable (@escaping @Sendable () -> Void) -> Void
) async {
    log("從背景執行緒呼叫 showPrice")
    await withCheckedContinuation { continuation in
        DispatchQueue.global().async {
            showPrice {
                log("更新畫面，在主執行緒：\(Thread.isMainThread)")
                continuation.resume()
            }
        }
    }
    log("背景呼叫完成")
    log("從主執行緒呼叫 showPrice（按鈕處理函式）")
    showPrice { log("更新畫面，在主執行緒：\(Thread.isMainThread)") }
    // 讓排進主佇列的工作有機會執行
    try? await Task.sleep(for: .milliseconds(100))
    log("主執行緒呼叫完成")
}

// MARK: - Bug 版
private enum L02Bug {
    static let showPrice: @Sendable (@escaping @Sendable () -> Void) -> Void = {
        update in
        // TODO ①：DispatchQueue.main.sync { update() }：主執行緒呼叫時，
        // 等自己，永遠等不到
        // 暫時的寫法：直接在呼叫的執行緒更新畫面。寫好之後刪掉這行
        update()
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, showPrice: showPrice)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static let showPrice: @Sendable (@escaping @Sendable () -> Void) -> Void = {
        update in
        // TODO ②：DispatchQueue.main.async { update() }：只是把工作排進主佇列，
        // 不用等
        // 暫時的寫法：直接在呼叫的執行緒更新畫面。寫好之後刪掉這行
        update()
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, showPrice: showPrice)
    }
}

// MARK: - 演練設定
extension Advanced04Races {
    static let l02 = Lab(
        id: "L02",
        title: "從背景呼叫沒事，在主執行緒呼叫就當掉",
        question: "第 4 題：deadlock 是什麼？怎麼避免、怎麼診斷？",
        goal:
            "不要在主執行緒呼叫 DispatchQueue.main.sync。要回到主執行緒更新畫面，用 async（或 @MainActor），不要等。先寫出會當掉的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced04-Races/L02_MainSync.swift，寫 TODO ①。",
            "選「練習」「Bug 版」按「執行」：寫對的話，畫面上會先提醒「這個版本會當掉」。要從 Xcode 執行才看得到當掉的位置：背景呼叫的 log 都正常，到「從主執行緒呼叫 showPrice」之後就當掉。",
            "（想用指令確認：`./check.sh run Advanced04Races L02 solution bug` 會印出「App 當掉了」。）",
            "診斷練習：當掉的時候看 Xcode 左邊的 Debug navigator 與呼叫堆疊，最上面是 _dispatch_sync_f_slow／dispatch_sync 相關的函式，往下找到的就是 showPrice 裡 main.sync 那一行；如果是 App 卡住不是當掉，在 Xcode 按暫停鍵，看每一條執行緒各卡在哪裡。",
            "寫 TODO ②，選「修正版」執行：兩種呼叫都完成，更新畫面都在主執行緒。",
        ],
        expectBug:
            "log 到「從主執行緒呼叫 showPrice（按鈕處理函式）」為止，然後 App 當掉（Console：BUG IN CLIENT OF LIBDISPATCH: dispatch_sync called on queue already owned by current thread）。",
        expectFixed: "背景呼叫完成；主執行緒呼叫完成；兩次「更新畫面，在主執行緒：true」。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: nil,
        checkFixed: { lines in
            if !lines.contains("背景呼叫完成") {
                return "從背景呼叫也要完成：排進主佇列的區塊要呼叫 update()"
            }
            if !lines.contains("主執行緒呼叫完成") { return "從主執行緒呼叫之後沒有跑完" }
            let updates = lines.filter { $0.hasPrefix("更新畫面，在主執行緒：") }
            if updates.count != 2 {
                return "兩次呼叫都要更新畫面（update()），目前更新了 \(updates.count) 次"
            }
            if updates.contains("更新畫面，在主執行緒：false") {
                return "更新畫面要在主執行緒：用 DispatchQueue.main.async { update() }"
            }
            return nil
        }
    )
}
