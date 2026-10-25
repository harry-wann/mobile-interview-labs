// L02：下單那一瞬間，資產頁的總額閃過少了 300,000
// 円（可用餘額與凍結餘額分開更新、分開通知）
// 對應複習站：Design03OrderAssets 第 6 題「資產的數字要怎麼算才不會出錯？」
// （所有畫面共用 BalanceStore，而且一次換一份完整的資產）
//
// 情境：資產頁顯示「可用」「凍結」和「總額 = 可用 + 凍結」。BalanceStore
// 一開始是可用 1,000,000、凍結 0。
//       使用者下了 300,000 円的限價買單之後，查詢資產的回覆是一份清單，
// 每一筆是「類型＋數量」：
//       trade（可用）700,000 在前，frozen（凍結）300,000 在後（Huobi 系 API
// 的資產查詢常見的形式：可用和凍結分成不同筆，順序和格式以交易所的 API
// 文件為準）。
//       BalanceStore 每次有變動，就通知畫面更新。
// 看畫面在這個過程中出現過哪些總額。
//
// 你要寫（BalanceStore.apply）：
//   ① Bug 版（L02Bug.Store）：available、frozen 兩個欄位各自一改就呼叫
// onPublish；apply 一筆一筆改欄位。
//   ② 修正版（L02Fixed.Store.apply）：先在一份新的資產上套用整份清單，
// 全部套完才換掉 balance、呼叫 onPublish 一次。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Design/Design03-OrderAssets/L02_AtomicBalance.swift
import LabKit

private struct Item {
    enum Kind { case trade, frozen }
    let kind: Kind
    let amount: Int
}

/// BalanceStore：收到一份資產清單
private protocol BalanceStore: AnyObject {
    func apply(_ items: [Item])
}

/// 演練流程（兩個版本共用）：Store 一通知，資產頁就記下當時的總額
private func scenario(
    _ log: LabLog,
    makeStore: (
        _ onPublish: @escaping (_ available: Int, _ frozen: Int) -> Void
    ) -> BalanceStore
) {
    var seen: [Int] = []
    let store = makeStore { available, frozen in
        seen.append(available + frozen)
        log(
            "資產頁更新：可用 \(d03Yen(available)) ＋ 凍結 \(d03Yen(frozen)) ＝ 總額 \(d03Yen(available + frozen))"
        )
    }
    log("查詢資產的回覆：trade（可用）700,000、frozen（凍結）300,000；真正的總額一直是 1,000,000")
    store.apply([
        Item(kind: .trade, amount: 700_000),
        Item(kind: .frozen, amount: 300_000),
    ])
    log(
        "最後：資產頁一共更新 \(seen.count) 次，看到的總額依序是 \(seen.isEmpty ? "（沒有）" : seen.map(d03Yen).joined(separator: " → "))"
    )
}

// MARK: - Bug 版
private enum L02Bug {
    final class Store: BalanceStore {
        private let onPublish: (Int, Int) -> Void
        // TODO ①-1：available、frozen 兩個欄位各加一個 didSet：欄位一改，
        // 就呼叫 onPublish(available, frozen)
        private var available = 1_000_000
        private var frozen = 0
        init(onPublish: @escaping (Int, Int) -> Void) {
            self.onPublish = onPublish
        }

        func apply(_ items: [Item]) {
            // TODO ①-2：一筆一筆套用清單：trade 的數量寫進 available，frozen
            // 的數量寫進 frozen
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Store(onPublish: $0) }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    final class Store: BalanceStore {
        private let onPublish: (Int, Int) -> Void
        // 一份完整的資產
        private var balance = (available: 1_000_000, frozen: 0)
        init(onPublish: @escaping (Int, Int) -> Void) {
            self.onPublish = onPublish
        }

        func apply(_ items: [Item]) {
            // TODO ②：先 var next = balance，在 next 上套用每一筆（trade 寫進
            // next.available，frozen 寫進 next.frozen）；
            //         全部套完才 balance = next，再呼叫一次 onPublish(balance.
            // available, balance.frozen)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Store(onPublish: $0) }
    }
}

// MARK: - 演練設定
extension Design03OrderAssets {
    static let l02 = Lab(
        id: "L02",
        title: "下單那一瞬間，總資產閃過少了 30 萬",
        question: "第 6 題：資產的數字要怎麼算才不會出錯？",
        goal:
            "可用和凍結是同一份資產的兩個部分，要一起換、一起通知畫面。分開更新，畫面會看到「可用已經扣掉、凍結還沒加上」的中間狀態，總額短暫少一截。先寫出分開更新的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Design/Design03-OrderAssets/L02_AtomicBalance.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看資產頁更新了幾次、每次的總額是多少。",
            "想一想：真正的總額從頭到尾都是 1,000,000，為什麼畫面看到過 700,000？（可用先改、凍結後改，中間畫面就被通知了。）",
            "寫 TODO ②（修正版），選「修正版」執行：只更新 1 次，總額 1,000,000。",
            "想一想：SwiftUI 的 @Published 若有兩個欄位分開更新，會有一樣的問題嗎？（同一個 run loop 內連續改兩個欄位，通常會合併成一次畫面更新；但 Combine 的 sink、跨 await 的更新不會合併。）",
        ],
        expectBug:
            "資產頁更新 2 次，總額先掉到 700,000 再彈回 1,000,000：「最後：資產頁一共更新 2 次，看到的總額依序是 700,000 → 1,000,000」。",
        expectFixed:
            "資產頁只更新 1 次，總額一直是 1,000,000：「最後：資產頁一共更新 1 次，看到的總額依序是 1,000,000」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, makeStore:) 執行"
            }
            if last.hasPrefix("最後：資產頁一共更新 0 次") {
                return
                    "apply 還沒套用清單：一筆一筆把 trade 寫進 available、frozen 寫進 frozen，欄位的 didSet 要呼叫 onPublish"
            }
            return last == "最後：資產頁一共更新 2 次，看到的總額依序是 700,000 → 1,000,000"
                ? nil
                : "Bug 還沒重現：available 與 frozen 各自一改就 onPublish（didSet），apply 一筆一筆改，畫面會看到總額先掉到 700,000"
        },
        checkFixed: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, makeStore:) 執行"
            }
            if last.hasPrefix("最後：資產頁一共更新 0 次") {
                return "apply 還沒套用清單：在 next 上套用每一筆，換掉 balance 之後呼叫 onPublish 一次"
            }
            return last == "最後：資產頁一共更新 1 次，看到的總額依序是 1,000,000"
                ? nil
                : "先在一份新的資產 next 上套用整份清單，全部套完才 balance = next，再呼叫一次 onPublish"
        }
    )
}
