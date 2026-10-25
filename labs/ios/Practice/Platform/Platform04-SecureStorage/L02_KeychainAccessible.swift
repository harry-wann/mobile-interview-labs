// L02：背景處理推播時讀不到 token，而且 token 還同步到了別的裝置（Keychain 的 accessibility 與 iCloud 同步）
// 對應複習站：Platform04SecureStorage 第 2 題「iOS 的 Keychain 怎麼用？accessibility 怎麼選？」
//
// 情境：登入之後把 refresh token 存進 Keychain。要求：App 在背景處理推播時（手機可能是鎖定的）也要讀得到；
//        不能進 iCloud 鑰匙圈同步到其他裝置。演練把 token 存好之後，用 SecItemCopyMatching 讀回「這個項目的屬性」，看它被設成什麼。
//
// 你要寫：
//   ① Bug 版：沒有指定 kSecAttrAccessible（預設 WhenUnlocked），而且打開 kSecAttrSynchronizable。
//   ② 修正版：kSecAttrAccessible 指定 AfterFirstUnlockThisDeviceOnly，不開同步。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform04-SecureStorage/L02_KeychainAccessible.swift
import Foundation
import Security
import LabKit

/// 讀回 Keychain 項目的屬性（兩個版本共用）：accessibility 的名稱、有沒有同步
private func inspect(account: String) -> (accessible: String, synchronizable: Bool)? {
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "tw.harry.labs.platform04.l02",
        kSecAttrAccount as String: account,
        kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
        kSecReturnAttributes as String: true,
    ]
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let attrs = item as? [String: Any] else { return nil }
    let names: [(CFString, String)] = [
        (kSecAttrAccessibleWhenUnlocked, "WhenUnlocked"),
        (kSecAttrAccessibleWhenUnlockedThisDeviceOnly, "WhenUnlockedThisDeviceOnly"),
        (kSecAttrAccessibleAfterFirstUnlock, "AfterFirstUnlock"),
        (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, "AfterFirstUnlockThisDeviceOnly"),
        (kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, "WhenPasscodeSetThisDeviceOnly"),
    ]
    let raw = attrs[kSecAttrAccessible as String] as? String
    let name = names.first { ($0.0 as String) == raw }?.1 ?? (raw ?? "（沒有）")
    let sync = (attrs[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue ?? false
    return (name, sync)
}

private func delete(account: String) {
    SecItemDelete([
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "tw.harry.labs.platform04.l02",
        kSecAttrAccount as String: account,
        kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
    ] as CFDictionary)
}

/// 演練流程（兩個版本共用）：存 token，再讀回項目的屬性
private func scenario(_ log: LabLog, save: (_ account: String, _ token: Data) -> Void) {
    delete(account: "refreshToken")
    save("refreshToken", Data("refresh-token-value".utf8))
    guard let attrs = inspect(account: "refreshToken") else {
        log("Keychain 裡找不到 refreshToken：要用 SecItemAdd 存進去")
        return
    }
    let readableWhenLocked = attrs.accessible.hasPrefix("AfterFirstUnlock")
    log("token 的 accessibility：\(attrs.accessible)")
    log("手機鎖定時，背景處理推播讀得到 token：\(readableWhenLocked ? "是" : "否")")
    log("會同步到其他裝置（iCloud 鑰匙圈）：\(attrs.synchronizable ? "是" : "否")")
    delete(account: "refreshToken")
}

// MARK: - Bug 版
private enum L02Bug {
    static func save(account: String, token: Data) {
        // TODO ①：組出 Keychain 項目（kSecClass 是 kSecClassGenericPassword，kSecAttrService 是 "tw.harry.labs.platform04.l02"，kSecAttrAccount 是 account，kSecValueData 是 token），
        //        另外加上 kSecAttrSynchronizable: true（同步到其他裝置），不要指定 kSecAttrAccessible，再呼叫 SecItemAdd
    }

    static func run(_ log: LabLog) async {
        scenario(log, save: save)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func save(account: String, token: Data) {
        // TODO ②：同樣的項目（kSecClass、kSecAttrService、kSecAttrAccount、kSecValueData），加上 kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly，
        //        不要同步，再呼叫 SecItemAdd
    }

    static func run(_ log: LabLog) async {
        scenario(log, save: save)
    }
}

// MARK: - 演練設定
extension Platform04SecureStorage {
    static let l02 = Lab(
        id: "L02",
        title: "鎖定時讀不到 token，還同步到別的裝置",
        question: "第 2 題：iOS 的 Keychain 怎麼用？accessibility 怎麼選？",
        goal: "背景要讀的 token 要選 AfterFirstUnlock，又不該進備份或同步，所以是 AfterFirstUnlockThisDeviceOnly；不要開 kSecAttrSynchronizable。先寫出鎖定時讀不到、還會同步的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform04-SecureStorage/L02_KeychainAccessible.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看 token 的 accessibility 是什麼、鎖定時讀不讀得到、有沒有同步。",
            "寫 TODO ②（修正版），選「修正版」執行：AfterFirstUnlockThisDeviceOnly、鎖定時讀得到、不同步。",
            "想一想：為什麼背景處理推播時，手機可能是鎖定的？（推播可以在使用者還沒看手機時到達，iOS 會在背景喚醒 App）",
        ],
        expectBug: "accessibility：WhenUnlocked；手機鎖定時讀不到；會同步到其他裝置：是。",
        expectFixed: "accessibility：AfterFirstUnlockThisDeviceOnly；手機鎖定時讀得到；會同步到其他裝置：否。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if lines.contains(where: { $0.hasPrefix("Keychain 裡找不到") }) { return "Keychain 裡找不到 refreshToken：save 要用 SecItemAdd 存進去" }
            if !lines.contains("token 的 accessibility：WhenUnlocked") { return "Bug 還沒重現：Bug 版不要指定 kSecAttrAccessible（預設是 WhenUnlocked）" }
            if !lines.contains("會同步到其他裝置（iCloud 鑰匙圈）：是") { return "Bug 還沒重現：Bug 版要加上 kSecAttrSynchronizable: true" }
            return nil
        },
        checkFixed: { lines in
            if lines.contains(where: { $0.hasPrefix("Keychain 裡找不到") }) { return "Keychain 裡找不到 refreshToken：save 要用 SecItemAdd 存進去" }
            if !lines.contains("token 的 accessibility：AfterFirstUnlockThisDeviceOnly") { return "accessibility 要指定 kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly" }
            if !lines.contains("會同步到其他裝置（iCloud 鑰匙圈）：否") { return "不要開 kSecAttrSynchronizable：交易所的 session 與金鑰不該同步到其他裝置" }
            return nil
        }
    )
}
