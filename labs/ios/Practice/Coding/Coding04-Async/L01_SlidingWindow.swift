// L01：限制同時 3 個，總共卻花了 600 毫秒（分批執行，沒有在完成後補位）
// 對應複習站：Coding04Async 第 1 題「怎麼限制最多只有 N 個同時進行？」
//
// 情境：行情頁要載入 9 個交易對的 K 線，最多同時 3 個。每個請求花的時間不一樣：第 1、4、7 個慢（200 毫秒），其他快（20 毫秒）。
//       回傳的結果要和輸入的順序一樣。（所有請求一口氣全部送出的寫法，見 Swift04 L02。）
//
// 你要寫：
//   ① Bug 版（L01Bug.mapConcurrently 的每一批）：每一批用 withTaskGroup 同時跑（每個 task 帶著這一批裡的位置 offset 回傳），
//      等這一批全部完成，依 offset 放回 out，回傳 out。（切批與 start += limit 已經寫好。）
//   ② 修正版（L01Fixed.mapConcurrently）：滑動視窗：先放 limit 個 task（每個帶著索引），然後每完成一個（group.next()）就依索引放回 results，
//      並補下一個進去，直到全部放完。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding04-Async/L01_SlidingWindow.swift
import Foundation
import LabKit

private let jobs = [200, 20, 20, 200, 20, 20, 200, 20, 20].enumerated().map { C04Job(name: "COIN\($0.offset + 1)", ms: $0.element) }
private let limit = 3

/// 假的 K 線 API：花 job.ms 毫秒，並讓 meter 記下同時有幾個在進行
private func fetch(_ job: C04Job, meter: C04Meter) async -> String {
    meter.enter()
    defer { meter.leave() }
    try? await Task.sleep(for: .milliseconds(job.ms))
    return job.name
}

/// 從「…總共花了 N 毫秒」取出 N（自動檢查用）
private func elapsedMs(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.contains("總共花了 ") && $0.hasSuffix(" 毫秒") }),
          let range = line.range(of: "總共花了 ") else { return nil }
    return Int(line[range.upperBound...].dropLast(" 毫秒".count))
}

/// 演練流程（兩個版本共用）：9 個請求、同時最多 3 個，量時間、同時進行的最高紀錄與結果順序
private func scenario(_ log: LabLog, mapConcurrently: ([C04Job], Int, C04Meter) async -> [String]) async {
    let meter = C04Meter()
    let start = DispatchTime.now()
    let results = await mapConcurrently(jobs, limit, meter)
    let ms = Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
    log("\(jobs.count) 個請求、同時最多 \(limit) 個：總共花了 \(ms) 毫秒")
    log("同時進行的請求最多 \(meter.peak) 個")
    log("結果和輸入的順序相同：\(results == jobs.map(\.name) ? "是" : "否")")
}

// MARK: - Bug 版
private enum L01Bug {
    static func mapConcurrently(_ jobs: [C04Job], limit: Int, meter: C04Meter) async -> [String] {
        var results: [String] = []
        var start = 0
        while start < jobs.count {
            let batch = Array(jobs[start..<min(start + limit, jobs.count)])     // 切成一批一批
            // TODO ①：把下面這一行換成 let part = await withTaskGroup(of: (Int, String).self) { group in … }：
            //         每個 job 一個 task，回傳 (offset, await fetch(job, meter: meter))；用 for await 收結果，
            //         依 offset 放進 out（var out = [String](repeating: "", count: batch.count)），最後回傳 out
            let part: [String] = []
            results += part                                                      // 這一批全部做完，才開始下一批
            start += limit
        }
        return results
    }

    static func run(_ log: LabLog) async {
        await scenario(log, mapConcurrently: { await mapConcurrently($0, limit: $1, meter: $2) })
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func mapConcurrently(_ jobs: [C04Job], limit: Int, meter: C04Meter) async -> [String] {
        await withTaskGroup(of: (Int, String).self) { group in
            var results = [String](repeating: "", count: jobs.count)
            var next = 0
            // TODO ②-1：先放 limit 個：next < min(limit, jobs.count) 時，group.addTask { (index, await fetch(jobs[index], meter: meter)) }（帶著索引 index），next += 1
            // TODO ②-2：while let (index, value) = await group.next()：results[index] = value；
            //          next < jobs.count 就再補一個 task（同樣帶著索引），next += 1
            return results
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, mapConcurrently: { await mapConcurrently($0, limit: $1, meter: $2) })
    }
}

// MARK: - 演練設定
extension Coding04Async {
    static let l01 = Lab(
        id: "L01",
        title: "限制同時 3 個，卻比預期慢一倍多",
        question: "第 1 題：怎麼限制最多只有 N 個同時進行？",
        goal: "滑動視窗：先放 limit 個，每完成一個就補一個。分批執行雖然不會超過上限，但每批都要等最慢的那個。先寫出分批執行的 Bug 版，再寫滑動視窗的修正版。",
        steps: [
            "打開 Practice/Coding/Coding04-Async/L01_SlidingWindow.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看「總共花了幾毫秒」，以及同時進行的最高紀錄（有沒有超過 3？）。",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，比較花的時間（結果順序一樣要正確）。",
            "想一想：為什麼每個 task 要帶著索引回傳？（TaskGroup 的結果是誰先完成誰先回來）",
        ],
        expectBug: "同時最多 3 個，結果順序正確，但總共花了約 600 毫秒（3 批，每批都等 200 毫秒的慢請求）。",
        expectFixed: "同時最多 3 個，結果順序正確，只花約 260 毫秒（快的做完立刻有人接手）。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let ms = elapsedMs(lines) else { return "演練沒有跑完：要用 scenario(log, mapConcurrently:) 執行" }
            if !lines.contains("結果和輸入的順序相同：是") { return "mapConcurrently 還沒寫對：9 個結果要照輸入的順序回傳" }
            if !lines.contains("同時進行的請求最多 3 個") { return "同時進行的請求應該剛好最多 3 個：一批 3 個" }
            return ms >= 500 ? nil : "Bug 還沒重現：要切成一批一批（每批 limit 個），一批全部做完才開始下一批"
        },
        checkFixed: { lines in
            guard let ms = elapsedMs(lines) else { return "演練沒有跑完：要用 scenario(log, mapConcurrently:) 執行" }
            if !lines.contains("結果和輸入的順序相同：是") { return "mapConcurrently 還沒寫對：每個 task 帶著索引回傳，最後依索引放回原本的位置" }
            if !lines.contains("同時進行的請求最多 3 個") { return "同時進行的請求應該剛好最多 3 個：先放 limit 個，每完成一個補一個" }
            return ms < 450 ? nil : "花了 \(ms) 毫秒：每完成一個 task（group.next()），就要立刻補下一個，不要等一批做完"
        }
    )
}
