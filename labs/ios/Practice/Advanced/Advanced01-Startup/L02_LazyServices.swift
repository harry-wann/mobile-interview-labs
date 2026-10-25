// L02：畫面還沒出現，6 個服務已經全部建好了（啟動時什麼都先建立，改成用到才建立）
// 對應複習站：Advanced01Startup 第 3 題「iOS 在 main() 之前做了什麼事？哪些會讓它變慢？」
//
// 情境：main() 之前會同步執行的東西（Objective-C 的 +load、C 的 static initializer）有一個共同問題：不管這次用不用得到，啟動時都先做完。
//        Swift 沒有 +load，但「啟動時把所有服務建好，放進一張表」是同樣的寫法。
//        這個 App 有 6 個服務，每個建立都要花時間（記憶體、讀檔、註冊）。首畫面只需要當機回報和資料庫，使用者打開行情頁之後才需要分析工具，
//        廣告、A/B 測試、客服聊天這次根本沒用到。
//
// 你要寫：
//   ① Bug 版：bootAll 一次建立全部 6 個服務（serviceCosts 已經列好名稱和花費），啟動時就全部建好。
//   ② 修正版：登錄表 Registry.service(_:)：第一次要用才建立，建好就記住，之後回傳同一個。
//             （Swift 的全域變數與 static 屬性本來就是第一次用到才初始化；登錄表是自己控制「什麼時候第一次用到」。）
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced01-Startup/L02_LazyServices.swift
import Foundation
import LabKit

/// 6 個服務和各自建立要花的毫秒數（兩個版本共用）
private let serviceCosts: [(name: String, ms: Int)] = [
    ("CrashReporter", 10), ("Database", 40), ("Analytics", 60), ("AdsSDK", 90), ("ABTest", 70), ("ChatSDK", 80),
]

/// 一個服務：建立的時候花時間，並寫一行 log（兩個版本共用）
private final class Service {
    let name: String
    init(_ name: String, ms: Int, log: LabLog) {
        self.name = name
        a01Work(ms)
        log("建立 \(name)")
    }
}

private func cost(of name: String) -> Int { serviceCosts.first { $0.name == name }?.ms ?? 0 }

/// 演練流程（兩個版本共用）：啟動 → 首幀 → 使用者打開行情頁（需要 Analytics）→ 結束
private func scenario(_ log: LabLog, boot: () -> Void, onMarketOpened: () -> Void) {
    let mark = log.mark()                           // 只數這一次執行寫的 log
    func created() -> Int { log.texts(since: mark).filter { $0.hasPrefix("建立 ") }.count }
    let t0 = A01Clock.nowMs()
    boot()
    log("首幀前建立了 \(created()) 個服務，首幀：\(Int(A01Clock.nowMs() - t0)) ms")
    onMarketOpened()
    log("整個流程結束，一共建立了 \(created()) 個服務")
}

// MARK: - Bug 版
private enum L02Bug {
    /// 啟動時一次建立全部的服務，放進一個陣列
    static func bootAll(_ log: LabLog) -> [Service] {
        // TODO ①：用 serviceCosts 的每一筆，建立 Service(name, ms:, log:)，回傳全部（用 map）
        []
    }

    static func run(_ log: LabLog) async {
        await Task.detached {
            var services: [Service] = []
            scenario(log, boot: { services = bootAll(log) }, onMarketOpened: {
                _ = services.first { $0.name == "Analytics" }     // 行情頁要用 Analytics：早就建好了
            })
        }.value
    }
}

// MARK: - 修正版
private enum L02Fixed {
    /// 登錄表：第一次要用某個服務時才建立，之後直接回傳同一個
    final class Registry {
        private let log: LabLog
        private var created: [String: Service] = [:]

        init(log: LabLog) { self.log = log }

        func service(_ name: String) -> Service {
            // TODO ②：created 裡已經有就直接回傳；沒有才建立 Service(name, ms: cost(of: name), log: log)，記進 created 再回傳
            Service(name, ms: 0, log: LabLog(echo: false))     // 暫時的替身：不花時間、不寫 log。寫好之後這行刪掉
        }
    }

    static func run(_ log: LabLog) async {
        await Task.detached {
            let registry = Registry(log: log)
            scenario(log, boot: {
                _ = registry.service("CrashReporter")                        // 首畫面只需要這兩個
                _ = registry.service("Database")
            }, onMarketOpened: {
                _ = registry.service("Analytics")                            // 行情頁才需要
                _ = registry.service("Analytics")                            // 再用一次：不會再建立
            })
        }.value
    }
}

// MARK: - 演練設定
extension Advanced01Startup {
    static let l02 = Lab(
        id: "L02",
        title: "畫面還沒出現，6 個服務已經全部建好",
        question: "第 3 題：iOS 在 main() 之前做了什麼事？哪些會讓它變慢？",
        goal: "啟動時只建立首畫面一定要用的服務，其他的等第一次用到才建立；沒用到的整個流程都不用建立。先寫出啟動時全部建立的 Bug 版，再寫用到才建立的修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced01-Startup/L02_LazyServices.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看「首幀前建立了幾個服務」和首幀花的時間。",
            "寫 TODO ②，選「修正版」執行：首幀前只建立 2 個，整個流程結束也只有 3 個，沒用到的廣告、A/B 測試、聊天沒有建立。",
            "進階：用 Instruments 的 App Launch 範本錄製一次真正的啟動，在 Static Initializer Calls 與 Dylib Loading 兩段看 pre-main 花了多少時間（演練 App 本身沒有 +load，數字很小，拿來對照你公司專案會更有感）。",
        ],
        expectBug: "首幀前建立了 6 個服務，首幀約 350 ms（6 個服務的建立時間加總）；整個流程結束一共 6 個。",
        expectFixed: "首幀前建立了 2 個服務，首幀約 50 ms；整個流程結束一共 3 個（多了行情頁用到的 Analytics），廣告、A/B 測試、聊天沒有建立。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let before = a01Number(in: lines, prefix: "首幀前建立了 ") else { return "演練沒有跑完" }
            if before < 6 { return "首幀前只建立了 \(Int(before)) 個服務：Bug 版要在啟動時一次建立全部 6 個（bootAll）" }
            let first = a01Number(in: lines, prefix: "首幀前建立了 6 個服務，首幀：") ?? 0
            return first >= 250 ? nil : "首幀只花了 \(Int(first)) ms：每個服務建立時都要呼叫 Service(name, ms:, log:)"
        },
        checkFixed: { lines in
            guard let before = a01Number(in: lines, prefix: "首幀前建立了 "),
                  let total = a01Number(in: lines, prefix: "整個流程結束，一共建立了 ") else { return "演練沒有跑完" }
            if before != 2 { return "首幀前建立了 \(Int(before)) 個服務：應該只有 CrashReporter 和 Database（用 registry.service(name) 取得）" }
            if total < 3 { return "行情頁用到 Analytics，卻沒有建立它：registry.service(\"Analytics\") 第一次要真的建立 Service" }
            if total > 3 { return "一共建立了 \(Int(total)) 個服務，多了：同一個服務建立過就要記住，不能重複建立；沒用到的不要建立" }
            return nil
        }
    )
}
