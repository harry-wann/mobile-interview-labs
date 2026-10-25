// L02：被測的程式壞了，測試卻顯示通過（測試裡的 Task 沒有 await）
// 對應複習站：Testing02iOSTesting 第 2 題「非同步的程式要怎麼測？」
//
// 情境：PriceViewModel.load() 向伺服器載入價格（約 30 ms），載入完把價格存進 price。
//       要驗證：load() 之後 price 是 15,000,000。
//       演練把同一個測試，分別對「正確的實作」和「被改壞的實作」（load() 完忘了存價格，price 永遠是 0）各跑一次，
//       全部測試結束後多等 150 ms，看有沒有「測試結束之後才出現的失敗」。
// Bug 版：測試裡用 `Task { await vm.load(); 檢查 price }`，沒有 await 這個 Task，測試函式馬上結束、被算成「通過」；
//        等 30 ms 後檢查才失敗，但測試早就結束了，執行器沒有人會看到。被改壞的實作也顯示通過（假的綠燈）。
// 修正版：直接在測試裡 `await vm.load()`，再檢查 price。被改壞的實作會讓測試失敗。
import Foundation
import LabKit

/// 把同一個測試分別對「正確的實作」和「被改壞的實作」各跑一次
private func runMatrix(_ log: LabLog, test: @escaping (T02PriceLoading, T02Expect) async -> Void) async {
    let implementations: [(String, () -> T02PriceLoading)] = [
        ("正確的實作", { T02PriceViewModel() }),
        ("被改壞的實作", { T02BrokenPriceViewModel() }),
    ]
    for (name, make) in implementations {
        let suite = T02Suite()
        suite.test("load() 之後價格是 15,000,000") { t in
            await test(make(), t)
        }
        await suite.run(label: name, settleMs: 150, log: log)
    }
}

// MARK: - Bug 版
private enum L02Bug {
    static func test(_ vm: T02PriceLoading, _ t: T02Expect) async {
        Task {                                              // 沒有 await 這個 Task：測試函式馬上就結束了
            await vm.load()
            t.equal(vm.price, 15_000_000)
        }
    }

    static func run(_ log: LabLog) async {
        await runMatrix(log, test: test)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func test(_ vm: T02PriceLoading, _ t: T02Expect) async {
        await vm.load()                                     // 等 load() 跑完，再檢查
        t.equal(vm.price, 15_000_000)
    }

    static func run(_ log: LabLog) async {
        await runMatrix(log, test: test)
    }
}

// MARK: - 演練設定
extension Testing02iOSTesting {
    static let l02 = Lab(
        id: "L02",
        title: "程式壞了，測試卻顯示通過",
        question: "第 2 題：非同步的程式要怎麼測？",
        goal: "看測試裡的 Task 沒有 await，斷言在測試結束後才執行，失敗沒有人看到；再改成直接 await。",
        steps: [
            "選「Bug 版」執行，看「被改壞的實作」：測試是通過還是失敗？有沒有「測試結束後才收到的失敗」？",
            "想一想：XCTest 的 `XCTestExpectation` 建立了卻沒有 `await fulfillment(of:)`，是不是同一種問題？",
            "選「修正版」執行：被改壞的實作要讓測試失敗。",
        ],
        expectBug: "正確的實作與被改壞的實作，測試都「通過 1、失敗 0、檢查了 0 項」；被改壞的實作另外印出「測試結束後才收到 1 個失敗，沒有被算進結果」。",
        expectFixed: "正確的實作：通過 1、檢查了 1 項；被改壞的實作：失敗 1（預期 15000000，實際 0）。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let good = T02Summary.parse(lines, label: "正確的實作"),
                  let broken = T02Summary.parse(lines, label: "被改壞的實作") else { return "找不到摘要：要用 runMatrix 跑" }
            if good.failed > 0 { return "正確的實作也失敗了：測試的期望值是 15_000_000" }
            if broken.failed > 0 { return "被改壞的實作被抓到了：Bug 版的斷言要放在沒有 await 的 Task 裡，測試函式才會先結束" }
            return broken.lateFailures > 0 ? nil : "Bug 還沒重現：要在 Task { } 裡 await vm.load() 之後用 t.equal 檢查價格（斷言要真的執行，只是比測試結束得晚）"
        },
        checkFixed: { lines in
            guard let good = T02Summary.parse(lines, label: "正確的實作"),
                  let broken = T02Summary.parse(lines, label: "被改壞的實作") else { return "找不到摘要：要用 runMatrix 跑" }
            if good.assertions < 1 { return "測試什麼都沒檢查：await vm.load() 之後要用 t.equal 檢查 vm.price" }
            if good.failed > 0 || good.lateFailures > 0 { return "正確的實作不該失敗：期望價格是 15_000_000" }
            return broken.failed > 0 ? nil : "被改壞的實作還是顯示通過：要直接 await vm.load()，檢查寫在 await 之後"
        }
    )
}
