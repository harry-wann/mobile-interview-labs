// L09：下單連線和 ViewModel 互相持有，離開畫面後都沒有被釋放（delegate 沒宣告 weak）
// 對應複習站：Swift02ARC 第 4 題「實務上哪些寫法最容易造成 retain cycle？」（delegate）
//
// 情境：下單畫面的 ViewModel 建立一條連線（OrderSocket），把自己設成連線的 delegate，連線有狀態時通知 ViewModel。
// Bug 版：OrderSocket 的 delegate 屬性是一般的（strong）屬性。ViewModel 持有連線，連線又持有 ViewModel。
// 修正版：protocol 限定 AnyObject，delegate 屬性宣告成 weak var。
import SwiftUI
import LabKit

/// 連線狀態的回報對象。限定 AnyObject，才能在 class 裡宣告成 weak（兩個版本共用）
private protocol OrderSocketDelegate: AnyObject {
    func socketDidConnect()
}

// MARK: - Bug 版
private enum L09Bug {
    static let live = "OrderViewModel（Bug 版）"

    final class OrderSocket {
        var delegate: OrderSocketDelegate?               // 一般屬性：strong 持有 delegate

        func connect() { delegate?.socketDidConnect() }
    }

    final class OrderViewModel: OrderSocketDelegate {
        private let socket = OrderSocket()
        private(set) var connected = false

        init() {
            LiveObjects.shared.add(live)
            socket.delegate = self                       // ViewModel → socket → ViewModel
        }

        func start() { socket.connect() }
        func socketDidConnect() { connected = true }

        deinit { LiveObjects.shared.remove(live) }
    }

    static func run(_ log: LabLog) async {
        await Swift02ARC.openThenClose(log, name: live, beforeClose: { (vm: OrderViewModel) in
            vm.start()
            log("連線後，connected = \(vm.connected)")
        }) { OrderViewModel() }
    }
}

// MARK: - 修正版
private enum L09Fixed {
    static let live = "OrderViewModel（修正版）"

    final class OrderSocket {
        weak var delegate: OrderSocketDelegate?          // weak：不讓引用計數 +1

        func connect() { delegate?.socketDidConnect() }
    }

    final class OrderViewModel: OrderSocketDelegate {
        private let socket = OrderSocket()
        private(set) var connected = false

        init() {
            LiveObjects.shared.add(live)
            socket.delegate = self
        }

        func start() { socket.connect() }
        func socketDidConnect() { connected = true }

        deinit { LiveObjects.shared.remove(live) }
    }

    static func run(_ log: LabLog) async {
        await Swift02ARC.openThenClose(log, name: live, beforeClose: { (vm: OrderViewModel) in
            vm.start()
            log("連線後，connected = \(vm.connected)")
        }) { OrderViewModel() }
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l09 = Lab(
        id: "L09",
        title: "delegate 沒寫 weak，兩個物件互相抓住",
        question: "第 4 題：實務上哪些寫法最容易造成 retain cycle？",
        goal: "ViewModel 持有連線，連線的 delegate 又指回 ViewModel。找出為什麼離開畫面後兩個都不會被釋放，並修好。",
        steps: [
            "選「Bug 版」，按畫面上方的「打開畫面」再按「關閉畫面」：「存活中 OrderViewModel」維持 1。",
            "按下方的「執行」看 log：連線後 connected = true，關閉畫面後 weak 參考還在。",
            "想一想：OrderSocket 的 delegate 屬性該不該 strong 持有對方？為什麼 protocol 要加 AnyObject 才能宣告 weak？",
            "選「修正版」執行，存活數應該回到 0。",
        ],
        expectBug: "連線後 connected = true；關閉畫面後，存活中的 OrderViewModel（Bug 版）仍是 1，weak 參考還在。",
        expectFixed: "連線後 connected = true；關閉畫面後存活數回到 0，weak 參考變成 nil。",
        run: { variant, log in
            switch variant {
            case .bug: await L09Bug.run(log)
            case .fixed: await L09Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug: AnyView(ScreenDemo(name: L09Bug.live) { L09Bug.OrderViewModel() })
            case .fixed: AnyView(ScreenDemo(name: L09Fixed.live) { L09Fixed.OrderViewModel() })
            }
        },
        checkBug: { lines in
            guard lines.contains("連線後，connected = true") else { return "連線後 connected 要是 true：init 裡要設定 socket.delegate = self" }
            guard lines.last?.hasSuffix("：1（weak 參考還在）") == true else { return "Bug 還沒重現：關閉畫面後 ViewModel 應該還活著（delegate 要是一般的 strong 屬性）" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("連線後，connected = true") else { return "連線後 connected 要是 true：init 裡要設定 socket.delegate = self" }
            guard lines.last?.hasSuffix("：0（weak 參考已變成 nil）") == true else { return "關閉畫面後 ViewModel 還活著：delegate 要宣告成 weak var" }
            return nil
        }
    )
}
