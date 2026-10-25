// L03：伺服器換金鑰那天，舊版 App 全部連不上（釘選至少要放兩個 pin）
// 對應複習站：Platform05Networking 第 3 題「伺服器的憑證要換的時候，
// 釘選怎麼輪替才不會讓使用者連不上？」
//
// 情境：App 3.19 上架時內建 pin。維運計畫在第 31 天，把伺服器換成預先產生、
// 一直離線保管的備援金鑰 B。
//       （演練用金鑰 B 的憑證來算 pin；真實做法是直接用備援公鑰算，
// 不用等憑證。）
// Bug 版：pin 清單只放目前用的金鑰 A。伺服器換成金鑰 B 的那天，舊版 App 的 pin
// 全部對不上，所有人連不上，
//        唯一的解法是發新版、等使用者更新，這段時間不能下單。
// 修正版：pin 清單放兩個：目前的金鑰 A，以及備援金鑰 B。換金鑰之後 B 的 pin
// 對得上；攻擊者的金鑰 X 仍然被擋下。
import Foundation
import LabKit

/// 怎麼從憑證算 pin（兩個版本共用）：公鑰的 SHA-256，和 L02 修正版一樣
private func pin(of certificate: Data) -> String {
    P05Pinning.sha256Base64(P05Pinning.spki(of: certificate))
}

/// 演練流程（兩個版本共用）：App 內建 shipped 這份 pin 清單，
/// 之後伺服器依序換憑證
private func scenario(_ log: LabLog, shipped: [String]) {
    log("App 內建的 pin 數量：\(shipped.count)")

    func connect(_ name: String, _ certificate: Data) {
        let trusted = P05Pinning.isTrusted(
            chain: [certificate], pins: shipped, pinOf: pin(of:))
        log("\(name)：\(trusted ? "連得上" : "連不上（拒絕連線）")")
    }
    connect("第 1 天，伺服器用金鑰 A", P05Certs.a1)
    connect("第 31 天，伺服器換成備援金鑰 B", P05Certs.b)
    connect("攻擊者的憑證（金鑰 X）", P05Certs.attacker)
}

// MARK: - Bug 版
private enum L03Bug {
    static func shippedPins() -> [String] {
        // 只放目前正在用的金鑰 A
        [pin(of: P05Certs.a1)]
    }

    static func run(_ log: LabLog) async {
        scenario(log, shipped: shippedPins())
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func shippedPins() -> [String] {
        // 目前的金鑰 A，加上預先產生的備援金鑰 B
        [pin(of: P05Certs.a1), pin(of: P05Certs.b)]
    }

    static func run(_ log: LabLog) async {
        scenario(log, shipped: shippedPins())
    }
}

// MARK: - 演練設定
extension Platform05Networking {
    static let l03 = Lab(
        id: "L03",
        title: "伺服器換金鑰那天，舊版 App 全部連不上",
        question: "第 3 題：伺服器的憑證要換的時候，釘選怎麼輪替才不會讓使用者連不上？",
        goal: "伺服器換成備援金鑰之後，已經上架的舊版 App 仍然連得上，攻擊者的金鑰仍然被擋下。pin 清單要放哪些金鑰？",
        steps: [
            "選「Bug 版」執行，看「第 31 天，伺服器換成備援金鑰 B」連不連得上。",
            "想一想：這時候已經上架的 App 3.19 還能改嗎？要多久才能讓使用者更新？",
            "選「修正版」執行，看換金鑰之後連得上、攻擊者仍然連不上。",
            "進階：備援金鑰要離線保管、不要放在伺服器上。第 32 天 App 3.21 上架時，pin 清單變成 [B, 新的備援 C]。",
        ],
        expectBug:
            "「App 內建的 pin 數量：1」；「第 1 天，伺服器用金鑰 A：連得上」；「第 31 天，伺服器換成備援金鑰 B：連不上（拒絕連線）」。",
        expectFixed:
            "「App 內建的 pin 數量：2」；第 1 天、第 31 天都「連得上」；「攻擊者的憑證（金鑰 X）：連不上（拒絕連線）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("App 內建的 pin 數量：1") {
                return "Bug 版只放目前用的金鑰 A 一個 pin：shippedPins() 回傳 [pin(of: a1)]"
            }
            if !lines.contains("第 1 天，伺服器用金鑰 A：連得上") {
                return "金鑰 A 的 pin 要放進清單，第 1 天才連得上"
            }
            return lines.contains("第 31 天，伺服器換成備援金鑰 B：連不上（拒絕連線）")
                ? nil : "Bug 還沒重現：清單裡沒有 B 的 pin，換金鑰後應該連不上"
        },
        checkFixed: { lines in
            if !lines.contains("App 內建的 pin 數量：2") {
                return "清單要放兩個 pin：目前的金鑰 A，以及備援金鑰 B"
            }
            if !lines.contains("第 1 天，伺服器用金鑰 A：連得上")
                || !lines.contains("第 31 天，伺服器換成備援金鑰 B：連得上")
            {
                return "金鑰 A、備援金鑰 B 的 pin 都要放進清單，換金鑰前後都要連得上"
            }
            return lines.contains("攻擊者的憑證（金鑰 X）：連不上（拒絕連線）")
                ? nil : "攻擊者的金鑰 X 不在清單裡，應該被擋下"
        }
    )
}
