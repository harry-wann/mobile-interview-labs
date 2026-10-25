// L06：BTC/JPY 的行情頁混進別的交易對，5 分鐘 K 線混進 15 分鐘的（用字串比對
// topic）
// 對應複習站：Exchange03WebSocket 第 6
// 題「收到訊息後怎麼分派？斷線重連後要做什麼？」
//
// 情境：收到推送後，要依 ch（topic）決定交給哪一個畫面。兩個畫面：行情頁要
// market.btcjpy.bbo，K 線頁要 market.btcjpy.kline.5min。
//       連線上同時有這幾個 topic 的推送（交易對 wbtcjpy 是演練假設的，
// 不代表交易所有上架）：
//       market.btcjpy.bbo、market.wbtcjpy.bbo、market.btcjpy.kline.5min、
// market.btcjpy.kline.15min、market.wbtcjpy.kline.5min、market.btcjpy.depth.
// step0
// Bug 版：用字串包含來判斷：topic 含有 "btcjpy" 而且含有 "bbo"（或含有 "kline"
// 而且含有 "5min"）。
//        "wbtcjpy" 也含有 "btcjpy"，"15min" 也含有 "5min"，
// 別的交易對和別的週期的資料混進了畫面。
// 修正版：把 topic 用 "." 拆成「market、交易對、種類、參數」，
// 每一段都用完全相等來比對。
import Foundation
import LabKit

private let incoming = [
    "market.btcjpy.bbo",
    "market.wbtcjpy.bbo",
    "market.btcjpy.kline.5min",
    "market.btcjpy.kline.15min",
    "market.wbtcjpy.kline.5min",
    "market.btcjpy.depth.step0",
]

/// 演練流程（兩個版本共用）：每個推送問兩個畫面「是不是給你的」，統計誤收的
private func scenario(
    _ log: LabLog,
    matches: (String, _ symbol: String, _ kind: String, _ param: String?) ->
        Bool
) {
    var tickerAll = 0, tickerWrong = 0, klineAll = 0, klineWrong = 0
    for topic in incoming {
        if matches(topic, "btcjpy", "bbo", nil) {
            tickerAll += 1
            let wrong = topic != "market.btcjpy.bbo"
            if wrong { tickerWrong += 1 }
            log("行情頁收下 \(topic)\(wrong ? "  ← 這不是 BTC/JPY 的最佳買賣價" : "")")
        }
        if matches(topic, "btcjpy", "kline", "5min") {
            klineAll += 1
            let wrong = topic != "market.btcjpy.kline.5min"
            if wrong { klineWrong += 1 }
            log("K 線頁收下 \(topic)\(wrong ? "  ← 這不是 BTC/JPY 的 5 分鐘 K 線" : "")")
        }
    }
    log(
        "行情頁收到 \(tickerAll) 則（錯的 \(tickerWrong) 則），K 線頁收到 \(klineAll) 則（錯的 \(klineWrong) 則）"
    )
}

// MARK: - Bug 版
private enum L06Bug {
    static func matches(
        _ topic: String, symbol: String, kind: String, param: String?
    ) -> Bool {
        // 用「包含」比對
        guard topic.contains(symbol), topic.contains(kind) else { return false }
        if let param { return topic.contains(param) }
        return true
    }

    static func run(_ log: LabLog) async {
        scenario(log) { matches($0, symbol: $1, kind: $2, param: $3) }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func matches(
        _ topic: String, symbol: String, kind: String, param: String?
    ) -> Bool {
        // market.btcjpy.kline.5min → [market, btcjpy, kline, 5min]
        let parts = topic.split(separator: ".").map(String.init)
        guard parts.count >= 3, parts[0] == "market", parts[1] == symbol,
            parts[2] == kind
        else { return false }
        if let param { return parts.count == 4 && parts[3] == param }
        return parts.count == 3
    }

    static func run(_ log: LabLog) async {
        scenario(log) { matches($0, symbol: $1, kind: $2, param: $3) }
    }
}

// MARK: - 演練設定
extension Exchange03WebSocket {
    static let l06 = Lab(
        id: "L06",
        title: "行情頁混進別的交易對，K 線混進別的週期",
        question: "第 6 題：收到訊息後怎麼分派？斷線重連後要做什麼？",
        goal: "依 topic 分派推送時，要把 topic 拆成交易對、種類、參數，每一段完全相等才算是給這個畫面的。",
        steps: [
            "選「Bug 版」執行，看兩個畫面各多收了哪些不該收的 topic。",
            "想一想：\"wbtcjpy\" 含有 \"btcjpy\" 嗎？\"15min\" 含有 \"5min\" 嗎？",
            "選「修正版」執行，兩個畫面都只收到自己的那一則。",
            "進階：不認得的 topic 要容錯，忽略就好；分派的順序是心跳、錯誤、確認、推送、忽略（見演練 4）。",
        ],
        expectBug:
            "「行情頁收到 2 則（錯的 1 則），K 線頁收到 3 則（錯的 2 則）」：wbtcjpy 的 BBO 混進行情頁，15min 與 wbtcjpy 的 K 線混進 5 分鐘 K 線頁。",
        expectFixed: "「行情頁收到 1 則（錯的 0 則），K 線頁收到 1 則（錯的 0 則）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("行情頁收到") }) {
                return "演練沒有跑完：要用 scenario(log, matches:) 執行"
            }
            return lines.contains("行情頁收到 2 則（錯的 1 則），K 線頁收到 3 則（錯的 2 則）")
                ? nil
                : "Bug 還沒重現：用 topic.contains(symbol)、contains(kind)、contains(param) 比對，wbtcjpy 與 15min 都會被放進來"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("行情頁收到") }) {
                return "演練沒有跑完：要用 scenario(log, matches:) 執行"
            }
            return lines.contains("行情頁收到 1 則（錯的 0 則），K 線頁收到 1 則（錯的 0 則）")
                ? nil
                : "把 topic 用 \".\" 拆開，交易對、種類、參數各自用 == 比對（K 線有 4 段，BBO 有 3 段）"
        }
    )
}
