// L02：下單後馬上離開畫面，App 當掉（[unowned self] 用在網路回呼）
// 對應複習站：Swift02ARC 第 2 題「weak 和 unowned 差在哪？什麼時候用哪個？」
//
// 情境：下單畫面按「送出」，ViewModel 呼叫交易所 API，回應回來時更新狀態。使用者按完送出就立刻離開畫面。
// Bug 版：回呼用 [unowned self]。回應回來時 ViewModel 已經被釋放，讀取 unowned 參考就直接當掉。
// 修正版：回呼被「活得比畫面久的東西」（網路層的單例）持有，用 [weak self]，self 不在了就什麼都不做。
import Foundation
import LabKit

/// 假的交易所 API：單例，活得比任何畫面都久。回應在 0.3 秒後從背景執行緒回來（兩個版本共用）
private final class ExchangeAPI {
    static let shared = ExchangeAPI()

    func submitOrder(completion: @escaping (String) -> Void) {
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.3) {
            completion("委託成功")
        }
    }
}

// MARK: - Bug 版
private enum L02Bug {
    final class OrderViewModel {
        var status = "待送出"

        func submit(log: LabLog) {
            ExchangeAPI.shared.submitOrder { [unowned self] result in
                // 以為 self 一定還在。但這個 closure 是網路層持有的，使用者離開畫面之後才被呼叫
                self.status = result
                log("收到回應，狀態更新為 \(self.status)")
            }
        }
    }

    static func run(_ log: LabLog) async {
        var viewModel: OrderViewModel? = OrderViewModel()
        viewModel?.submit(log: log)
        log("按下送出，馬上關掉畫面")
        viewModel = nil                               // 畫面關閉，ViewModel 被釋放
        try? await Task.sleep(for: .seconds(1))       // 0.3 秒後回應回來
        log("跑到這裡代表沒有當掉")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    final class OrderViewModel {
        var status = "待送出"

        func submit(log: LabLog) {
            ExchangeAPI.shared.submitOrder { [weak self] result in
                // weak：self 被釋放後會變成 nil，不會當掉
                guard let self else {
                    log("回應回來時畫面已經關閉，什麼都不做")
                    return
                }
                self.status = result
                log("收到回應，狀態更新為 \(self.status)")
            }
        }
    }

    static func run(_ log: LabLog) async {
        var viewModel: OrderViewModel? = OrderViewModel()
        viewModel?.submit(log: log)
        log("按下送出，馬上關掉畫面")
        viewModel = nil
        try? await Task.sleep(for: .seconds(1))
        log("跑到這裡代表沒有當掉")
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l02 = Lab(
        id: "L02",
        title: "下單後馬上離開畫面，App 當掉",
        question: "第 2 題：weak 和 unowned 差在哪？什麼時候用哪個？",
        goal: "網路回應回來的時候，下單畫面的 ViewModel 已經被釋放。找出為什麼會當掉，並改成不會當掉的寫法。",
        steps: [
            "從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "選「Bug 版」按「執行」：約 0.3 秒後 App 當掉，Xcode 停在 self.status = result 那一行，主控台印出 Attempted to read an unowned reference but object … was already deallocated。",
            "想一想：這個 closure 是被 ViewModel 自己的子物件持有，還是被網路層的單例持有？self 能保證比它活得久嗎？",
            "按 ■ 停止後重新執行，選「修正版」執行，確認不會當掉。",
        ],
        expectBug: "App 當掉，主控台：Fatal error: Attempted to read an unowned reference but object 0x… was already deallocated。",
        expectFixed: "印出「回應回來時畫面已經關閉，什麼都不做」，接著印出「跑到這裡代表沒有當掉」。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkFixed: { lines in
            guard lines.contains("回應回來時畫面已經關閉，什麼都不做") else { return "修正版應該印出「回應回來時畫面已經關閉，什麼都不做」：closure 要用 [weak self]，self 是 nil 時印這行" }
            guard lines.last == "跑到這裡代表沒有當掉" else { return "修正版不應該當掉" }
            return nil
        }
    )
}
