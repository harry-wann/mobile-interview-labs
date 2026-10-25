// L03：重新連線之後，成交明細多了幾筆重複的，24 小時成交量也算多了（不能丟的資料還要防重複）
// 對應複習站：Advanced05HighFrequency 第 4 題「哪些資料可以丟、哪些絕對不能丟？」
//
// 情境：成交明細（每一筆成交都是獨立的事件）不能合併、不能丟，但連線斷了再接上，伺服器會把最近幾筆再送一次，同一筆成交就會收到兩次。
//        收到的順序：成交 #1（0.5 BTC）、#2（0.2）、#3（0.1），斷線重連之後，重送 #2、#3，再來新的 #4（0.3）。
//        每一筆成交有唯一的 ID（冪等鍵：同一個 ID 不管送幾次，結果都跟送一次一樣）。
// Bug 版：每收到一筆就 append 進成交明細，並加進成交量：#2、#3 出現兩次，成交量多算了 0.3。
// 修正版：記下已經處理過的成交 ID（Set）。收到 ID 已經在裡面，就跳過；沒有才 append、加成交量。
import Foundation
import LabKit

private struct Trade {
    let id: Int
    let quantity: Decimal      // BTC
}

private let received: [Trade] = [
    Trade(id: 1, quantity: 0.5), Trade(id: 2, quantity: 0.2), Trade(id: 3, quantity: 0.1),
    Trade(id: 2, quantity: 0.2), Trade(id: 3, quantity: 0.1),            // 重連之後，伺服器重送最近兩筆
    Trade(id: 4, quantity: 0.3),
]

/// 成交明細的共同介面（兩個版本共用）
private protocol TradeTape: AnyObject {
    func receive(_ trade: Trade)
    var rows: [Int] { get }            // 畫面上的成交明細，每一列是成交 ID
    var volume: Decimal { get }        // 累計成交量
}

private func scenario(_ log: LabLog, tape: some TradeTape) {
    for trade in received { tape.receive(trade) }
    log("成交明細：\(tape.rows.map(String.init).joined(separator: "、"))（共 \(tape.rows.count) 筆）")
    log("累計成交量：\(tape.volume) BTC")
}

// MARK: - Bug 版
private enum L03Bug {
    final class Tape: TradeTape {
        private(set) var rows: [Int] = []
        private(set) var volume: Decimal = 0

        func receive(_ trade: Trade) {
            rows.append(trade.id)                                 // 收到就加，不管是不是已經收過
            volume += trade.quantity
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, tape: Tape())
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class Tape: TradeTape {
        private(set) var rows: [Int] = []
        private(set) var volume: Decimal = 0
        private var seen = Set<Int>()                             // 已經處理過的成交 ID

        func receive(_ trade: Trade) {
            guard seen.insert(trade.id).inserted else { return }  // ID 已經在裡面：重送的，跳過
            rows.append(trade.id)
            volume += trade.quantity
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, tape: Tape())
    }
}

// MARK: - 演練設定
extension Advanced05HighFrequency {
    static let l03 = Lab(
        id: "L03",
        title: "重新連線之後，成交明細多了重複的，成交量算多了",
        question: "第 4 題：哪些資料可以丟、哪些絕對不能丟？",
        goal: "成交明細這類「每一筆都是獨立事件」的資料不能丟，但要用唯一的 ID 去重：重送的同一筆只算一次。",
        steps: [
            "選「Bug 版」執行，看兩行 log：成交明細有 6 筆，#2、#3 出現兩次；累計成交量 1.4 BTC，比實際多了 0.3。",
            "選「修正版」執行：成交明細 1、2、3、4 共 4 筆，累計成交量 1.1 BTC。",
            "想一想：訂單簿的增量更新（delta）不能丟，要檢查序號；成交明細不能丟，要用 ID 去重；最新價可以只留最新的。三種資料的處理為什麼不同？（見這個單元的第 4 題）",
        ],
        expectBug: "成交明細：1、2、3、2、3、4（共 6 筆）；累計成交量：1.4 BTC。",
        expectFixed: "成交明細：1、2、3、4（共 4 筆）；累計成交量：1.1 BTC。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("成交明細：1、2、3、2、3、4（共 6 筆）") { return "Bug 版收到的每一筆都要 append（包含重送的），成交明細會有 6 筆" }
            return lines.contains("累計成交量：1.4 BTC") ? nil : "Bug 版重送的也要加進成交量：1.4 BTC"
        },
        checkFixed: { lines in
            if !lines.contains("成交明細：1、2、3、4（共 4 筆）") { return "重送的 #2、#3 要跳過：用 Set 記下處理過的 ID，insert 回傳 inserted == false 就跳過；其他筆不能丟" }
            return lines.contains("累計成交量：1.1 BTC") ? nil : "成交量只能算每筆一次：1.1 BTC"
        }
    )
}
