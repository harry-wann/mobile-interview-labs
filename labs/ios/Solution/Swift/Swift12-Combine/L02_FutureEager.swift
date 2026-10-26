// L02：還沒打開畫面就發了請求，失敗後重試也沒用（Future 一建立就執行）
// 對應複習站：Swift12Combine 第 2 題「Single、Completable、Observable.create
// 在 Combine 怎麼寫？為什麼 Future 要包 Deferred？」
//
// 情境：資產畫面的 ViewModel 先把「取得餘額」的 publisher 組好，使用者
// 0.1 秒後才打開畫面、開始訂閱，失敗時 retry(2)。假的 API 第 1 次請求會
// 逾時失敗，第 2 次之後成功。
// Bug 版：照 RxSwift 的 Single.create 直接翻成 Future。Future 的 closure
//        在建立時就執行：還沒人訂閱就發了請求；結果會被記住，retry 重新
//        訂閱的是同一個已經失敗的 Future，伺服器只被呼叫 1 次，畫面顯示失敗。
// 修正版：外面包一層 Deferred：每次被訂閱才建立新的 Future，沒人訂閱不發
//        請求，retry 時會重新發請求，行為才和 Single 一樣。
import Combine
import Foundation
import LabKit

private struct L02Timeout: Error {}

/// 假的餘額 API（兩個版本共用）：第 1 次請求逾時失敗，之後都成功；
/// 30 毫秒後在背景執行緒回呼，像真的網路請求
private final class L02FakeAPI: @unchecked Sendable {
    private let calls = Swift12Combine.Counter()
    var callCount: Int { calls.count }

    func request(_ done: @escaping (Result<Int, Error>) -> Void) {
        let n = calls.next()
        let deadline = DispatchTime.now() + .milliseconds(30)
        DispatchQueue.global().asyncAfter(deadline: deadline) {
            if n == 1 {
                done(.failure(L02Timeout()))
            } else {
                done(.success(1_200_000))
            }
        }
    }
}

/// 演練流程（兩個版本共用）：先組好 publisher，0.1 秒後才訂閱，
/// 失敗就重試 2 次
private func scenario(
    _ log: LabLog,
    _ fetchBalance: (L02FakeAPI) -> AnyPublisher<Int, Error>
) async {
    let api = L02FakeAPI()
    // ViewModel 先把 publisher 組好；使用者還沒打開畫面，沒有人訂閱
    let balance = fetchBalance(api)
    try? await Task.sleep(for: .milliseconds(100))
    log("還沒訂閱：API 被呼叫 \(api.callCount) 次")

    // 使用者打開資產畫面：開始訂閱，失敗就重試 2 次
    let shown = Swift12Combine.Box<String?>(nil)
    let cancellable =
        balance
        .retry(2)
        .sink(
            receiveCompletion: { completion in
                if case .failure = completion {
                    shown.value = "失敗"
                }
            },
            receiveValue: { value in
                shown.value = "餘額 \(value)"
            }
        )
    await Swift12Combine.waitUntil { shown.value != nil }
    log("畫面顯示：\(shown.value ?? "（逾時，沒有結果）")")
    log("API 一共被呼叫 \(api.callCount) 次")
    // 訂閱要活到這裡（Release 建置的區域變數用完最後一次就會被釋放）
    withExtendedLifetime(cancellable) {}
}

// MARK: - Bug 版
private enum L02Bug {
    static func fetchBalance(_ api: L02FakeAPI) -> AnyPublisher<Int, Error> {
        // Future 的 closure 在建立時就執行：呼叫 fetchBalance 的當下就發請求；
        // 結果會被記住，之後的每個訂閱者（包括 retry）拿到的都是同一個結果
        Future<Int, Error> { promise in
            api.request(promise)
        }
        .eraseToAnyPublisher()
    }

    static func run(_ log: LabLog) async {
        await scenario(log, fetchBalance)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func fetchBalance(_ api: L02FakeAPI) -> AnyPublisher<Int, Error> {
        // 每次被訂閱才建立一個新的 Future（對應 Single 的「訂閱時才執行」）
        Deferred {
            Future<Int, Error> { promise in
                api.request(promise)
            }
        }
        .eraseToAnyPublisher()
    }

    static func run(_ log: LabLog) async {
        await scenario(log, fetchBalance)
    }
}

// MARK: - 演練設定
extension Swift12Combine {
    static let l02 = Lab(
        id: "L02",
        title: "還沒打開畫面就發了請求，重試也沒用",
        question:
            "第 2 題：Single、Completable、Observable.create 在 Combine 怎麼寫？為什麼 Future 要包 Deferred？",
        goal: "使用者打開畫面才發請求；第一次失敗時，retry 要真的重新發請求並拿到餘額。",
        steps: [
            "選「Bug 版」執行，看第一行：還沒有人訂閱，API 已經被呼叫 1 次。",
            "看最後兩行：retry(2) 沒有重新發請求，畫面顯示失敗。",
            "選「修正版」執行：還沒訂閱時 API 被呼叫 0 次；訂閱後第 1 次失敗、retry 重發第 2 次，畫面顯示餘額。",
            "想一想：RxSwift 的 Single 是什麼時候執行的？Future 呢？",
        ],
        expectBug: "還沒訂閱：API 被呼叫 1 次；畫面顯示：失敗；API 一共被呼叫 1 次。",
        expectFixed:
            "還沒訂閱：API 被呼叫 0 次；畫面顯示：餘額 1200000；API 一共被呼叫 2 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("還沒訂閱：API 被呼叫 1 次") else {
                return "Bug 還沒重現：fetchBalance 要回傳 Future（不要包 Deferred），還沒訂閱就會發請求"
            }
            guard lines.contains("畫面顯示：失敗") else {
                return "Bug 還沒重現：retry 重新訂閱同一個 Future，畫面應該顯示失敗"
            }
            return lines.last == "API 一共被呼叫 1 次"
                ? nil : "Bug 還沒重現：API 應該只被呼叫 1 次"
        },
        checkFixed: { lines in
            guard lines.contains("還沒訂閱：API 被呼叫 0 次") else {
                return "還沒訂閱就發了請求：用 Deferred 包住 Future"
            }
            guard lines.contains("畫面顯示：餘額 1200000") else {
                return "訂閱後要發請求，失敗時 retry 要重新發請求：fetchBalance 回傳 Deferred { Future { … } }"
            }
            return lines.last == "API 一共被呼叫 2 次"
                ? nil : "API 應該被呼叫 2 次：第 1 次失敗，retry 重發第 2 次"
        }
    )
}
