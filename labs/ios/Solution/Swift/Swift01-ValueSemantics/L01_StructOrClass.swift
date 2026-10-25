// L01：按了「取消」，價格卻還是被改掉（struct 和 class 的差別）
// 對應複習站：Swift01ValueSemantics 第 1 題「struct 和 class
// 差在哪？什麼時候用哪個？」
//
// 情境：價格提醒清單，點一筆打開編輯畫面，改了價格之後可以按「取消」
// 或「儲存」。
// Bug 版：提醒用 class 寫。編輯畫面拿到的是「同一個物件」，還沒儲存，
// 清單上的價格就已經變了，按取消也改不回來。
// 修正版：提醒改用 struct。編輯畫面拿到的是自己的一份，按儲存時才寫回清單。
import LabKit

// MARK: - Bug 版
private enum L01Bug {
    /// 價格提醒：用 class 寫
    final class PriceAlert {
        let symbol: String
        var price: Int

        init(symbol: String, price: Int) {
            self.symbol = symbol
            self.price = price
        }
    }

    static func run(_ log: LabLog) async {
        let alerts = [PriceAlert(symbol: "BTC/JPY", price: 15_000_000)]
        log("清單：\(alerts[0].symbol) 提醒價 \(alerts[0].price)")

        // 打開編輯畫面：把清單上的這一筆交給編輯畫面當草稿
        // class：只複製了參考，draft 和 alerts[0] 是同一個物件
        let draft = alerts[0]
        draft.price = 16_000_000
        log("編輯畫面把價格改成 \(draft.price)，還沒按儲存")
        log("這時清單上的價格：\(alerts[0].price)")

        // 使用者按「取消」：編輯畫面把草稿丟掉，不寫回清單
        log("按下取消後，清單上的價格：\(alerts[0].price)")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    /// 價格提醒：改用 struct，複製之後各自獨立
    struct PriceAlert {
        let symbol: String
        var price: Int
    }

    static func run(_ log: LabLog) async {
        var alerts = [PriceAlert(symbol: "BTC/JPY", price: 15_000_000)]
        log("清單：\(alerts[0].symbol) 提醒價 \(alerts[0].price)")

        // struct：複製出一份新的資料，改 draft 不會影響清單
        var draft = alerts[0]
        draft.price = 16_000_000
        log("編輯畫面把價格改成 \(draft.price)，還沒按儲存")
        log("這時清單上的價格：\(alerts[0].price)")

        log("按下取消後，清單上的價格：\(alerts[0].price)")

        // 如果使用者按的是「儲存」，才把草稿寫回清單
        alerts[0] = draft
        log("（如果按儲存）清單上的價格：\(alerts[0].price)")
    }
}

// MARK: - 演練設定
extension Swift01ValueSemantics {
    static let l01 = Lab(
        id: "L01",
        title: "按了取消，價格卻被改掉",
        question: "第 1 題：struct 和 class 差在哪？什麼時候用哪個？",
        goal: "編輯畫面改了價格但按「取消」，清單上的價格應該維持原樣；按「儲存」才改。",
        steps: [
            "選「Bug 版」執行，看「按下取消後，清單上的價格」。",
            "選「修正版」執行，比較取消與儲存後的價格。",
        ],
        expectBug: "還沒按儲存，清單上的價格就變成 16000000；按取消後還是 16000000。",
        expectFixed: "取消後清單維持 15000000，按儲存才變成 16000000。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("按下取消後，清單上的價格：16000000")
                ? nil : "取消後清單的價格沒有被改掉：Bug 還沒重現（draft 要和清單是同一個物件）"
        },
        checkFixed: { lines in
            if !lines.contains("按下取消後，清單上的價格：15000000") {
                return "取消後清單的價格被改掉了：draft 要是自己的一份"
            }
            if !lines.contains("（如果按儲存）清單上的價格：16000000") {
                return "按儲存後清單應該變成 16000000：要把 draft 寫回 alerts[0]"
            }
            return nil
        }
    )
}
