// L01：按了「取消」，價格卻還是被改掉（struct 和 class 的差別）
// 對應複習站：Swift01ValueSemantics 第 1 題「struct 和 class
// 差在哪？什麼時候用哪個？」
//
// 情境：價格提醒清單，點一筆打開編輯畫面，改了價格之後可以按「取消」
// 或「儲存」。
//
// 你要寫：
//   ① Bug 版：提醒用 class 寫（已經寫好），把清單上的一筆交給編輯畫面去改。
// 跑起來會看到按了取消，清單還是被改掉。
//   ② 修正版：想想 PriceAlert 要用什麼型別，取消才不會改到清單；
// 按儲存時再寫回清單。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift01-ValueSemantics/L01_StructOrClass.swift
import LabKit

// MARK: - Bug 版
private enum L01Bug {
    /// 價格提醒：用 class 寫（已寫好）
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

        // 打開編輯畫面
        // TODO ①：把清單上的這一筆 alerts[0] 交給編輯畫面當草稿（let draft =
        // …），再把 draft.price 改成 16_000_000

        log("這時清單上的價格：\(alerts[0].price)")
        // 使用者按「取消」：編輯畫面把草稿丟掉，不寫回清單
        log("按下取消後，清單上的價格：\(alerts[0].price)")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    // TODO ②-1：PriceAlert 要改成什麼型別，複製給 draft
    // 之後兩邊才會各自獨立？改下面的宣告
    //          （改完之後，下面不需要的 init 可以刪掉）
    final class PriceAlert {
        let symbol: String
        var price: Int

        init(symbol: String, price: Int) {
            self.symbol = symbol
            self.price = price
        }
    }

    static func run(_ log: LabLog) async {
        var alerts = [PriceAlert(symbol: "BTC/JPY", price: 15_000_000)]
        log("清單：\(alerts[0].symbol) 提醒價 \(alerts[0].price)")

        // TODO ②-2：一樣把 alerts[0] 交給 draft，把 draft.price 改成 16_000_000

        log("這時清單上的價格：\(alerts[0].price)")
        log("按下取消後，清單上的價格：\(alerts[0].price)")

        // TODO ②-3：示範「按儲存」：把 draft 寫回 alerts[0]

        log("（如果按儲存）清單上的價格：\(alerts[0].price)")
    }
}

// MARK: - 演練設定
extension Swift01ValueSemantics {
    static let l01 = Lab(
        id: "L01",
        title: "按了取消，價格卻被改掉",
        question: "第 1 題：struct 和 class 差在哪？什麼時候用哪個？",
        goal: "編輯畫面改了價格但按「取消」，清單上的價格應該維持原樣；按「儲存」才改。先寫出會改到清單的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Swift/Swift01-ValueSemantics/L01_StructOrClass.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看「按下取消後，清單上的價格」。",
            "寫 TODO ②，選「修正版」執行，比較取消與儲存後的價格。",
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
