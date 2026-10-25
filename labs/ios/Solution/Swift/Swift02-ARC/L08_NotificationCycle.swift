// L08：關掉畫面後，通知一來還是會更新已經不存在的畫面（NotificationCenter 的 closure 版 observer）
// 對應複習站：Swift02ARC 第 4 題「實務上哪些寫法最容易造成 retain cycle？」（NotificationCenter）
//
// 情境：資產畫面的 ViewModel 監聽「報價更新」通知，每收到一次就重新整理。
// Bug 版：addObserver(forName:object:queue:using:) 的 closure 由 NotificationCenter 持有，closure 又用到 self，
//         而且沒有保存 token、也沒有 removeObserver。ViewModel 離開畫面後永遠不會釋放，通知來了還在處理。
// 修正版：closure 用 [weak self]，保存 addObserver 回傳的 token，在 deinit 裡 removeObserver(token)。
import SwiftUI
import LabKit

private let priceUpdated = Notification.Name("L08.priceUpdated")

// MARK: - Bug 版
private enum L08Bug {
    static let live = "AssetViewModel（Bug 版）"

    final class AssetViewModel {
        private let hits: Swift02ARC.HitCounter

        init(hits: Swift02ARC.HitCounter) {
            self.hits = hits
            LiveObjects.shared.add(live)
            NotificationCenter.default.addObserver(forName: priceUpdated, object: nil, queue: .main) { _ in
                self.refresh()               // NotificationCenter 持有 closure，closure 持有 self
            }
        }

        private func refresh() { hits.hit() }

        deinit { LiveObjects.shared.remove(live) }
    }

    static func run(_ log: LabLog) async {
        let hits = Swift02ARC.HitCounter()
        await Swift02ARC.openThenClose(log, name: live, whileOpen: { (_: AssetViewModel) in
            NotificationCenter.default.post(name: priceUpdated, object: nil)
            try? await Task.sleep(for: .milliseconds(100))   // queue: .main 的 closure 要等一下才會執行
            log("畫面開著時發一次通知，refresh 被呼叫 \(hits.count) 次")
        }) { AssetViewModel(hits: hits) }
        NotificationCenter.default.post(name: priceUpdated, object: nil)
        try? await Task.sleep(for: .milliseconds(100))
        log("畫面關掉後又發一次通知，refresh 累計被呼叫 \(hits.count) 次")
    }
}

// MARK: - 修正版
private enum L08Fixed {
    static let live = "AssetViewModel（修正版）"

    final class AssetViewModel {
        private let hits: Swift02ARC.HitCounter
        private var token: NSObjectProtocol?

        init(hits: Swift02ARC.HitCounter) {
            self.hits = hits
            LiveObjects.shared.add(live)
            token = NotificationCenter.default.addObserver(forName: priceUpdated, object: nil, queue: .main) { [weak self] _ in
                self?.refresh()              // weak：NotificationCenter 不再抓住 self
            }
        }

        private func refresh() { hits.hit() }

        deinit {
            if let token { NotificationCenter.default.removeObserver(token) }   // 移除 observer
            LiveObjects.shared.remove(live)
        }
    }

    static func run(_ log: LabLog) async {
        let hits = Swift02ARC.HitCounter()
        await Swift02ARC.openThenClose(log, name: live, whileOpen: { (_: AssetViewModel) in
            NotificationCenter.default.post(name: priceUpdated, object: nil)
            try? await Task.sleep(for: .milliseconds(100))   // queue: .main 的 closure 要等一下才會執行
            log("畫面開著時發一次通知，refresh 被呼叫 \(hits.count) 次")
        }) { AssetViewModel(hits: hits) }
        NotificationCenter.default.post(name: priceUpdated, object: nil)
        try? await Task.sleep(for: .milliseconds(100))
        log("畫面關掉後又發一次通知，refresh 累計被呼叫 \(hits.count) 次")
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l08 = Lab(
        id: "L08",
        title: "關掉畫面後通知還在更新畫面",
        question: "第 4 題：實務上哪些寫法最容易造成 retain cycle？",
        goal: "ViewModel 用 NotificationCenter 的 closure 版 observer 監聽報價更新。找出為什麼畫面關掉後它還在處理通知，並修好。",
        steps: [
            "選「Bug 版」執行，看最後兩行：畫面開著時 refresh 被呼叫 1 次，畫面關掉後再發一次通知，累計是 2 次。",
            "看關閉畫面那一行：存活中的 AssetViewModel 是 1，weak 參考還在。",
            "想一想：誰持有 closure？closure 持有誰？addObserver 回傳的 token 有拿來做什麼嗎？",
            "選「修正版」執行，畫面關掉後再發通知，refresh 不應該再被呼叫。",
        ],
        expectBug: "關閉畫面後存活數仍是 1；畫面關掉後再發通知，refresh 累計被呼叫 2 次。",
        expectFixed: "關閉畫面後存活數是 0；畫面關掉後再發通知，refresh 累計還是 1 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug: AnyView(ScreenDemo(name: L08Bug.live) { L08Bug.AssetViewModel(hits: HitCounter()) })
            case .fixed: AnyView(ScreenDemo(name: L08Fixed.live) { L08Fixed.AssetViewModel(hits: HitCounter()) })
            }
        },
        checkBug: { lines in
            guard lines.contains("畫面開著時發一次通知，refresh 被呼叫 1 次") else { return "畫面開著時收到通知，refresh 應該被呼叫 1 次：用 addObserver(forName:object:queue:using:) 監聽，closure 裡呼叫 refresh()" }
            return lines.last == "畫面關掉後又發一次通知，refresh 累計被呼叫 2 次"
                ? nil : "Bug 還沒重現：畫面關掉後 ViewModel 應該還在處理通知（closure 要直接用 self，也不要 removeObserver）"
        },
        checkFixed: { lines in
            guard lines.contains("畫面開著時發一次通知，refresh 被呼叫 1 次") else { return "畫面開著時收到通知，refresh 應該被呼叫 1 次：用 addObserver(forName:object:queue:using:) 監聽，closure 裡呼叫 refresh()" }
            return lines.last == "畫面關掉後又發一次通知，refresh 累計被呼叫 1 次"
                ? nil : "畫面關掉後 refresh 不應該再被呼叫：closure 要用 [weak self]，並在 deinit 裡 removeObserver(token)"
        }
    )
}
