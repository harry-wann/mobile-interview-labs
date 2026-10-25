// L05：背景任務直接改畫面上的文字，App 當掉（只能在主執行緒碰的東西，沒有標 @MainActor）
// 對應複習站：Swift05Actors 第 3 題「@MainActor 與 global actor 是什麼？該怎麼用？」
//
// 情境：報價標籤（StatusLabel）和 UIKit 的 UILabel 一樣，只能在主執行緒修改。ViewModel 用背景任務下載價格，下載完要更新標籤。
//       這個演練用 dispatchPrecondition 模擬 UIKit 的主執行緒檢查（UIKit 真的碰到會有 Main Thread Checker 警告或畫面錯亂）。
//
// 你要寫：
//   ① Bug 版：StatusLabel 沒有任何標註：setText 先 dispatchPrecondition(condition: .onQueue(.main))，再存 text；run 在 Task.detached 裡直接呼叫 label.setText。
//   ② 修正版：StatusLabel 標 @MainActor（setText 記下是不是在主執行緒、存 text）；背景任務用 await 呼叫 label.setText。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift05-Actors/L05_BackgroundUI.swift
import Foundation
import LabKit

// MARK: - Bug 版
private enum L05Bug {
    /// 模擬 UILabel：只能在主執行緒修改
    final class StatusLabel {
        private(set) var text = ""

        func setText(_ newText: String) {
            // TODO ①-1：先 dispatchPrecondition(condition: .onQueue(.main))（不在主執行緒就當掉），再把 newText 存進 text
        }
    }

    static func run(_ log: LabLog) async {
        let label = StatusLabel()
        await Task.detached {
            try? await Task.sleep(for: .milliseconds(50))            // 假裝在背景下載價格
            // TODO ①-2：在背景直接呼叫 label.setText("BTC/JPY 15000000")（沒有任何編譯警告）
        }.value
        log("畫面上的文字：\(label.text)")
    }
}

// MARK: - 修正版
private enum L05Fixed {
    /// 標 @MainActor：所有成員都在主 actor 上，從外面呼叫要 await
    // TODO ②-1：這個 class 要標 @MainActor（所有成員都在主 actor 上）
    final class StatusLabel {
        private(set) var text = ""
        private(set) var updatedOnMainThread = false

        func setText(_ newText: String) {
            updatedOnMainThread = Swift05Actors.onMainThread()
            text = newText
        }
    }

    static func run(_ log: LabLog) async {
        let label = await StatusLabel()
        await Task.detached {
            try? await Task.sleep(for: .milliseconds(50))
            // TODO ②-2：呼叫 label.setText("BTC/JPY 15000000")（StatusLabel 標了 @MainActor 之後，編譯器會要求你加 await）
        }.value
        log("畫面上的文字：\(await label.text)")
        log("更新時在主執行緒：\(await label.updatedOnMainThread)")
    }
}

// MARK: - 演練設定
extension Swift05Actors {
    static let l05 = Lab(
        id: "L05",
        title: "背景任務改畫面文字，App 當掉",
        question: "第 3 題：@MainActor 與 global actor 是什麼？該怎麼用？",
        goal: "背景下載完價格後要更新標籤，標籤只能在主執行緒改。讓編譯器幫你擋下「在背景直接改」的寫法。",
        steps: [
            "從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "寫 TODO ①，選「練習」「Bug 版」按「執行」：約 0.05 秒後 App 當掉，Xcode 中斷在 libdispatch 裡，除錯導覽列的呼叫堆疊最上面是 _dispatch_assert_queue_fail，往下找得到 setText 裡 dispatchPrecondition 那一行。",
            "想一想：編譯器有提醒你這個呼叫是在背景嗎？StatusLabel 有沒有告訴編譯器它屬於哪個 actor？",
            "按 ■ 停止後重新執行，寫 TODO ②，選「修正版」執行：標籤更新成功，更新時在主執行緒：true。再試著把 setText 前面的 await 拿掉，看編譯錯誤。",
        ],
        expectBug: "App 當掉，呼叫堆疊最上面是 _dispatch_assert_queue_fail，往下找得到 setText 裡 dispatchPrecondition 那一行。",
        expectFixed: "畫面上的文字：BTC/JPY 15000000；更新時在主執行緒：true。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkFixed: { lines in
            guard lines.contains("畫面上的文字：BTC/JPY 15000000") else { return "標籤的文字應該被更新成 BTC/JPY 15000000：背景任務要 await label.setText(…)" }
            return lines.last == "更新時在主執行緒：true" ? nil : "更新標籤時應該在主執行緒：StatusLabel 要標 @MainActor"
        }
    )
}
