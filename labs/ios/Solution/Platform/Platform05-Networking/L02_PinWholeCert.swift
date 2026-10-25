// L02：憑證自動續期那天，所有使用者都連不上（pin 要釘公鑰，不是整張憑證）
// 對應複習站：Platform05Networking 第 2 題「什麼是憑證釘選（certificate
// pinning）？兩個平台怎麼設定？」
//
// 情境：App 內建伺服器的 pin（憑證的指紋清單）。連線時，App
// 把伺服器送來的憑證算出 pin，不在清單裡就拒絕連線。
//       伺服器的憑證每 90 天自動續期：換成新的一張憑證，但背後的金鑰沒換。
// Bug 版：pin 是「整張憑證檔案」的 SHA-256。續期後憑證檔案變了（序號、
// 有效期限都不同），pin 對不上，所有使用者連不上。
// 修正版：pin 是「公鑰（SPKI）」的 SHA-256，就是 openssl 指令算出來的那個值。
// 續期只換憑證、沒換金鑰，pin 不變；
//        攻擊者的憑證金鑰不同，pin 對不上，仍然被擋下。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：pinOf 決定「怎麼從一張憑證算出 pin」，
/// 發版時和連線時用同一個函式
private func scenario(_ log: LabLog, pinOf: (Data) -> String) {
    // 發版時，用當時伺服器的憑證算出內建的 pin
    let shipped = [pinOf(P05Certs.a1)]
    log("App 內建的 pin：\(shipped[0])")

    func connect(_ name: String, _ certificate: Data) {
        let trusted = P05Pinning.isTrusted(
            chain: [certificate], pins: shipped, pinOf: pinOf)
        log("\(name)：\(trusted ? "連得上" : "連不上（拒絕連線）")")
    }
    connect("續期前的伺服器", P05Certs.a1)
    connect("續期後的伺服器（同一把金鑰、新的憑證）", P05Certs.a2)
    connect("攻擊者的憑證（另一把金鑰）", P05Certs.attacker)
}

// MARK: - Bug 版
private enum L02Bug {
    static func pin(of certificate: Data) -> String {
        // 把整張憑證檔案拿去算 SHA-256：憑證一續期，值就變了
        P05Pinning.sha256Base64(certificate)
    }

    static func run(_ log: LabLog) async {
        scenario(log, pinOf: pin(of:))
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func pin(of certificate: Data) -> String {
        // 只算公鑰（SPKI）的 SHA-256：續期不影響
        P05Pinning.sha256Base64(P05Pinning.spki(of: certificate))
    }

    static func run(_ log: LabLog) async {
        scenario(log, pinOf: pin(of:))
    }
}

// MARK: - 演練設定
extension Platform05Networking {
    static let l02 = Lab(
        id: "L02",
        title: "憑證續期那天，所有使用者都連不上",
        question: "第 2 題：什麼是憑證釘選（certificate pinning）？兩個平台怎麼設定？",
        goal: "伺服器憑證續期（換憑證、不換金鑰）後，App 仍然連得上；攻擊者的憑證（另一把金鑰）仍然被擋下。pin 要算在哪一段資料上？",
        steps: [
            "選「Bug 版」執行，看「續期後的伺服器」連不連得上。",
            "想一想：續期只是換了一張憑證，伺服器背後的金鑰有沒有變？pin 對的是憑證檔案，還是公鑰？",
            "選「修正版」執行，看續期後連得上、攻擊者仍然連不上。",
            "進階：修正版印出的 pin 和 openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64 算出來的一樣（檔案裡的測試憑證是 a1，可以先用 openssl 轉出來比對）。",
        ],
        expectBug:
            "「續期前的伺服器：連得上」「續期後的伺服器（同一把金鑰、新的憑證）：連不上（拒絕連線）」「攻擊者的憑證（另一把金鑰）：連不上（拒絕連線）」。",
        expectFixed:
            "內建的 pin 是 AztowyLM1lqAuk1bhe3RTFIdhN7cDwpBPolu/uILc1A=；續期前、續期後都「連得上」，攻擊者「連不上（拒絕連線）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            let before = lines.contains("續期前的伺服器：連得上")
            let renewed = lines.contains("續期後的伺服器（同一把金鑰、新的憑證）：連不上（拒絕連線）")
            let attacker = lines.contains("攻擊者的憑證（另一把金鑰）：連不上（拒絕連線）")
            if !before {
                return "續期前的伺服器應該連得上：pin(of:) 要算出可以重複比對的值（整張憑證的 SHA-256）"
            }
            if !renewed {
                return "Bug 還沒重現：pin 要算在「整張憑證檔案」上，續期後憑證檔案變了，pin 才會對不上"
            }
            return attacker ? nil : "攻擊者的憑證應該被擋下"
        },
        checkFixed: { lines in
            let before = lines.contains("續期前的伺服器：連得上")
            let renewed = lines.contains("續期後的伺服器（同一把金鑰、新的憑證）：連得上")
            let attacker = lines.contains("攻擊者的憑證（另一把金鑰）：連不上（拒絕連線）")
            if !before || !renewed {
                return
                    "續期後也要連得上：pin 要算在公鑰上，用 P05Pinning.spki(of:) 取出公鑰再算 SHA-256"
            }
            return attacker ? nil : "攻擊者的憑證（另一把金鑰）應該被擋下"
        }
    )
}
