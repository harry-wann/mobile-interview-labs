// L06：只用 evaluatePolicy 擋一下，token 其實沒有綁定生物辨識（要讓 Keychain
// 項目本身要求驗證）
// 對應複習站：Platform04SecureStorage 第 5 題「生物辨識怎麼「綁定」金鑰，
// 而不是只在畫面上擋一下？」
//
// 情境：使用者要查看資產前，要先通過 Face ID／Touch ID。演練把 token 存進
// Keychain 之後，用 SecItemCopyMatching 讀回這個項目的屬性，
//        看它有沒有帶著 SecAccessControl（存取控制）、而且要求生物辨識（屬性
// accc 裡會出現 pbio）。
//        （模擬器沒有登記 Face ID，系統不會真的跳驗證，
// 所以檢查的是「項目被設成什麼」；真的會跳 Face ID 要在真機上看。）
//
// 你要寫：
//   ① Bug 版：token 當一般項目存進 Keychain，不附 SecAccessControl（讀取前只靠
// evaluatePolicy 擋一下）。
//   ② 修正版：存進去時附上 SecAccessControl（biometryCurrentSet），
// 讓項目本身要求生物辨識。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform04-SecureStorage/L06_BiometricBinding.swift
import Foundation
import LocalAuthentication
import Security
import LabKit

/// 讀回 Keychain 項目的屬性：有沒有 SecAccessControl，
/// 有的話內容是什麼（兩個版本共用）
private func accessControlDescription(account: String) -> String? {
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "tw.harry.labs.platform04.l06",
        kSecAttrAccount as String: account,
        kSecReturnAttributes as String: true,
    ]
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
        let attrs = item as? [String: Any]
    else { return nil }
    return attrs[kSecAttrAccessControl as String].map { String(describing: $0) }
        ?? ""
}

private func delete(account: String) {
    SecItemDelete(
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "tw.harry.labs.platform04.l06",
            kSecAttrAccount as String: account,
        ] as CFDictionary)
}

/// 演練流程（兩個版本共用）
private func scenario(
    _ log: LabLog, save: (_ account: String, _ secret: Data) -> Void
) {
    delete(account: "token")
    save("token", Data("session-token".utf8))
    guard let description = accessControlDescription(account: "token") else {
        log("Keychain 裡找不到 token：要用 SecItemAdd 存進去")
        return
    }
    log(
        "token 的 Keychain 項目有 SecAccessControl：\(description.isEmpty ? "沒有" : "有")"
    )
    log("項目本身要求生物辨識（讀取時由系統驗證）：\(description.contains("pbio") ? "是" : "否")")
    delete(account: "token")
}

// MARK: - Bug 版
private enum L06Bug {
    /// 一般項目；讀之前由 App 自己呼叫 evaluatePolicy 擋一下（只是 if 判斷）
    static func save(account: String, secret: Data) {
        // TODO ①：組出一般的 Keychain 項目（kSecClass 是
        // kSecClassGenericPassword，kSecAttrService 是 "tw.harry.labs.
        // platform04.l06"，kSecAttrAccount 是 account，kSecValueData 是
        // secret，
        //        kSecAttrAccessible 是
        // kSecAttrAccessibleWhenUnlockedThisDeviceOnly），不附
        // kSecAttrAccessControl，再呼叫 SecItemAdd
    }

    /// 正式 App 裡讀取之前會這樣擋：成功才讀。這只是 App 自己的判斷，可以被跳過
    static func readAfterGate() async -> Bool {
        (try? await LAContext().evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: "確認身分以查看資產")) ?? false
    }

    static func run(_ log: LabLog) async {
        scenario(log, save: save)
    }
}

// MARK: - 修正版
private enum L06Fixed {
    /// 項目本身綁定生物辨識：讀取時由系統強制驗證
    static func save(account: String, secret: Data) {
        // TODO ②-1：用 SecAccessControlCreateWithFlags 建立存取控制：
        // accessibility 用 kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly，
        // flags 用 biometryCurrentSet
        // TODO ②-2：組出 Keychain 項目（kSecClass、kSecAttrService、
        // kSecAttrAccount、kSecValueData 同上），改用 kSecAttrAccessControl
        // 附上剛剛建立的存取控制（不要同時給 kSecAttrAccessible），再呼叫
        // SecItemAdd
    }

    static func run(_ log: LabLog) async {
        scenario(log, save: save)
    }
}

// MARK: - 演練設定
extension Platform04SecureStorage {
    static let l06 = Lab(
        id: "L06",
        title: "只擋一下，token 沒有綁定生物辨識",
        question: "第 5 題：生物辨識怎麼「綁定」金鑰，而不是只在畫面上擋一下？",
        goal:
            "讓 Keychain 項目本身要求生物辨識（SecAccessControl），不要只靠 evaluatePolicy 的結果判斷。先寫出只靠 evaluatePolicy 擋的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform04-SecureStorage/L06_BiometricBinding.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看 Keychain 項目有沒有 SecAccessControl。",
            "寫 TODO ②（修正版），選「修正版」執行：項目有 SecAccessControl，而且要求生物辨識。",
            "在真機上驗證：修正版存的項目，讀取時系統會跳出 Face ID。Info.plist 要有 NSFaceIDUsageDescription（這個 App 已經設好）。",
        ],
        expectBug: "token 的 Keychain 項目有 SecAccessControl：沒有；項目本身要求生物辨識：否。",
        expectFixed: "token 的 Keychain 項目有 SecAccessControl：有；項目本身要求生物辨識：是。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            if lines.contains(where: { $0.hasPrefix("Keychain 裡找不到") }) {
                return "Keychain 裡找不到 token：save 要用 SecItemAdd 存進去"
            }
            if !lines.contains("項目本身要求生物辨識（讀取時由系統驗證）：否") {
                return
                    "Bug 還沒重現：Bug 版把 token 當一般項目存（不要附上 kSecAttrAccessControl）"
            }
            return nil
        },
        checkFixed: { lines in
            if lines.contains(where: { $0.hasPrefix("Keychain 裡找不到") }) {
                return "Keychain 裡找不到 token：save 要用 SecItemAdd 存進去"
            }
            if !lines.contains("項目本身要求生物辨識（讀取時由系統驗證）：是") {
                return
                    "項目還沒綁定生物辨識：用 SecAccessControlCreateWithFlags 建立 .biometryCurrentSet，再用 kSecAttrAccessControl 附在項目上"
            }
            return nil
        }
    )
}
