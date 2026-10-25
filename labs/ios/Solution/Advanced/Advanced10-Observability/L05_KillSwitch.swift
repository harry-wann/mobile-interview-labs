// L05：營運按下了 kill switch，使用者離線重開 App，
// 被關掉的功能又出現了（預設值與快取）
// 對應複習站：Advanced10Observability 第 4 題「商店不能回復版本，
// 出問題時有哪些安全網？（kill switch、強制更新）」
//
// 情境：新的出金流程放在遠端 kill switch（newWithdrawFlow）後面，平常是開的。
// 出事時營運把遠端設定改成關。
//       演練依序發生：① 使用者在線上啟動 App，遠端是開；② 營運按下 kill
// switch（遠端改成關），使用者把 App 切到背景再回到前景；
//       ③ 使用者進了地鐵（沒有網路），重新啟動 App；④
// 另一位新使用者在沒有網路的地方，剛裝好 App 就打開。
//       「遠端設定」與「本機儲存」都是假的物件，用來模擬 Remote Config 與
// UserDefaults／DataStore。
// Bug 版：只在啟動時取一次遠端設定，回到前景不重新取；
// 取不到時用寫在程式裡的預設值 true（開），也沒有把上一次的結果存起來。
//        結果：回到前景時功能還開著；離線重開 App，被關掉的功能又出現；
// 全新安裝離線時，高風險功能預設是開的。
// 修正版：啟動與回到前景都重新取得設定，成功就存起來；取不到時用上一次存的值；
// 連存的值都沒有，才用「保守」的預設值（關）。
import Foundation
import LabKit

/// 假的遠端設定（Remote Config）：online 為 false 時取不到（兩個版本共用）
private final class FakeRemote {
    var online = true
    // 遠端目前的值：功能開或關
    var value = true

    func fetch() -> Bool? { online ? value : nil }
}

/// 假的本機儲存（UserDefaults／DataStore）：重新啟動 App
/// 之後還在（兩個版本共用）
private final class FakeStore {
    var cached: Bool?
}

private protocol KillSwitch: AnyObject {
    var isEnabled: Bool { get }
    func onLaunch()
    func onForeground()
}

/// 演練流程（兩個版本共用）：makeSwitch 建立一個「這次啟動的 App」使用的 kill
/// switch
private func scenario(
    _ log: LabLog, makeSwitch: (FakeRemote, FakeStore) -> KillSwitch
) {
    let remote = FakeRemote()
    let store = FakeStore()
    func text(_ on: Bool) -> String { on ? "開" : "關" }

    // ① 在線上啟動，遠端是開
    let app1 = makeSwitch(remote, store)
    app1.onLaunch()
    log("① 啟動（線上，遠端=開）：功能\(text(app1.isEnabled))")

    // ② 營運按下 kill switch，使用者回到前景
    remote.value = false
    app1.onForeground()
    log("② 營運按下 kill switch（遠端=關），使用者回到前景：功能\(text(app1.isEnabled))")

    // ③ 離線，重新啟動 App
    remote.online = false
    let app2 = makeSwitch(remote, store)
    app2.onLaunch()
    log("③ 使用者進了地鐵（離線），重新啟動 App：功能\(text(app2.isEnabled))")

    // ④ 另一位使用者，全新安裝，離線
    let app3 = makeSwitch(remote, FakeStore())
    app3.onLaunch()
    log("④ 全新安裝、離線：功能\(text(app3.isEnabled))")
}

// MARK: - Bug 版
private enum L05Bug {
    final class Switch: KillSwitch {
        private let remote: FakeRemote
        private var fetched: Bool?

        init(remote: FakeRemote, store: FakeStore) {
            self.remote = remote
        }

        // 取不到就用寫在程式裡的預設值 true
        var isEnabled: Bool { fetched ?? true }

        // 只有啟動時取一次，也沒有存起來
        func onLaunch() { fetched = remote.fetch() }
        // 回到前景什麼都不做
        func onForeground() {}
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Switch(remote: $0, store: $1) }
    }
}

// MARK: - 修正版
private enum L05Fixed {
    final class Switch: KillSwitch {
        private let remote: FakeRemote
        private let store: FakeStore

        init(remote: FakeRemote, store: FakeStore) {
            self.remote = remote
            self.store = store
        }

        // 取不到用上一次存的值；都沒有就用保守的預設值（關）
        var isEnabled: Bool { store.cached ?? false }

        func onLaunch() { refresh() }
        // 回到前景也重新取得
        func onForeground() { refresh() }

        private func refresh() {
            // 成功就存起來，離線時還能用
            if let latest = remote.fetch() { store.cached = latest }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Switch(remote: $0, store: $1) }
    }
}

// MARK: - 演練設定
extension Advanced10Observability {
    static let l05 = Lab(
        id: "L05",
        title: "按了 kill switch，離線重開 App 功能又出現",
        question: "第 4 題：商店不能回復版本，出問題時有哪些安全網？（kill switch、強制更新）",
        goal:
            "kill switch 要「立刻生效」而且離線也有效：回到前景重新取得、成功就存起來、取不到用存的值、連存的都沒有就用保守的預設值（關）。",
        steps: [
            "選「Bug 版」執行，看四個時間點功能是開還是關，哪幾個不該是開？",
            "想一想：營運按下 kill switch 的當下，使用者正在用 App，什麼時候才會生效？使用者離線重開 App 呢？",
            "選「修正版」執行：② 回到前景就關，③ 離線仍然關（用上次存的），④ 全新安裝離線也關（保守預設）。",
            "進階：開關只決定功能出不出現，不能當權限判斷，權限一律在伺服器；要求更新到最低版本的強制更新，是另一道安全網（Platform07Release 演練 2）。",
        ],
        expectBug: "① 開、② 開（回到前景沒有重新取得）、③ 開（離線用了預設值 true）、④ 開（高風險功能預設是開的）。",
        expectFixed: "① 開、② 關、③ 關、④ 關。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("① 啟動（線上，遠端=開）：功能開") else {
                return "演練沒有跑完：① 啟動時線上取得遠端設定，功能應該是開"
            }
            if !lines.contains("② 營運按下 kill switch（遠端=關），使用者回到前景：功能開") {
                return "Bug 還沒重現：回到前景不重新取得，功能應該還是開"
            }
            if !lines.contains("③ 使用者進了地鐵（離線），重新啟動 App：功能開") {
                return "Bug 還沒重現：離線取不到，用程式裡的預設值 true，功能應該是開"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("① 啟動（線上，遠端=開）：功能開") else {
                return "① 啟動時線上取得遠端設定，功能應該是開：onLaunch 要取得並存起來"
            }
            if !lines.contains("② 營運按下 kill switch（遠端=關），使用者回到前景：功能關") {
                return "回到前景應該重新取得設定：onForeground 也要 refresh"
            }
            if !lines.contains("③ 使用者進了地鐵（離線），重新啟動 App：功能關") {
                return "離線時應該用上一次存的值（關）：取得成功時存進 store.cached，isEnabled 讀它"
            }
            if !lines.contains("④ 全新安裝、離線：功能關") {
                return "全新安裝又離線，沒有存過任何值，預設值應該是保守的「關」"
            }
            return nil
        }
    )
}
