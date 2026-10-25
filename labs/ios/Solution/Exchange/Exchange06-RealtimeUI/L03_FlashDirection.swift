// L03：數字明明是漲的，閃爍卻是下跌的顏色（閃爍方向和「上一則推播」比，不是和「畫面上的價格」比）
// 對應複習站：Exchange06RealtimeUI 第 5 題「價格變動的閃爍提示怎麼做？」
//
// 情境：行情推播會先合併，畫面每隔一段時間才更新一次，中間的價格使用者根本沒看過。
//       推播依序是：100 → 畫面更新 → 105、103 → 畫面更新 → 103 → 畫面更新。
//       使用者看到的是 100、103、103：第二次畫面更新，數字從 100 變成 103，是上漲。
//       （第一次顯示沒有「上次畫在畫面上的價格」，不閃爍。）
// Bug 版：refresh 時，拿「上一則推播」（105）和要顯示的價格（103）比：103 比 105 低，閃出下跌的顏色；但畫面上的數字明明是 100 變成 103。
// 修正版：記下「上次畫在畫面上的價格」（lastShown），refresh 時拿它和要顯示的價格比，比完把 lastShown 換成這次顯示的價格。
import Foundation
import LabKit

private enum Direction: String {
    case up = "▲ 上漲"
    case down = "▼ 下跌"
    case flat = "－ 持平"
}

/// old 是「拿來比較的舊價格」，第一次顯示時沒有舊值
private func direction(old: Decimal?, new: Decimal) -> Direction {
    guard let old else { return .flat }
    if new > old { return .up }
    if new < old { return .down }
    return .flat
}

private protocol FlashModel: AnyObject {
    /// 每一則推播到達（背景）
    func onPush(_ price: Decimal)
    /// 畫面更新：回傳要顯示的價格與閃爍方向；還沒有任何推播時回傳 nil
    func refresh() -> (shown: Decimal, direction: Direction)?
}

/// 演練流程（兩個版本共用）：推播與畫面更新交錯
private func scenario(_ log: LabLog, model: FlashModel) {
    var directions: [String] = []
    var shownPrices: [String] = []
    func refresh() {
        guard let result = model.refresh() else { return }
        directions.append(result.direction.rawValue)
        shownPrices.append("\(result.shown)")
        log("畫面更新：顯示 \(result.shown)，\(result.direction.rawValue)")
    }
    model.onPush(e06("100")); refresh()
    model.onPush(e06("105")); model.onPush(e06("103")); refresh()
    model.onPush(e06("103")); refresh()
    log("最後：三次畫面更新的閃爍方向：\(directions.joined(separator: "、"))（使用者看到的價格：\(shownPrices.joined(separator: " → "))）")
}

// MARK: - Bug 版
private enum L03Bug {
    final class Model: FlashModel {
        private var latest: Decimal?
        private var previousPush: Decimal?              // 上一則推播的價格

        func onPush(_ price: Decimal) {
            previousPush = latest
            latest = price
        }

        func refresh() -> (shown: Decimal, direction: Direction)? {
            guard let latest else { return nil }
            return (latest, direction(old: previousPush, new: latest))      // 和「上一則推播」比
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, model: Model())
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class Model: FlashModel {
        private var latest: Decimal?
        private var lastShown: Decimal?                 // 上次畫在畫面上的價格

        func onPush(_ price: Decimal) {
            latest = price
        }

        func refresh() -> (shown: Decimal, direction: Direction)? {
            guard let latest else { return nil }
            let result = direction(old: lastShown, new: latest)             // 和「上次畫在畫面上的價格」比
            lastShown = latest
            return (latest, result)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, model: Model())
    }
}

// MARK: - 演練設定
extension Exchange06RealtimeUI {
    static let l03 = Lab(
        id: "L03",
        title: "數字漲了，閃爍卻是下跌的顏色",
        question: "第 5 題：價格變動的閃爍提示怎麼做？",
        goal: "閃爍方向要拿「這次要顯示的價格」和「上次畫在畫面上的價格」比，不是和上一則推播比：推播合併之後，中間的價格使用者沒看過。",
        steps: [
            "選「Bug 版」執行，看第二次畫面更新：使用者看到的數字從 100 變成 103，閃的方向對嗎？",
            "想一想：推播依序是 105、103，拿 103 和 105 比，得到什麼？使用者其實沒有看過 105。",
            "選「修正版」執行，三次畫面更新的方向要依序是「持平、上漲、持平」。",
            "進階：閃爍除了顏色，還要加 ▲▼ 符號（色覺異常的使用者），也要尊重系統的「減少動態效果」。",
        ],
        expectBug: "第二次畫面更新顯示 103，卻是「▼ 下跌」：「最後：三次畫面更新的閃爍方向：－ 持平、▼ 下跌、－ 持平」。",
        expectFixed: "「最後：三次畫面更新的閃爍方向：－ 持平、▲ 上漲、－ 持平（使用者看到的價格：100 → 103 → 103）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, model:) 執行" }
            return lines.contains(where: { $0.hasPrefix("最後：三次畫面更新的閃爍方向：－ 持平、▼ 下跌、－ 持平") })
                ? nil : "Bug 還沒重現：refresh 用 previousPush（上一則推播）和 latest 比，第二次會變成 103 比 105，得到下跌"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, model:) 執行" }
            return lines.contains(where: { $0.hasPrefix("最後：三次畫面更新的閃爍方向：－ 持平、▲ 上漲、－ 持平") })
                ? nil : "refresh 要拿 lastShown（上次畫在畫面上的價格）和 latest 比，比完把 lastShown 換成 latest：第一次沒有舊值是持平，第二次 103 比 100 是上漲"
        }
    )
}
