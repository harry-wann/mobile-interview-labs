// L05：把既有的私鑰「搬進」Secure Enclave，App 直接當掉（Secure Enclave
// 的金鑰只能在裡面產生）
// 對應複習站：Platform04SecureStorage 第 4 題「Secure Enclave 和 StrongBox
// 差在哪？」
//
// 情境：App 原本把一把 P-256 簽章金鑰存在軟體裡（P256.Signing.PrivateKey）。
// 為了安全，想把它「搬進」Secure Enclave。
//        Secure Enclave 是蘋果裝置內的專用安全處理器，私鑰永遠不離開晶片，App
// 只能請它簽章。
// Bug 版：用 SecureEnclave.P256.Signing.PrivateKey(dataRepresentation:)，
// 把既有金鑰的內容丟進去，再用 try! 當成一定成功。
//        這個初始化器要的是「Secure Enclave 自己產生、加密過」的資料，
// 傳進一般金鑰的內容會丟出錯誤，try! 讓 App 當掉。
// 修正版：金鑰一定要在 Secure Enclave 裡面產生（SecureEnclave.P256.Signing.
// PrivateKey()），再把公鑰傳給伺服器登記；
//        用之前先檢查 SecureEnclave.isAvailable，沒有的裝置降級成軟體金鑰。
// 演練驗證：用公鑰可以驗證 Secure Enclave 簽出來的簽章。
//        另外記住：Secure Enclave 的曲線是 P-256，不支援區塊鏈常用的
// secp256k1，所以不能直接拿來簽比特幣、以太坊的交易。
import CryptoKit
import Foundation
import LabKit

// MARK: - Bug 版
private enum L05Bug {
    static func run(_ log: LabLog) async {
        // App 原本存在軟體裡的金鑰
        let existing = P256.Signing.PrivateKey()
        log("要把既有的私鑰搬進 Secure Enclave……")
        // 沒有匯入的做法：這裡會當掉
        let imported = try! SecureEnclave.P256.Signing.PrivateKey(
            dataRepresentation: existing.rawRepresentation)
        log("匯入成功：\(imported.publicKey.rawRepresentation.count) bytes 的公鑰")
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func run(_ log: LabLog) async {
        let challenge = Data("challenge-from-server".utf8)
        let publicKey: P256.Signing.PublicKey
        let signature: P256.Signing.ECDSASignature
        do {
            if SecureEnclave.isAvailable {
                // 在 Secure Enclave 裡面產生，私鑰不會出來
                let key = try SecureEnclave.P256.Signing.PrivateKey()
                publicKey = key.publicKey
                // 簽章在晶片內完成
                signature = try key.signature(for: challenge)
                log("簽章金鑰：在 Secure Enclave 裡產生")
            } else {
                // 沒有 Secure Enclave 的裝置：降級成軟體金鑰（要存 Keychain）
                let key = P256.Signing.PrivateKey()
                publicKey = key.publicKey
                signature = try key.signature(for: challenge)
                log("簽章金鑰：這台裝置沒有 Secure Enclave，降級成軟體金鑰")
            }
        } catch {
            log("產生金鑰失敗：\(error)")
            return
        }
        log("簽章演算法：P-256（不是區塊鏈常用的 secp256k1）")
        log(
            "伺服器用登記過的公鑰驗證簽章：\(publicKey.isValidSignature(signature, for: challenge) ? "成功" : "失敗")"
        )
    }
}

// MARK: - 演練設定
extension Platform04SecureStorage {
    static let l05 = Lab(
        id: "L05",
        title: "把既有私鑰搬進 Secure Enclave，App 當掉",
        question: "第 4 題：Secure Enclave 和 StrongBox 差在哪？",
        goal:
            "Secure Enclave 的私鑰只能在裡面產生，不能匯入。要用的話，在裡面產生新金鑰、登記公鑰，並為沒有 Secure Enclave 的裝置準備降級做法。",
        steps: [
            "寫好 Bug 版，從 Xcode 執行（⌘R），才看得到當掉的位置。選「Bug 版」執行：App 在 try! 那一行當掉，主控台有 CryptoTokenKit 的錯誤。",
            "選「修正版」執行：簽章金鑰在 Secure Enclave 裡產生（沒有的裝置會降級），伺服器用公鑰驗證簽章成功。",
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
