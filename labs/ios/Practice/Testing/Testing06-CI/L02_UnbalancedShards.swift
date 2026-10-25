// L02：把測試分成 3 份同時跑，卻還是等了 12 分鐘（分片沒有平均）
// 對應複習站：Testing06CI 第 2 題「CI 太慢的時候，怎麼讓它變快？」
//
// 情境：12 個測試檔（T06TestFile.all，每個有上一次量到的耗時，總共 1160 秒）要分成 3 份，在 3 台機器上同時跑。
//       整體要等「最慢的那一份」跑完。shard(tests, count) 決定每份分到哪些測試檔。
//       最理想的情況是每份約 387 秒。
//
// 你要寫：
//   ① Bug 版：shard 照檔案順序，把 tests 平分成 count 份（每份連續的 4 個檔案，不看耗時）。
//   ② 修正版：shard 依耗時分配：先把測試依 seconds 由大到小排序，每個測試都放進「目前總耗時最少」的那一份。
// 只改有 TODO 的地方。解答：Solution/Testing/Testing06-CI/L02_UnbalancedShards.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：分片、確認每個檔案剛好被分到一份、印出每份耗時
private func scenario(_ log: LabLog, shard: ([T06TestFile], Int) -> [[T06TestFile]]) {
    let tests = T06TestFile.all
    let shards = shard(tests, 3)
    let assigned = shards.flatMap { $0 }.map(\.name).sorted()
    let eachOnce = shards.count == 3 && assigned == tests.map(\.name).sorted()
    log("\(tests.count) 個測試檔分成 \(shards.count) 份，每個檔案剛好一份：\(eachOnce ? "是" : "否")")
    let totals = shards.map { $0.reduce(0) { $0 + $1.seconds } }
    log("每份耗時：" + totals.map { "\($0) 秒" }.joined(separator: "、"))
    log("整體要等 \(totals.max() ?? 0) 秒（最理想約 \(Int((Double(tests.reduce(0) { $0 + $1.seconds }) / 3).rounded())) 秒）")
}

// MARK: - Bug 版
private enum L02Bug {
    static func shard(_ tests: [T06TestFile], _ count: Int) -> [[T06TestFile]] {
        // TODO ①：照檔案順序，每份連續分 tests.count ÷ count 個檔案（目前回傳空的，沒有任何檔案被分到）
        _ = (tests, count)
        return []
    }

    static func run(_ log: LabLog) async {
        scenario(log, shard: shard)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func shard(_ tests: [T06TestFile], _ count: Int) -> [[T06TestFile]] {
        // TODO ②-1：準備 count 份空的 shards，和每一份目前的總耗時 totals
        // TODO ②-2：把 tests 依 seconds 由大到小排序，一個一個放進「目前 totals 最小」的那一份，同時更新 totals
        _ = (tests, count)
        return []
    }

    static func run(_ log: LabLog) async {
        scenario(log, shard: shard)
    }
}

// MARK: - 演練設定
extension Testing06CI {
    static let l02 = Lab(
        id: "L02",
        title: "分成 3 份同時跑，還是等了 12 分鐘",
        question: "第 2 題：CI 太慢的時候，怎麼讓它變快？",
        goal: "分片要依測試的耗時平均分配，不是平分檔案數量；整體時間取決於最慢的那一份。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看每份的耗時和「整體要等多久」。",
            "想一想：為什麼三台機器同時跑，整體時間卻只比一台機器快一點點？",
            "寫 TODO ②，選「修正版」執行：每個檔案都要剛好分到一份，整體時間要接近最理想的 387 秒。",
        ],
        expectBug: "每份耗時：770 秒、310 秒、80 秒；整體要等 770 秒。",
        expectFixed: "三份耗時都接近 387 秒（解答實測 390、390、380）；整體約 390 秒。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("12 個測試檔分成 3 份，每個檔案剛好一份：是") else { return "12 個測試檔都要分到 3 份其中一份，而且每個檔案只能出現一次" }
            return lines.contains("整體要等 770 秒（最理想約 387 秒）") ? nil : "Bug 還沒重現：照檔案順序，每份分 4 個檔案（前面的檔案都是慢的），整體要等 770 秒"
        },
        checkFixed: { lines in
            guard lines.contains("12 個測試檔分成 3 份，每個檔案剛好一份：是") else { return "12 個測試檔都要分到 3 份其中一份，而且每個檔案只能出現一次" }
            guard let line = lines.first(where: { $0.hasPrefix("整體要等 ") }),
                  let seconds = Int(line.dropFirst("整體要等 ".count).prefix(while: { $0.isNumber })) else { return "找不到整體耗時" }
            return seconds <= 420 ? nil : "整體要等 \(seconds) 秒，還是不平均：先把檔案依耗時由大到小排序，每個檔案都放進目前總耗時最少的那一份"
        }
    )
}
