// L04：同一筆成交，在不同國家的手機上被歸到不同天的日 K（用裝置的時區切日界）
// 對應複習站：Exchange07Kline 第 3 題「日 K 的邊界在哪裡？UTC 和 JST 差在哪？」
//
// 情境：App 要把成交聚合進日 K。這套 API 文件的日 K 範例，起點是 16:00 UTC（UTC+8 的午夜，偏移 28800 秒），要和伺服器的 `id` 一致，聚合才會對得上官方圖表。
//       兩筆成交：2021-09-29 20:00 UTC（1632945600000 毫秒）、2021-09-29 15:30 UTC（1632929400000 毫秒）。
//       伺服器的日 K：第一筆在 id 1632931200 那根，第二筆在 id 1632844800 那根。
//       演練模擬三支手機：裝置時區分別是 Asia/Tokyo、America/New_York、UTC。
//
// 你要寫：
//   ① Bug 版（L04Bug.run 的 bucketId 閉包）：用 Calendar（timeZone 設成 zone）的 startOfDay(for:) 找這筆成交那一天的起點，回傳秒數。
//   ② 修正版（L04Fixed.run 的 bucketId 閉包）：sec = e07FloorDiv(tsMs, 1000)，回傳 e07FloorDiv(sec + originOffsetSec, 86_400) * 86_400 - originOffsetSec。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange07-Kline/L04_DayBoundary.swift
import Foundation
import LabKit

private let zones = ["Asia/Tokyo", "America/New_York", "UTC"]
private let trades: [(label: String, ts: Int64, expected: Int64)] = [
    ("20:00 UTC 的成交", 1_632_945_600_000, 1_632_931_200),
    ("15:30 UTC 的成交", 1_632_929_400_000, 1_632_844_800),
]

/// 演練流程（兩個版本共用）：`bucketId` 回傳這筆成交所屬那根日 K 的起點（秒）
private func scenario(_ log: LabLog, bucketId: (_ tsMs: Int64, _ zone: TimeZone) -> Int64) {
    var wrong = 0
    for zoneId in zones {
        let zone = TimeZone(identifier: zoneId)!
        for trade in trades {
            let id = bucketId(trade.ts, zone)
            if id != trade.expected { wrong += 1 }
            log("裝置時區 \(zoneId)，\(trade.label) → 日 K id \(id)（伺服器是 \(trade.expected)）\(id == trade.expected ? "✓" : "✗")")
        }
    }
    log("最後：6 個結果裡，和伺服器對不上的有 \(wrong) 個")
}

// MARK: - Bug 版
private enum L04Bug {
    static func run(_ log: LabLog) async {
        scenario(log) { tsMs, zone in
            // TODO ①：用 Calendar(identifier: .gregorian)，把 timeZone 設成 zone；tsMs 轉成 Date（毫秒要除以 1000 當秒數），
            //        用 calendar.startOfDay(for:) 找這一天的起點，回傳它的 timeIntervalSince1970（Int64）。目前回傳 0 只是佔位
            return 0
        }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static let originOffsetSec: Int64 = 28_800                          // 資料來源的日界：UTC+8 的午夜（做成設定，向交易所確認）

    static func run(_ log: LabLog) async {
        scenario(log) { tsMs, _ in
            // TODO ②：sec = e07FloorDiv(tsMs, 1000)（毫秒轉秒）；回傳 e07FloorDiv(sec + originOffsetSec, 86_400) * 86_400 - originOffsetSec。
            //        不要用 zone。目前回傳 0 只是佔位
            return 0
        }
    }
}

// MARK: - 演練設定
extension Exchange07Kline {
    static let l04 = Lab(
        id: "L04",
        title: "同一筆成交，換個國家就歸到別天的日 K",
        question: "第 3 題：日 K 的邊界在哪裡？UTC 和 JST 差在哪？",
        goal: "日 K 的日界由資料來源決定，不能用裝置的時區：用 floor 除法加上資料來源的偏移，所有手機算出來的 K 線起點才會一致，也和伺服器一致。先寫出用裝置時區切日界的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange07-Kline/L04_DayBoundary.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看三個裝置時區算出來的日 K id，和伺服器的比，哪些對不上？",
            "想一想：東京的手機算出的 1632927600，對應的 UTC 時間是幾點？和伺服器的 16:00 UTC 差多少？",
            "寫 TODO ②（修正版），選「修正版」執行，六個結果都要和伺服器一致，而且三個裝置時區算出來一樣。",
            "進階：畫伺服器給的 K 線時，一律用它回傳的 `id`，不要自己重算；只有本機聚合才需要決定日界。",
        ],
        expectBug: "六個結果全部對不上（東京 1632927600、紐約 1632888000、UTC 1632873600…），「最後：6 個結果裡，和伺服器對不上的有 6 個」。",
        expectFixed: "六個結果都 ✓：20:00 UTC 的成交是 1632931200，15:30 UTC 的成交是 1632844800，「和伺服器對不上的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, bucketId:) 執行" }
            if !lines.contains("裝置時區 Asia/Tokyo，20:00 UTC 的成交 → 日 K id 1632927600（伺服器是 1632931200）✗") {
                return "Bug 還沒重現：用 Calendar（timeZone 設成 zone）的 startOfDay(for:) 找一天的起點，東京算出來應該是 1632927600"
            }
            return lines.contains("最後：6 個結果裡，和伺服器對不上的有 6 個") ? nil : "三個裝置時區、兩筆成交，六個結果都應該和伺服器對不上"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, bucketId:) 執行" }
            return lines.contains("最後：6 個結果裡，和伺服器對不上的有 0 個")
                ? nil : "用 floor 除法：sec = floorDiv(tsMs, 1000)；floorDiv(sec + 28800, 86400) * 86400 - 28800，不要用 zone"
        }
    )
}
