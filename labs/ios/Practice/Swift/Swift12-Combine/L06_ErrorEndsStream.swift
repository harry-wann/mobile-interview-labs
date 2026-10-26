// L06：刷新失敗一次，之後再按都沒反應（replaceError 放在最外層）
// 對應複習站：Swift12Combine 第 5 題「錯誤處理：Failure 型別是什麼？catch
// 要放在哪裡？」
//
// 情境：委託列表每按一次刷新（refreshTap）就呼叫 api.fetchOrders()。假的
// API 第 2 次呼叫會逾時失敗，其他都成功（回傳 2 筆委託）。使用者連按 3 次。
//
// 你要寫：
//   ① Bug 版：refreshTap 接 setFailureType(to: Error.self)、flatMap 呼叫
//     api.fetchOrders()，在最外層接 replaceError(with: [])。
//   ② 修正版：錯誤在 flatMap 裡面處理：api.fetchOrders() 接 catch，回傳
//     Empty；外層不用 setFailureType，也不用 replaceError。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift12-Combine/L06_ErrorEndsStream.swift
import Combine
import LabKit

private struct L06Timeout: Error {}

/// 刷新按鈕的點擊事件
private typealias L06Tap = PassthroughSubject<Void, Never>

/// 假的委託 API（兩個版本共用）：第 2 次呼叫逾時失敗，其他都回傳 2 筆委託
private final class L06FakeAPI {
    private(set) var calls = 0

    func fetchOrders() -> AnyPublisher<[String], Error> {
        Deferred { [self] () -> AnyPublisher<[String], Error> in
            calls += 1
            if calls == 2 {
                return Fail(error: L06Timeout()).eraseToAnyPublisher()
            }
            return Just(["買 0.01 BTC", "賣 0.5 ETH"])
                .setFailureType(to: Error.self)
                .eraseToAnyPublisher()
        }
        .eraseToAnyPublisher()
    }
}

/// 演練流程（兩個版本共用）：連按 3 次刷新，看 API、畫面、串流的狀態
private func scenario(
    _ log: LabLog,
    _ makeRows: (L06Tap, L06FakeAPI) -> AnyPublisher<[String], Never>
) {
    let api = L06FakeAPI()
    let refreshTap = L06Tap()
    // 畫面上的委託
    let rows = Swift12Combine.Box<[String]>([])
    let renders = Swift12Combine.Counter()
    let finished = Swift12Combine.Box(false)
    let cancellable = makeRows(refreshTap, api)
        .sink(
            receiveCompletion: { _ in
                finished.value = true
            },
            receiveValue: { newRows in
                rows.value = newRows
                renders.hit()
            }
        )

    // 使用者連按 3 次刷新
    for _ in 1...3 {
        refreshTap.send(())
    }
    log("按了 3 次刷新：API 被呼叫 \(api.calls) 次、畫面更新 \(renders.count) 次")
    log("最後畫面上的委託：\(rows.value.count) 筆")
    log("刷新的串流已經結束：\(finished.value ? "是" : "否")")
    withExtendedLifetime(cancellable) {}
}

// MARK: - Bug 版
private enum L06Bug {
    static func rows(
        refreshTap: L06Tap, api: L06FakeAPI
    ) -> AnyPublisher<[String], Never> {
        // TODO ①：refreshTap 先 setFailureType(to: Error.self)，再 flatMap
        // 呼叫 api.fetchOrders()，最外層接 replaceError(with: [])，
        // 最後 eraseToAnyPublisher()
        Empty().eraseToAnyPublisher()
    }

    static func run(_ log: LabLog) async {
        scenario(log, rows)
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func rows(
        refreshTap: L06Tap, api: L06FakeAPI
    ) -> AnyPublisher<[String], Never> {
        // TODO ②：refreshTap 直接 flatMap；在 flatMap 裡面，對
        // api.fetchOrders() 接 catch，錯誤時回傳 Empty（型別要寫清楚：
        // 輸出 [String]、Failure 是 Never），最後 eraseToAnyPublisher()
        Empty().eraseToAnyPublisher()
    }

    static func run(_ log: LabLog) async {
        scenario(log, rows)
    }
}

// MARK: - 演練設定
extension Swift12Combine {
    static let l06 = Lab(
        id: "L06",
        title: "刷新失敗一次，之後再按都沒反應",
        question: "第 5 題：錯誤處理：Failure 型別是什麼？catch 要放在哪裡？",
        goal: "某一次刷新失敗時，保留畫面上的資料，之後再按刷新還是要能更新。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看第一行：按了 3 次，API 只被呼叫 2 次，出現 ✓。",
            "看後兩行：列表被清空，刷新的串流已經結束。之後不管按幾次都沒反應。",
            "寫 TODO ②，選「修正版」執行：API 被呼叫 3 次，失敗的那次不更新畫面，最後還是 2 筆委託，串流沒有結束。",
            "想一想：為什麼型別系統會讓人想把 replaceError 放在最外層？",
        ],
        expectBug:
            "按了 3 次刷新：API 被呼叫 2 次、畫面更新 2 次；最後畫面上的委託：0 筆；刷新的串流已經結束：是。",
        expectFixed:
            "按了 3 次刷新：API 被呼叫 3 次、畫面更新 2 次；最後畫面上的委託：2 筆；刷新的串流已經結束：否。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard !lines.contains(where: { $0.hasPrefix("按了 3 次刷新：API 被呼叫 0 次") })
            else {
                return "刷新還沒接起來：refreshTap 要 flatMap 呼叫 api.fetchOrders()"
            }
            guard lines.contains("按了 3 次刷新：API 被呼叫 2 次、畫面更新 2 次"),
                lines.contains("刷新的串流已經結束：是")
            else {
                return "Bug 還沒重現：replaceError 要放在 flatMap 外面（最外層）"
            }
            return nil
        },
        checkFixed: { lines in
            guard !lines.contains(where: { $0.hasPrefix("按了 3 次刷新：API 被呼叫 0 次") })
            else {
                return "刷新還沒接起來：refreshTap 要 flatMap 呼叫 api.fetchOrders()"
            }
            guard lines.contains("按了 3 次刷新：API 被呼叫 3 次、畫面更新 2 次"),
                lines.contains("最後畫面上的委託：2 筆"),
                lines.contains("刷新的串流已經結束：否")
            else {
                return "第一次錯誤就結束了串流：在 flatMap 裡面對 api.fetchOrders() 接 catch，回傳 Empty"
            }
            return nil
        }
    )
}
