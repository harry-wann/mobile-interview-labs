// L01：把 token 存進 UserDefaults，備份檔或越獄後一翻就看到（token 要放
// Keychain）
// 對應複習站：Platform04SecureStorage 第 1 題「token 要存在哪裡？為什麼不能放
// UserDefaults 或 SharedPreferences？」
//
// 情境：登入之後，App 要把 access token 存在手機上，下次開啟就不用重新登入。
// 演練檢查三件事：
//        ① App 自己讀得回 token；② 模擬攻擊者翻 UserDefaults，看得到明文 token
// 嗎；
//        ③ 模擬攻擊者拿到手機備份（或越獄之後能讀 App 的資料夾）：掃描 App
// 資料夾裡所有檔案，有幾個檔案含明文 token。
//        （UserDefaults 由系統稍後才寫成 Library/Preferences 底下的 plist 檔，
// 通常幾秒內，系統忙的時候更久，所以第 ③ 項在 Bug 版可能是 0 個或 1 個；
// 自動檢查看第 ②。）
// Bug 版：存進 UserDefaults。UserDefaults 沒有加密，內容就是明文，
// 系統會把它寫成 plist 檔。
// 修正版：存進 Keychain（SecItemAdd／SecItemCopyMatching）。Keychain
// 由系統加密保管，不在 App 的資料夾裡，也不在 UserDefaults 裡，
// 攻擊者什麼都翻不到。
import Foundation
import Security
import LabKit

private let suiteName = "tw.harry.labs.platform04.l01"

/// 存放 token 的地方（兩個版本各自實作）
private protocol TokenStore {
    func save(_ token: String)
    func load() -> String?
    func wipe()
}

/// 攻擊者翻 UserDefaults（兩個版本共用）：App 的標準 UserDefaults
/// 與這個演練用的 suite，有沒有明文 token
private func userDefaultsHolds(_ token: String) -> Bool {
    let standard = UserDefaults.standard.persistentDomain(
        forName: Bundle.main.bundleIdentifier ?? "")
    let suite = UserDefaults(suiteName: suiteName)?.persistentDomain(
        forName: suiteName)
    return [standard, suite].contains { domain in
        domain?.values.contains { ($0 as? String) == token } ?? false
    }
}

/// 攻擊者掃描 App 的資料夾（兩個版本共用）：找出內容含有這個 token 的檔案
private func filesContaining(_ token: String) -> [String] {
    let home = NSHomeDirectory()
    guard let walker = FileManager.default.enumerator(atPath: home) else {
        return []
    }
    let needle = Data(token.utf8)
    var found: [String] = []
    for case let relative as String in walker {
        let url = URL(fileURLWithPath: home).appendingPathComponent(relative)
        guard
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?
                .fileSize, size > 0, size < 2_000_000,
            let data = try? Data(contentsOf: url)
        else { continue }
        if data.range(of: needle) != nil { found.append(relative) }
    }
    return found
}

/// 演練流程（兩個版本共用）：存 token → App 自己讀得回來嗎 → 攻擊者翻
/// UserDefaults、掃描資料夾
private func scenario(_ log: LabLog, store: TokenStore) async {
    let token = "eyJ.\(UUID().uuidString)"
    store.wipe()
    store.save(token)
    log("App 自己讀得回 token：\(store.load() == token ? "是" : "否")")
    log("攻擊者翻 UserDefaults，看到明文 token：\(userDefaultsHolds(token) ? "是" : "否")")
    var leaked: [String] = []
    // 最多等 1.5 秒，看系統有沒有把檔案寫到磁碟
    for _ in 0..<15 {
        leaked = filesContaining(token)
        if !leaked.isEmpty { break }
        try? await Task.sleep(for: .milliseconds(100))
    }
    log(
        "攻擊者掃描 App 的資料夾，在 \(leaked.count) 個檔案裡找到明文 token\(leaked.first.map { "：\($0)" } ?? "")"
    )
    store.wipe()
}

// MARK: - Bug 版
private enum L01Bug {
    struct Store: TokenStore {
        private let defaults = UserDefaults(suiteName: suiteName)!

        // 明文，系統會寫成 plist 檔
        func save(_ token: String) {
            defaults.set(token, forKey: "accessToken")
        }
        func load() -> String? { defaults.string(forKey: "accessToken") }
        func wipe() { defaults.removePersistentDomain(forName: suiteName) }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, store: Store())
    }
}

// MARK: - 修正版
private enum L01Fixed {
    struct Store: TokenStore {
        private let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "tw.harry.labs.platform04.l01",
            kSecAttrAccount as String: "accessToken",
        ]

        func save(_ token: String) {
            // 先刪掉舊的，避免重複
            wipe()
            var add = base
            add[kSecValueData as String] = Data(token.utf8)
            add[kSecAttrAccessible as String] =
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }

        func load() -> String? {
            var query = base
            query[kSecReturnData as String] = true
            var item: CFTypeRef?
            guard
                SecItemCopyMatching(query as CFDictionary, &item)
                    == errSecSuccess, let data = item as? Data
            else { return nil }
            return String(data: data, encoding: .utf8)
        }

        func wipe() { SecItemDelete(base as CFDictionary) }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, store: Store())
    }
}

// MARK: - 演練設定
private func leakedCount(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("攻擊者掃描 App 的資料夾，在 ") }
        .flatMap {
            Int(
                $0.replacingOccurrences(of: "攻擊者掃描 App 的資料夾，在 ", with: "")
                    .components(separatedBy: " ").first ?? "")
        }
}

extension Platform04SecureStorage {
    static let l01 = Lab(
        id: "L01",
        title: "token 存在 UserDefaults，一翻就看到",
        question: "第 1 題：token 要存在哪裡？為什麼不能放 UserDefaults 或 SharedPreferences？",
        goal:
            "token 要存進 Keychain。App 自己讀得回來，但攻擊者翻 UserDefaults、掃描 App 的資料夾，都找不到明文的 token。",
        steps: [
            "選「Bug 版」按執行，看「攻擊者翻 UserDefaults，看到明文 token」；稍等幾秒，掃描資料夾通常會找到 Library/Preferences 底下的 plist 檔。",
            "選「修正版」執行：App 一樣讀得回 token，但翻 UserDefaults 是「否」，掃描資料夾找到 0 個檔案。",
            "想一想：Keychain 也不是絕對安全（越獄後仍可能被讀出），所以 token 要短效、伺服器端可以註銷，敏感操作還要再次驗證。",
        ],
        expectBug:
            "App 讀得回 token；攻擊者翻 UserDefaults 看到明文 token：是；掃描資料夾可能找到 1 個 plist 檔（系統稍後才寫入，可能還是 0 個）。",
        expectFixed:
            "App 讀得回 token；攻擊者翻 UserDefaults 看到明文 token：否；掃描資料夾找到 0 個檔案。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("App 自己讀得回 token：是") {
                return
                    "App 自己要讀得回 token：save 要把 token 存進 UserDefaults，load 要讀出來"
            }
            return lines.contains("攻擊者翻 UserDefaults，看到明文 token：是")
                ? nil
                : "Bug 還沒重現：Bug 版要把 token 存進 UserDefaults（defaults.set），它是沒有加密的明文"
        },
        checkFixed: { lines in
            guard let n = leakedCount(lines) else { return "演練沒有跑完" }
            if !lines.contains("App 自己讀得回 token：是") {
                return
                    "App 自己要讀得回 token：用 SecItemAdd 存、SecItemCopyMatching（kSecReturnData）讀"
            }
            if !lines.contains("攻擊者翻 UserDefaults，看到明文 token：否") {
                return "UserDefaults 裡還看得到明文 token：不要用 UserDefaults，改存 Keychain"
            }
            return n == 0
                ? nil
                : "掃描還是找到 \(n) 個檔案有明文 token：不要用 UserDefaults 或自己寫檔，改存 Keychain"
        }
    )
}
