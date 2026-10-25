// L03：移除廣告 SDK 說可以省 22 MB，實際只省了 18 MB（共用的依賴不能重複算）
// 對應複習站：Advanced07AppSize 第 4
// 題「為什麼精簡第三方依賴通常效果最大？怎麼找出誰最占空間？」
//
// 情境：App 整合了 5 個 SDK，它們又各自依賴其他函式庫（大小單位 MB）：
//         Charts 6 → Utils 4；Push 2 → Utils、FirebaseCore 3；Analytics 3 →
// FirebaseCore；
//         AdsSDK 5 → AdsAdapterA 6、AdsAdapterB 7、Utils；ImageLoader 2。
//       Utils 被 Charts、Push、AdsSDK 三個一起用，FirebaseCore 被 Push、
// Analytics 一起用。
//       演練要算「移除某個 SDK 後，App 實際能少多少」，
// 再依這個數字排出最值得處理的順序。
//
// 你要寫：
//   ① Bug 版：把這個 SDK 底下所有依賴的大小加起來（含共用的 Utils）。
// 各項加總會比所有依賴的總和還大。
//   ② 修正版：算「移除前的依賴總和」減「移除後還留著的依賴總和」，其餘 SDK
// 仍然用到的依賴不算省下來的。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced07-AppSize/L03_DependencyShare.swift
import Foundation
import LabKit

private let sizes: [String: Int] = [
    "Charts": 6, "Push": 2, "Analytics": 3, "AdsSDK": 5, "ImageLoader": 2,
    "Utils": 4, "FirebaseCore": 3, "AdsAdapterA": 6, "AdsAdapterB": 7,
]

private let dependencies: [String: [String]] = [
    "Charts": ["Utils"],
    "Push": ["Utils", "FirebaseCore"],
    "Analytics": ["FirebaseCore"],
    "AdsSDK": ["AdsAdapterA", "AdsAdapterB", "Utils"],
    "ImageLoader": [],
]

private let roots = ["Charts", "Push", "Analytics", "AdsSDK", "ImageLoader"]

/// 從這幾個 SDK 出發，會用到的所有依賴（含自己），每個只算一次（兩個版本共用）
private func reachable(from roots: [String]) -> Set<String> {
    var seen = Set<String>()
    var stack = roots
    while let name = stack.popLast() {
        guard seen.insert(name).inserted else { continue }
        stack.append(contentsOf: dependencies[name] ?? [])
    }
    return seen
}

private func totalSize(_ names: Set<String>) -> Int {
    names.reduce(0) { $0 + sizes[$1, default: 0] }
}

/// 演練流程（兩個版本共用）：savings(SDK) 回傳「移除這個 SDK 後，App 能少多少
/// MB」
private func scenario(_ log: LabLog, savings: (String) -> Int) {
    let ranking = roots.map { ($0, savings($0)) }.sorted {
        $0.1 != $1.1 ? $0.1 > $1.1 : $0.0 < $1.0
    }
    for (name, mb) in ranking { log("移除 \(name) 可省 \(mb) MB") }
    log("最值得先處理：\(ranking[0].0)")
    log(
        "各項加總 \(ranking.reduce(0) { $0 + $1.1 }) MB（App 所有依賴總共 \(totalSize(reachable(from: roots))) MB）"
    )
}

// MARK: - Bug 版
private enum L03Bug {
    static func savings(_ root: String) -> Int {
        // TODO ①：把這個 SDK 底下所有依賴（含自己）的大小加起來。提示：
        // totalSize(reachable(from: [root]))
        return 0
    }

    static func run(_ log: LabLog) async {
        scenario(log, savings: savings)
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func savings(_ root: String) -> Int {
        // TODO ②：算「移除之前的依賴總和」減「移除之後還留著的依賴總和」：
        //         先用 roots.filter 挑出移除這個 SDK 之後剩下的 SDK，再各自用
        // reachable(from:) 與 totalSize 算總和，相減
        return 0
    }

    static func run(_ log: LabLog) async {
        scenario(log, savings: savings)
    }
}

// MARK: - 演練設定
extension Advanced07AppSize {
    static let l03 = Lab(
        id: "L03",
        title: "說能省 22 MB，實際只省 18 MB",
        question: "第 4 題：為什麼精簡第三方依賴通常效果最大？怎麼找出誰最占空間？",
        goal:
            "評估移除某個 SDK 能省多少，要看「移除之後，還有誰在用它的依賴」。共用的依賴不能算在每一個 SDK 頭上。 先寫出重複計算共用依賴的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced07-AppSize/L03_DependencyShare.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看每個 SDK 的「可省」數字，以及最後一行：各項加總有沒有超過所有依賴的總和？",
            "想一想：Utils 同時被 Charts、Push、AdsSDK 使用，只移除 AdsSDK，Utils 會不會從 App 消失？",
            "寫 TODO ②，選「修正版」執行：移除前與移除後各算一次所有依賴的總和，相減。",
            "進階：Android 用 `./gradlew app:dependencies` 看依賴樹，iOS 用 link map（Write Link Map File）看每個函式庫的大小，再自己用同樣的方法算。",
        ],
        expectBug:
            "AdsSDK 可省 22 MB（最大）、Charts 10、Push 9、Analytics 6、ImageLoader 2；各項加總 49 MB，比所有依賴總共 38 MB 還大。",
        expectFixed:
            "AdsSDK 可省 18 MB（仍是最大）、Charts 6、Analytics 3、ImageLoader 2、Push 2；各項加總 31 MB，小於 38 MB（剩下的 7 MB 是共用的 Utils 與 FirebaseCore）。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("各項加總") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains("移除 AdsSDK 可省 22 MB") {
                return "Bug 還沒重現：把 AdsSDK 底下所有依賴的大小加起來（含共用的 Utils），應該是 22 MB"
            }
            if !lines.contains(where: { $0.hasPrefix("各項加總 49 MB") }) {
                return "各項加總應該是 49 MB（比所有依賴的總和 38 MB 還大）"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("各項加總") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains("移除 AdsSDK 可省 18 MB") {
                return "移除 AdsSDK 應該只省 18 MB：Utils 還被 Charts、Push 使用，不算省下來的"
            }
            if !lines.contains("移除 Push 可省 2 MB") {
                return "移除 Push 應該只省 2 MB：Utils、FirebaseCore 還有別的 SDK 在用"
            }
            if !lines.contains(where: { $0.hasPrefix("各項加總 31 MB") }) {
                return "各項加總應該是 31 MB"
            }
            return nil
        }
    )
}
