// L05：把既有的私鑰「搬進」Secure Enclave，App 直接當掉（Secure Enclave
// 的金鑰只能在裡面產生）
// 對應複習站：Platform04SecureStorage 第 4 題「Secure Enclave 和 StrongBox
// 差在哪？」
//
// 情境：App 原本把一把 P-256 簽章金鑰存在軟體裡（P256.Signing.PrivateKey）。
// 為了安全，想把它「搬進」Secure Enclave。
//        Secure Enclave 是蘋果裝置內的專用安全處理器，私鑰永遠不離開晶片，App
// 只能請它簽章。
//
// 你要寫：
//   ① Bug 版：用 SecureEnclave.P256.Signing.PrivateKey(dataRepresentation:)
// 想把既有金鑰匯進去，加 try!。App 會當掉。
//   ② 修正版：在 Secure Enclave 裡產生金鑰（沒有的裝置降級成軟體金鑰），簽章，
// 並用公鑰驗證。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform04-SecureStorage/L05_SecureEnclaveImport.
// swift
import CryptoKit
import Foundation
import LabKit

// MARK: - Bug 版
private enum L05Bug {
    static func run(_ log: LabLog) async {
        // App 原本存在軟體裡的金鑰
        let existing = P256.Signing.PrivateKey()
        log("要把既有的私鑰搬進 Secure Enclave……")
        // TODO ①：用 try! SecureEnclave.P256.Signing.
        // PrivateKey(dataRepresentation: existing.rawRepresentation)
        // 匯入既有金鑰（existing 的 rawRepresentation 是一般金鑰的內容，不是
        // Secure Enclave 產生的資料，會丟出錯誤，try! 讓 App 當掉），
        //        成功的話 log("匯入成功")
        _ = existing
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func run(_ log: LabLog) async {
        let challenge = Data("challenge-from-server".utf8)
        // TODO ②-1：先檢查 SecureEnclave.isAvailable。可以的話，用
        // SecureEnclave.P256.Signing.PrivateKey() 在裡面產生金鑰，
        //          對 challenge 簽章（key.signature(for:)），log("簽章金鑰：在
        // Secure Enclave 裡產生")；
        //          不行的話降級成 P256.Signing.PrivateKey()，log("簽章金鑰：
        // 這台裝置沒有 Secure Enclave，降級成軟體金鑰")
        // TODO ②-2：log("簽章演算法：P-256（不是區塊鏈常用的 secp256k1）")
        // TODO ②-3：用金鑰的 publicKey.isValidSignature(簽章, for: challenge)
        // 驗證，log("伺服器用登記過的公鑰驗證簽章：成功")（失敗就寫「失敗」）
        _ = challenge
    }
}

// MARK: - 演練設定
extension Platform04SecureStorage {
    static let l05 = Lab(
        id: "L05",
        title: "把既有私鑰搬進 Secure Enclave，App 當掉",
        question: "第 4 題：Secure Enclave 和 StrongBox 差在哪？",
        goal:
            "Secure Enclave 的私鑰只能在裡面產生，不能匯入。要用的話，在裡面產生新金鑰、登記公鑰，並為沒有 Secure Enclave 的裝置準備降級做法。先寫出會當掉的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform04-SecureStorage/L05_SecureEnclaveImport.swift，寫 TODO ①（Bug 版）。從 Xcode 執行（⌘R）才看得到當掉的位置。",
            "選「練習」「Bug 版」執行：App 在 try! 那一行當掉，主控台有 CryptoTokenKit 的錯誤。按 ■ 停止。",
            "寫 TODO ②（修正版），選「修正版」執行：簽章金鑰在 Secure Enclave 裡產生（沒有的裝置會降級），伺服器用公鑰驗證簽章成功。",
            "想一想：Secure Enclave 能不能拿來簽比特幣的交易？（不能：它只支援 P-256，而且不能匯入既有的 secp256k1 私鑰）",
        ],
        expectBug:
            "log 印到「要把既有的私鑰搬進 Secure Enclave……」之後 App 當掉：try! 收到 CryptoTokenKit 的錯誤（corrupted objectID detected）。",
        expectFixed:
            "簽章金鑰：在 Secure Enclave 裡產生（實測 iOS 26.3 的模擬器也有 Secure Enclave，也能用）；簽章演算法 P-256；伺服器驗證簽章成功。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { _ in
            // 寫對的話 App 會當掉，不會走到這裡
            "App 沒有當掉：要用 try! SecureEnclave.P256.Signing.PrivateKey(dataRepresentation:) 匯入既有金鑰的 rawRepresentation"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("簽章金鑰：") }) {
                return
                    "還沒有產生金鑰：用 SecureEnclave.isAvailable 判斷，可以的話用 SecureEnclave.P256.Signing.PrivateKey() 在裡面產生"
            }
            if !lines.contains("簽章演算法：P-256（不是區塊鏈常用的 secp256k1）") {
                return "演練沒有跑完"
            }
            if !lines.contains("伺服器用登記過的公鑰驗證簽章：成功") {
                return
                    "公鑰驗證不了簽章：用同一把金鑰的 publicKey 驗證 key.signature(for: challenge)"
            }
            return nil
        }
    )
}
