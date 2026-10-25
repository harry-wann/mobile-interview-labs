// L03：重新安裝 App 之後，不用登入就直接進去了（Keychain 在 App 被刪除後還在）
// 對應複習站：Platform04SecureStorage 第 2 題「iOS 的 Keychain 怎麼用？accessibility 怎麼選？」
//
// 情境：使用者登入後刪除 App，隔天又重新安裝。UserDefaults 會跟著 App 一起被刪除，但 Keychain 的內容通常還在（裝置上的另一個資料庫）。
//        演練用一個專屬的 UserDefaults 代表「這一次安裝的資料」，Keychain 裡先放一份「上一次安裝留下的 token」，
//        再依序模擬：重新安裝後第一次啟動 → 使用者登入 → 第二次啟動。
//
// 你要寫：
//   ① Bug 版：啟動時只看 Keychain 有沒有 token，有就當作已登入。
//   ② 修正版：沒有 hasLaunchedBefore 旗標就是重新安裝，先清掉 Keychain 殘留的 token，再設旗標。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform04-SecureStorage/L03_ReinstallLeftover.swift
import Foundation
import Security
import LabKit

private let suite = "tw.harry.labs.platform04.l03"

/// Keychain 裡的 token（兩個版本共用）
private enum TokenKeychain {
    private static let base: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "tw.harry.labs.platform04.l03",
        kSecAttrAccount as String: "accessToken",
    ]

    static func save(_ token: String) {
        delete()
        var add = base
        add[kSecValueData as String] = Data(token.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func load() -> String? {
        var query = base
        query[kSecReturnData as String] = true
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete() { SecItemDelete(base as CFDictionary) }
}

/// 演練流程（兩個版本共用）：[launch] 是 App 啟動時做的事，回傳「已登入嗎」
private func scenario(_ log: LabLog, launch: (UserDefaults) -> Bool) {
    // 上一次安裝：登入過，token 留在 Keychain；UserDefaults 隨著 App 被刪除，所以是空的
    UserDefaults.standard.removePersistentDomain(forName: suite)
    TokenKeychain.save("old-token-from-previous-install")
    let defaults = UserDefaults(suiteName: suite)!

    log("重新安裝後第一次啟動：\(launch(defaults) ? "直接登入了（用的是上一次安裝留下的 token）" : "要重新登入")")
    TokenKeychain.save("new-token-after-login")                 // 使用者重新登入
    log("使用者登入之後，第二次啟動：\(launch(defaults) ? "維持登入" : "被登出了")")

    TokenKeychain.delete()
    UserDefaults.standard.removePersistentDomain(forName: suite)
}

// MARK: - Bug 版
private enum L03Bug {
    /// App 啟動：有 token 就當作已登入
    static func launch(_ defaults: UserDefaults) -> Bool {
        // TODO ①：有 token（TokenKeychain.load() 不是 nil）就當作已登入。現在先回傳 false
        false
    }

    static func run(_ log: LabLog) async {
        scenario(log, launch: launch)
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func launch(_ defaults: UserDefaults) -> Bool {
        // TODO ②：如果 defaults 沒有 hasLaunchedBefore 旗標（UserDefaults 隨 App 刪除，沒有就是重新安裝後第一次啟動），
        //        先 TokenKeychain.delete() 清掉殘留的 token，再把旗標設成 true；最後回傳有沒有 token（TokenKeychain.load() != nil）。現在先回傳 false
        return false
    }

    static func run(_ log: LabLog) async {
        scenario(log, launch: launch)
    }
}

// MARK: - 演練設定
extension Platform04SecureStorage {
    static let l03 = Lab(
        id: "L03",
        title: "重新安裝後，不用登入就進去了",
        question: "第 2 題：iOS 的 Keychain 怎麼用？accessibility 怎麼選？",
        goal: "Keychain 在 App 被刪除後還在，所以首次啟動要清掉上一次安裝殘留的 token；之後的啟動不能清掉使用者剛登入的 token。先寫出重新安裝後直接登入的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform04-SecureStorage/L03_ReinstallLeftover.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「重新安裝後第一次啟動」是不是直接登入。",
            "寫 TODO ②（修正版），選「修正版」執行：第一次啟動要重新登入；登入之後第二次啟動維持登入。",
            "想一想：為什麼要用 UserDefaults 當旗標？（UserDefaults 會隨 App 一起被刪除，Keychain 不會，兩者的差異剛好可以判斷是不是重新安裝）",
        ],
        expectBug: "重新安裝後第一次啟動：直接登入了（用的是上一次安裝留下的 token）；登入之後第二次啟動：維持登入。",
        expectFixed: "重新安裝後第一次啟動：要重新登入；登入之後第二次啟動：維持登入。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("重新安裝後第一次啟動：直接登入了（用的是上一次安裝留下的 token）") { return "Bug 還沒重現：Bug 版啟動時只看 Keychain 有沒有 token，有就當作已登入" }
            return lines.contains("使用者登入之後，第二次啟動：維持登入") ? nil : "登入之後第二次啟動應該維持登入：有 token 就回傳 true"
        },
        checkFixed: { lines in
            if !lines.contains("重新安裝後第一次啟動：要重新登入") { return "重新安裝後第一次啟動不該直接登入：沒有 hasLaunchedBefore 旗標時，要先刪掉 Keychain 裡的殘留 token（TokenKeychain.delete()），再設旗標" }
            if !lines.contains("使用者登入之後，第二次啟動：維持登入") { return "登入之後第二次啟動被登出了：旗標設好之後，不要再清 Keychain" }
            return nil
        }
    )
}
