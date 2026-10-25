// L09：圖表停止更新了，後面的 K 線都沒有畫出來（`update` 遇到時間倒退丟出例外，
// 沒有擋也沒有接）
// 對應複習站：Exchange07Kline 第 7 題「用 WebView 放 TradingView Lightweight
// Charts，資料怎麼傳過去？要注意什麼？」
//
// 情境：App 要把 K 線一根一根用 `update` 推給圖表。和 Lightweight Charts 一樣，
// `update` 只能推相同或較新的時間，時間倒退會丟出例外。
//       從背景回到前景、合併補資料的時候，依序要推的時間是：60、120、180、
// 120（重送的舊資料，已經推過了）、240、300。
//       正確結果：跳過倒退的 120，圖表最後一根是 300，共 5 根（60、120、180、
// 240、300）。
//
// 你要寫：
//   ① Bug 版（L09Bug.run 的 push 閉包）：依序 try chart.update(time:)，
// 不擋時間倒退，也不接例外。
//   ② 修正版（L09Fixed.run 的 push 閉包）：記住 lastPushedTime，比它舊的
// log「略過時間倒退的 …」並跳過；每次 update 包 do/catch，成功才更新
// lastPushedTime。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange07-Kline/L09_UpdateBackwards.swift
import Foundation
import LabKit

private struct ChartError: Error, CustomStringConvertible {
    let description: String
}

/// 假的圖表：`update` 只能推相同或較新的時間
private final class FakeChart {
    private(set) var times: [Int] = []

    func update(time: Int) throws {
        if let last = times.last, time < last {
            throw ChartError(
                description:
                    "Cannot update oldest data, last time=\(last), new time=\(time)"
            )
        }
        times.append(time)
    }
}

/// 演練流程（兩個版本共用）：`push` 把這些時間依序推給圖表
private func scenario(
    _ log: LabLog, push: (FakeChart, [Int], LabLog) throws -> Void
) {
    let chart = FakeChart()
    do {
        try push(chart, [60, 120, 180, 120, 240, 300], log)
    } catch {
        log("更新中斷，丟出例外：\(error)")
    }
    log(
        "最後：圖表最後一根是 \(chart.times.last.map(String.init) ?? "-")，共 \(chart.times.count) 根（正確答案：最後一根 300，共 5 根）"
    )
}

// MARK: - Bug 版
private enum L09Bug {
    static func run(_ log: LabLog) async {
        scenario(log) { chart, times, _ in
            // TODO ①：用 for 迴圈依序 try chart.update(time: time)，
            // 不擋時間倒退，也不接例外
        }
    }
}

// MARK: - 修正版
private enum L09Fixed {
    static func run(_ log: LabLog) async {
        scenario(log) { chart, times, log in
            // TODO ②：用 var lastPushedTime 記住最後推送的時間；for
            // 迴圈依序處理 times：
            //        time < lastPushedTime 就 log("略過時間倒退的 \(time)
            // （最後推送的是 \(lastPushedTime)）") 並跳過；
            //        否則 do { try chart.update(time: time); lastPushedTime =
            // time } catch { log(...) }，一次失敗不要讓後面全部停掉
        }
    }
}

// MARK: - 演練設定
extension Exchange07Kline {
    static let l09 = Lab(
        id: "L09",
        title: "圖表停止更新，後面的 K 線沒有畫出來",
        question:
            "第 7 題：用 WebView 放 TradingView Lightweight Charts，資料怎麼傳過去？要注意什麼？",
        goal:
            "圖表的 `update` 時間不能倒退：要記住最後推送的時間，比它舊的跳過，並且每次 `update` 都包 `try/catch`，一次例外不能讓圖表悄悄停止更新。先寫出不擋時間倒退的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange07-Kline/L09_UpdateBackwards.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看圖表最後一根是哪一根，哪幾根沒有推出去？",
            "想一想：丟出例外的是哪一個時間？為什麼 240、300 也跟著沒有畫出來？",
            "寫 TODO ②（修正版），選「修正版」執行，要看到「略過時間倒退的 120」，圖表最後一根是 300，共 5 根。",
            "進階：從背景回到前景時，要把「自上次推送以來的所有 K 線」依序推完，才不會在中間留下洞。",
        ],
        expectBug:
            "推到第 4 個（120）時丟出「Cannot update oldest data」，「最後：圖表最後一根是 180，共 3 根」，240、300 沒有畫出來。",
        expectFixed: "「略過時間倒退的 120（最後推送的是 180）」，「最後：圖表最後一根是 300，共 5 根」。",
        run: { variant, log in
            switch variant {
            case .bug: await L09Bug.run(log)
            case .fixed: await L09Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, push:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：圖表最後一根是 180，共 3 根")
            })
                ? nil
                : "Bug 還沒重現：用 for 迴圈依序 try chart.update(time:)，不擋時間倒退、不接例外，第 4 個（120）會丟出例外把迴圈中斷"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, push:) 執行"
            }
            if !lines.contains(where: { $0.hasPrefix("略過時間倒退的 120") }) {
                return "記住最後推送的時間（lastPushedTime），比它舊的就跳過，並用 log 寫「略過時間倒退的 …」"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：圖表最後一根是 300，共 5 根")
            })
                ? nil
                : "跳過倒退的 120 之後，240、300 都要推出去；每次 update 成功才更新 lastPushedTime"
        }
    )
}
