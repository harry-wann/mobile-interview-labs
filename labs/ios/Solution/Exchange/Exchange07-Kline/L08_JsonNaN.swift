// L08：圖表少了一筆資料，還回報錯誤（用字串插值組要傳給網頁的 JSON，遇到 NaN）
// 對應複習站：Exchange07Kline 第 7 題「用 WebView 放 TradingView Lightweight
// Charts，資料怎麼傳過去？要注意什麼？」
//
// 情境：圖表放在 WebView 裡，App 把每根 K 線的「平均成交價」（vol ÷ amount）用
// JSON 傳給網頁，網頁用 `JSON.parse` 解析後交給圖表。
//       五根 K 線裡，第 3 根這段時間完全沒有成交（vol 0、amount 0），0 ÷ 0
// 得到 NaN（不是數字）。
//       （假的圖表用 `JSONSerialization` 嚴格解析傳進來的文字，和網頁的 `JSON.
// parse` 一樣：不合法的 JSON 就丟掉，記一筆錯誤。）
// Bug 版：用字串插值組 JSON：`{"time":\(time),"value":\(value)}`。Swift 把 NaN
// 轉成文字是 `nan`，不是合法的 JSON，那一筆整個壞掉。
// 修正版：傳之前檢查 `isFinite`：不是有限的數字，就不傳 value，
// 只傳時間（圖表把只有時間的點當成空白點）；用 JSONEncoder 產生 JSON。
import Foundation
import LabKit

private struct Bar {
    let time: Int
    let value: Double
}

/// 每根 K 線的平均成交價 = 成交金額 ÷ 成交數量；沒有成交的區間是 0 ÷ 0 = NaN
private let bars: [Bar] = [
    (60, 4_800_000.0, 1.0), (120, 9_700_000.0, 2.0), (180, 0.0, 0.0),
    (240, 14_500_000.0, 3.0), (300, 4_900_000.0, 1.0),
]
.map { Bar(time: $0.0, value: $0.1 / $0.2) }

/// 假的圖表：收到網頁的 JSON 文字，嚴格解析
private final class FakeChart {
    private(set) var accepted = 0
    private(set) var whitespace = 0
    private(set) var rejected = 0

    func update(json: String, time: Int, log: LabLog) {
        guard let data = json.data(using: .utf8),
            let object = (try? JSONSerialization.jsonObject(with: data))
                as? [String: Any]
        else {
            rejected += 1
            log("時間 \(time)：圖表收到的不是合法的 JSON，丟掉：\(json)")
            return
        }
        accepted += 1
        if object["value"] == nil { whitespace += 1 }
        log("時間 \(time)：圖表收到 \(json)")
    }
}

/// 演練流程（兩個版本共用）：`payload` 決定怎麼把一根 K 線轉成 JSON 文字
private func scenario(_ log: LabLog, payload: (Bar) -> String) {
    let chart = FakeChart()
    for bar in bars {
        chart.update(json: payload(bar), time: bar.time, log: log)
    }
    log(
        "最後：圖表收到 \(chart.accepted) 筆（其中空白點 \(chart.whitespace) 筆），格式錯誤 \(chart.rejected) 筆"
    )
}

private struct Point: Encodable {
    let time: Int
    let value: Double?
}

// MARK: - Bug 版
private enum L08Bug {
    static func run(_ log: LabLog) async {
        scenario(log) { bar in
            // 字串插值：NaN 變成 nan
            "{\"time\":\(bar.time),\"value\":\(bar.value)}"
        }
    }
}

// MARK: - 修正版
private enum L08Fixed {
    static func run(_ log: LabLog) async {
        scenario(log) { bar in
            // 不是有限的數字：不傳 value
            let point = Point(
                time: bar.time, value: bar.value.isFinite ? bar.value : nil)
            return String(
                data: try! JSONEncoder().encode(point), encoding: .utf8)!
        }
    }
}

// MARK: - 演練設定
extension Exchange07Kline {
    static let l08 = Lab(
        id: "L08",
        title: "圖表少一筆資料，還回報錯誤",
        question:
            "第 7 題：用 WebView 放 TradingView Lightweight Charts，資料怎麼傳過去？要注意什麼？",
        goal: "傳給網頁的資料要用 JSON 編碼器產生，不要用字串插值；NaN、無限大要先用 `isFinite` 檢查，不能直接送出。",
        steps: [
            "選「Bug 版」執行，看哪一筆被圖表丟掉，收到的文字長什麼樣子？",
            "想一想：Swift 把 NaN 轉成文字是什麼？JSON 認得這個字嗎？（JavaScript 的 `JSON.parse` 一樣不認得）",
            "選「修正版」執行，五筆都要被圖表收到，沒有錯誤；第 3 根是只有時間的空白點。",
            "進階：`JSONSerialization.data(withJSONObject:)` 遇到 NaN 會丟出 Objective-C 例外，Swift 的 `do/catch` 接不到，App 會直接閃退；要先用 `isValidJSONObject` 檢查，或改用 `JSONEncoder`。",
        ],
        expectBug:
            "第 3 根的文字是 `{\"time\":180,\"value\":nan}`，圖表丟掉：「最後：圖表收到 4 筆（其中空白點 0 筆），格式錯誤 1 筆」。",
        expectFixed:
            "第 3 根的文字是 `{\"time\":180}`（空白點）：「最後：圖表收到 5 筆（其中空白點 1 筆），格式錯誤 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, payload:) 執行"
            }
            return lines.contains("最後：圖表收到 4 筆（其中空白點 0 筆），格式錯誤 1 筆")
                ? nil
                : "Bug 還沒重現：用字串插值組 JSON（\"{\\\"time\\\":\\(bar.time),\\\"value\\\":\\(bar.value)}\"），第 3 根的 value 是 NaN，會變成不合法的 JSON"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, payload:) 執行"
            }
            return lines.contains("最後：圖表收到 5 筆（其中空白點 1 筆），格式錯誤 0 筆")
                ? nil
                : "value 不是有限的數字（bar.value.isFinite 為 false）時，Point 的 value 要設成 nil，只傳時間；其他的照常傳，用 JSONEncoder 編碼"
        }
    )
}
