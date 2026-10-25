// L01：關掉聊天畫面，ViewModel 卻沒有被釋放（closure 捕獲 self 造成 retain
// cycle）
// 對應複習站：Swift02ARC 第 1 題「什麼是 retain cycle？為什麼 Swift 會有、
// Android 比較不用擔心？」
//
// 情境：聊天室的 ViewModel 把「收到訊息時要做的事」存成 closure 屬性，closure
// 裡用到 self。
//
// 你要寫：
//   ① Bug 版：ChatViewModel 的 init 裡給 onMessage 一個 closure，直接用 self，
// 讓 ViewModel 和 closure 互相持有。
//   ② 修正版：同樣的 closure，改用 [weak self]。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift02-ARC/L01_ClosureCycle.swift
import SwiftUI
import LabKit

// MARK: - Bug 版
private enum L01Bug {
    static let live = "ChatViewModel（Bug 版）"

    final class ChatViewModel {
        var onMessage: ((String) -> Void)?
        var lastMessage = ""

        init() {
            LiveObjects.shared.add(live)
            // TODO ①：給 onMessage 一個 closure：收到 text 就把它寫進 self.
            // lastMessage（直接用 self，不加 capture list）
        }

        deinit { LiveObjects.shared.remove(live) }
    }

    static func run(_ log: LabLog) async {
        await Swift02ARC.openThenClose(
            log, name: live,
            beforeClose: { (vm: ChatViewModel) in
                vm.onMessage?("BTC/JPY 突破 1500 萬")
                log("收到訊息後，lastMessage = \(vm.lastMessage)")
            }
        ) { ChatViewModel() }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static let live = "ChatViewModel（修正版）"

    final class ChatViewModel {
        var onMessage: ((String) -> Void)?
        var lastMessage = ""

        init() {
            LiveObjects.shared.add(live)
            // TODO ②：同樣的 closure，但這次用 weak 的 capture list；weak 的
            // self 是 Optional，要用 self?. 存取
        }

        deinit { LiveObjects.shared.remove(live) }
    }

    static func run(_ log: LabLog) async {
        await Swift02ARC.openThenClose(
            log, name: live,
            beforeClose: { (vm: ChatViewModel) in
                vm.onMessage?("BTC/JPY 突破 1500 萬")
                log("收到訊息後，lastMessage = \(vm.lastMessage)")
            }
        ) { ChatViewModel() }
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l01 = Lab(
        id: "L01",
        title: "關掉畫面後 ViewModel 沒有被釋放",
        question: "第 1 題：什麼是 retain cycle？為什麼 Swift 會有、Android 比較不用擔心？",
        goal:
            "ViewModel 把 closure 存成屬性，closure 裡又用到 self。找出為什麼關掉畫面後它不會被釋放，並修好。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」，按畫面上方的「打開畫面」再按「關閉畫面」：「存活中 ChatViewModel」維持 1，沒有回到 0。",
            "按下方的「執行」看 log：出現 ✓ 代表 bug 重現了（關閉畫面後 weak 參考還在）。",
            "用 Memory Graph 找原因：從 Xcode 執行（⌘R），打開畫面再關閉，按除錯列的 Debug Memory Graph（三個小圓圈相連的圖示），左邊搜尋 ChatViewModel，選到它，右邊會畫出 ChatViewModel 和 closure 互相指向的循環。",
            "進階：Edit Scheme › Run › Diagnostics 勾選 Malloc Stack Logging（Live Allocations Only），Memory Graph 裡就能看到這個物件是在哪一行建立的。",
            "寫 TODO ②，選「修正版」執行，存活數應該回到 0。",
        ],
        expectBug:
            "關閉畫面後，存活中的 ChatViewModel（Bug 版）仍然是 1，weak 參考還在；Memory Graph 上看得到 ChatViewModel 和 closure 互相持有。",
        expectFixed: "關閉畫面後存活數回到 0，weak 參考變成 nil，deinit 有執行。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug:
                AnyView(
                    ScreenDemo(name: L01Bug.live) { L01Bug.ChatViewModel() })
            case .fixed:
                AnyView(
                    ScreenDemo(name: L01Fixed.live) { L01Fixed.ChatViewModel() }
                )
            }
        },
        checkBug: { lines in
            guard lines.contains("收到訊息後，lastMessage = BTC/JPY 突破 1500 萬") else {
                return
                    "收到訊息後 lastMessage 要被更新：onMessage 裡要把 text 寫進 self.lastMessage"
            }
            guard lines.last?.hasSuffix("：1（weak 參考還在）") == true else {
                return
                    "Bug 還沒重現：關閉畫面後 ViewModel 應該還活著（closure 要直接用 self，不要加 capture list）"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("收到訊息後，lastMessage = BTC/JPY 突破 1500 萬") else {
                return
                    "收到訊息後 lastMessage 要被更新：onMessage 裡要把 text 寫進 self.lastMessage"
            }
            guard lines.last?.hasSuffix("：0（weak 參考已變成 nil）") == true else {
                return "關閉畫面後 ViewModel 還活著：closure 要用 [weak self]"
            }
            return nil
        }
    )
}
