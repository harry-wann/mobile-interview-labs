// L03：把提醒改成 11,000,000，伺服器卻還在用舊的門檻（兩次修改的請求亂序到達，
// 沒有版本號）
// 對應複習站：Design04PriceAlerts 第 2 題「為什麼價格的判斷一定要放在伺服器？」
// （App 把條件送給伺服器，兩邊的條件要一致）
//
// 情境：伺服器上的提醒 a1：BTC 高於 10,000,000 円（版本 0）。
// 使用者連續改了兩次門檻：先改成 10,500,000（修改 1），不到一秒又改成 11,000,
// 000（修改 2）。
//       兩個請求走不同的路徑：修改 1 的第一次嘗試逾時、重試過，
// 所以伺服器先收到修改 2，後收到修改 1。
//       之後價格漲到 10,700,000 円：使用者最後設的是 11,000,000，
// 這個價格不該響。
// Bug 版：伺服器收到什麼就蓋什麼。修改 2 先到（門檻 11,000,000），修改 1
// 後到又蓋掉它（門檻變 10,500,000）。
//        價格 10,700,000 時提醒響了，但使用者最後設的明明是 11,000,000。
// 修正版：App 每次修改，把這個提醒的版本號加 1，一起送出；
// 伺服器只接受版本號比目前大的修改，舊的修改（版本號小於或等於目前的）
// 直接忽略。
//        修改 1（版本 1）晚到時，目前已經是版本 2，被忽略；門檻維持 11,000,
// 000，價格 10,700,000 不會響。
import LabKit

private struct Update {
    let threshold: Int
    let version: Int
}

/// App 端：使用者改了門檻，產生要送出的修改
private protocol AlertClient: AnyObject {
    func makeUpdate(threshold: Int) -> Update
}

/// 伺服器端：收到修改，更新自己存的門檻
private protocol AlertServer: AnyObject {
    func receive(_ update: Update)
    var threshold: Int { get }
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, client: AlertClient, server: AlertServer) {
    log("伺服器上的提醒 a1：BTC 高於 \(d04Yen(server.threshold)) 円（版本 0）")
    let first = client.makeUpdate(threshold: 10_500_000)
    log("使用者把門檻改成 10,500,000 → 修改 1（版本 \(first.version)）")
    let second = client.makeUpdate(threshold: 11_000_000)
    log("使用者又改成 11,000,000 → 修改 2（版本 \(second.version)）")
    server.receive(second)
    log("伺服器先收到修改 2 → 門檻 \(d04Yen(server.threshold))")
    server.receive(first)
    log("修改 1 重試之後才到 → 門檻 \(d04Yen(server.threshold))")
    let fires = 10_700_000 >= server.threshold
    log("價格漲到 10,700,000 円 → 提醒\(fires ? "響了" : "沒有響")")
    log(
        "最後：伺服器的門檻是 \(d04Yen(server.threshold))（使用者最後設的是 11,000,000），價格 10,700,000 \(fires ? "觸發了提醒" : "沒有觸發提醒")"
    )
}

// MARK: - Bug 版
private enum L03Bug {
    final class Client: AlertClient {
        func makeUpdate(threshold: Int) -> Update {
            // 沒有版本號
            Update(threshold: threshold, version: 0)
        }
    }

    final class Server: AlertServer {
        private(set) var threshold = 10_000_000

        func receive(_ update: Update) {
            // 收到什麼就蓋什麼
            threshold = update.threshold
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, client: Client(), server: Server())
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class Client: AlertClient {
        private var version = 0

        func makeUpdate(threshold: Int) -> Update {
            // 每次修改，版本號加 1
            version += 1
            return Update(threshold: threshold, version: version)
        }
    }

    final class Server: AlertServer {
        private(set) var threshold = 10_000_000
        private var version = 0

        func receive(_ update: Update) {
            // 版本號不比目前大：舊的修改，忽略
            guard update.version > version else { return }
            version = update.version
            threshold = update.threshold
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, client: Client(), server: Server())
    }
}

// MARK: - 演練設定
extension Design04PriceAlerts {
    static let l03 = Lab(
        id: "L03",
        title: "把提醒改成 1100 萬，伺服器卻還用舊的門檻",
        question: "第 2 題：為什麼價格的判斷一定要放在伺服器？",
        goal:
            "判斷既然在伺服器，App 送上去的條件就要和使用者最後設的一致。請求可能亂序、可能重試，所以每次修改帶一個遞增的版本號，伺服器只接受比目前新的，舊的忽略。",
        steps: [
            "選「Bug 版」執行，看伺服器先後收到兩個修改之後，門檻是多少、價格 10,700,000 時提醒響不響。",
            "想一想：使用者最後設的是 11,000,000，伺服器為什麼變成 10,500,000？（後到的舊修改蓋掉了新的。）",
            "選「修正版」執行：修改 1 晚到，因為版本號比較小而被忽略，門檻維持 11,000,000，提醒不響。",
            "想一想：為什麼不用手機的時間（updatedAt）當版本？（手機時間可能不準，見 Exchange04 演練 6；遞增的版本號不依賴時鐘。）",
        ],
        expectBug:
            "修改 1 晚到又蓋掉修改 2，門檻變成 10,500,000，價格 10,700,000 觸發了提醒：「最後：伺服器的門檻是 10,500,000（使用者最後設的是 11,000,000），價格 10,700,000 觸發了提醒」。",
        expectFixed:
            "修改 1（版本 1）因為比目前的版本 2 舊而被忽略：「最後：伺服器的門檻是 11,000,000（使用者最後設的是 11,000,000），價格 10,700,000 沒有觸發提醒」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, client:, server:) 執行"
            }
            return lines.contains(
                "最後：伺服器的門檻是 10,500,000（使用者最後設的是 11,000,000），價格 10,700,000 觸發了提醒"
            )
                ? nil
                : "Bug 還沒重現：伺服器的 receive 收到什麼就把 threshold 蓋成什麼（不看版本號），後到的修改 1 會蓋掉修改 2"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, client:, server:) 執行"
            }
            return lines.contains(
                "最後：伺服器的門檻是 11,000,000（使用者最後設的是 11,000,000），價格 10,700,000 沒有觸發提醒"
            )
                ? nil
                : "Client 每次 makeUpdate 先把 version 加 1；Server 的 receive 只接受 update.version > version 的修改，並同時更新 version 和 threshold"
        }
    )
}
