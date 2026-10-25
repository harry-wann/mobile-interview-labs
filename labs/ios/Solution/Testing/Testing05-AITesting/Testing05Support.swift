// Testing05AITesting 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   被測的程式 T05Fee（手續費），以及「被改壞的版本」T05Mutation（真正的 mutation testing 工具會自動改程式碼，這裡用開關代表）。
//   測試套件 T05Suite：AI 產生的測試、人補強過的測試；能算覆蓋率，也能對任一個改壞的版本跑一次。
//   L02 用的「亂數」與 L03 用的 CI 歷史紀錄。
import Foundation
import LabKit

// 被測的程式：手續費

enum T05Mutation: CaseIterable {
    case none                  // 沒有改壞
    case boundary              // 邊界：>= 改成 >
    case swapRates             // maker 與 taker 的費率對調
    case roundInsteadOfFloor   // 無條件捨去改成四捨五入
    case noMinFee              // 拿掉「最少收 1 日圓」
    case noVipDiscount         // VIP 沒有減半

    /// 所有「改壞的版本」（不含 none）
    static let allMutants: [T05Mutation] = allCases.filter { $0 != .none }

    var title: String {
        switch self {
        case .none: "正確的實作"
        case .boundary: ">= 改成 >"
        case .swapRates: "maker 與 taker 費率對調"
        case .roundInsteadOfFloor: "無條件捨去改成四捨五入"
        case .noMinFee: "拿掉最少收 1 日圓"
        case .noVipDiscount: "VIP 沒有減半"
        }
    }
}

enum T05Fee {
    /// 6 個覆蓋率檢查點的名稱
    static let probeIds = ["maker", "taker", "vip", "normal", "minFeeApplied", "minFeeNotNeeded"]

    /// 手續費（日圓）。maker 0.05%、taker 0.15%；單筆金額達 1,000 萬日圓（含）以上是 VIP，費率減半；無條件捨去；最少收 1 日圓。
    /// 費率用「每 100,000 的幾分」表示（0.05% 是 50）。probe 用來記錄程式跑到哪幾個地方（覆蓋率）。
    static func calculate(amountJPY: Int, isMaker: Bool, mutation: T05Mutation = .none, probe: ((String) -> Void)? = nil) -> Int {
        probe?(isMaker ? "maker" : "taker")
        var rate = isMaker ? 50 : 150
        if mutation == .swapRates { rate = isMaker ? 150 : 50 }
        let isVip = mutation == .boundary ? amountJPY > 10_000_000 : amountJPY >= 10_000_000
        if isVip {
            probe?("vip")
            if mutation != .noVipDiscount { rate /= 2 }
        } else {
            probe?("normal")
        }
        var fee = mutation == .roundInsteadOfFloor ? (amountJPY * rate + 50_000) / 100_000 : amountJPY * rate / 100_000
        if mutation != .noMinFee {
            if fee < 1 {
                probe?("minFeeApplied")
                fee = 1
            } else {
                probe?("minFeeNotNeeded")
            }
        }
        return fee
    }
}

// 測試套件

typealias T05FeeFunction = (_ amountJPY: Int, _ isMaker: Bool) -> Int

struct T05Test {
    let name: String
    /// 拿一個手續費函式來跑，回傳測試有沒有通過
    let check: (T05FeeFunction) -> Bool
}

final class T05Probe {
    private(set) var hits: Set<String> = []
    func hit(_ id: String) { hits.insert(id) }
}

struct T05Suite {
    let name: String
    let tests: [T05Test]

    /// 對某個版本（正確的或被改壞的）跑全部測試，是不是全部通過
    func passes(on mutation: T05Mutation) -> Bool {
        tests.allSatisfy { test in
            test.check { T05Fee.calculate(amountJPY: $0, isMaker: $1, mutation: mutation) }
        }
    }

    /// 覆蓋率：測試執行時，跑到幾個檢查點（共 6 個），0 到 1
    func coverage() -> Double {
        let probe = T05Probe()
        for test in tests {
            _ = test.check { T05Fee.calculate(amountJPY: $0, isMaker: $1, probe: probe.hit) }
        }
        return Double(probe.hits.count) / Double(T05Fee.probeIds.count)
    }

    /// 被改壞的版本裡，有幾個被測試抓到（測試失敗）
    func killedMutants() -> Int {
        T05Mutation.allMutants.filter { !passes(on: $0) }.count
    }

    /// AI 產生的 5 個測試：看起來很完整，每個函式、每個分支都跑到，卻沒有檢查精確的數字
    static let aiGenerated = T05Suite(name: "AI 產生的測試", tests: [
        T05Test(name: "手續費不會是負的") { fee in fee(1_000_000, true) >= 0 },
        T05Test(name: "taker 手續費大於 0") { fee in fee(1_000_000, false) > 0 },
        T05Test(name: "手續費不會超過成交金額") { fee in fee(5_000_000, false) <= 5_000_000 },
        T05Test(name: "VIP 單有手續費") { fee in fee(20_000_000, true) > 0 },
        T05Test(name: "小額單也有手續費") { fee in fee(1_000, true) > 0 },
    ])

    /// 人補強之後的 5 個測試：檢查精確的數字，包含邊界
    static let strengthened = T05Suite(name: "補強後的測試", tests: [
        T05Test(name: "maker 與 taker 的費率") { fee in fee(1_000_000, true) == 500 && fee(1_000_000, false) == 1_500 },
        T05Test(name: "無條件捨去") { fee in fee(9_999_999, false) == 14_999 },
        T05Test(name: "剛好 1,000 萬是 VIP") { fee in fee(10_000_000, false) == 7_500 },
        T05Test(name: "VIP 費率減半") { fee in fee(20_000_000, true) == 5_000 },
        T05Test(name: "最少收 1 日圓") { fee in fee(1_000, true) == 1 },
    ])
}

// L02：用「亂數」的測試

/// 演練用固定的一串數字代表亂數，這樣每次執行結果都一樣
final class T05Random {
    private let values = [2_000, 1_000, 777, 1_900, 450, 1_234, 3_000, 888, 1_500, 600]
    private var index = 0

    func nextAmount() -> Int {
        defer { index += 1 }
        return values[index % values.count]
    }
}

// L03：CI 歷史紀錄

/// 一次測試執行的紀錄
struct T05CIRun {
    let commit: String
    let test: String
    let passed: Bool
}

enum T05History {
    /// 3 個 commit、4 個測試的執行紀錄：
    /// testFee、testChart 一直通過；testLogin 在 c2 壞掉（兩次重試都失敗，真的 bug），c3 修好；
    /// testOrderUI 在 c1 與 c3 同一個 commit 上有時失敗、有時通過（flaky）。
    static let runs: [T05CIRun] = [
        T05CIRun(commit: "c1", test: "testFee", passed: true),
        T05CIRun(commit: "c1", test: "testLogin", passed: true),
        T05CIRun(commit: "c1", test: "testOrderUI", passed: false),
        T05CIRun(commit: "c1", test: "testOrderUI", passed: true),
        T05CIRun(commit: "c1", test: "testChart", passed: true),
        T05CIRun(commit: "c2", test: "testFee", passed: true),
        T05CIRun(commit: "c2", test: "testLogin", passed: false),
        T05CIRun(commit: "c2", test: "testLogin", passed: false),
        T05CIRun(commit: "c2", test: "testOrderUI", passed: true),
        T05CIRun(commit: "c2", test: "testChart", passed: true),
        T05CIRun(commit: "c3", test: "testFee", passed: true),
        T05CIRun(commit: "c3", test: "testLogin", passed: true),
        T05CIRun(commit: "c3", test: "testOrderUI", passed: true),
        T05CIRun(commit: "c3", test: "testOrderUI", passed: false),
        T05CIRun(commit: "c3", test: "testChart", passed: true),
    ]
}
