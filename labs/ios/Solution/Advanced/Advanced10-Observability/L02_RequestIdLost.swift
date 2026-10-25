// L02：同時處理三個請求，log 上的 request id 卻對不上自己的請求（async 邊界上
// id 遺失、被別的請求蓋掉）
// 對應複習站：Advanced10Observability 第 1 題「可觀測性是什麼？log、指標、
// 追蹤各回答什麼問題？」（每筆 log 都帶關聯 ID）
//
// 情境：每個請求進來時，App 要替它記一個 request id，之後這個請求相關的所有
// log 都帶著這個 id，才能把一次操作從 App 串到伺服器。
//       演練同時處理三個請求（req-1、req-2、req-3），每個請求「開始 → 等回應 →
// 收到回應 → 寫入資料庫 → 背景寫入」，
//       每一行 log 前面印出「logger 認為現在是哪個 request id」，
// 後面寫這一行真正屬於哪個請求，兩邊對得上才正確。
// Bug 版：request id 放在一個全域變數。請求開始時設定，之後的 log 都讀它；
// 但三個請求交錯執行，後來的請求把值蓋掉，
//        先開始的請求醒來後印出別人的 id。
// 修正版：request id 放在 @TaskLocal。值綁在「這個請求的非同步工作」上，await
// 前後、子工作（Task { }）都讀得到同一個值，不同請求互不影響。
import Foundation
import LabKit

/** 收集 logger 的輸出與對不上的行數（兩個版本共用） */
private final class Collector: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var lines: [String] = []
    private(set) var mismatches = 0

    func emit(currentId: String, owner: String, message: String) {
        lock.withLock {
            lines.append("[\(currentId)] \(owner) 的\(message)")
            if currentId != owner { mismatches += 1 }
        }
    }
}

/// 演練流程（兩個版本共用）：withRequest 讓 body 在「這個請求的環境」裡執行；
/// currentId 讀目前的 request id
private func scenario(
    _ log: LabLog,
    withRequest:
        @escaping @Sendable (String, @Sendable () async -> Void) async -> Void,
    currentId: @escaping @Sendable () -> String
) async {
    let collector = Collector()

    func handle(_ owner: String, startDelayMs: Int, waitMs: Int) async {
        try? await Task.sleep(for: .milliseconds(startDelayMs))
        await withRequest(owner) {
            collector.emit(currentId: currentId(), owner: owner, message: "開始")
            // 等伺服器回應
            try? await Task.sleep(for: .milliseconds(waitMs))
            collector.emit(
                currentId: currentId(), owner: owner, message: "收到回應")
            // 子工作
            await Task {
                collector.emit(
                    currentId: currentId(), owner: owner, message: "背景寫入")
            }.value
        }
    }

    await withTaskGroup(of: Void.self) { group in
        group.addTask { await handle("req-1", startDelayMs: 0, waitMs: 200) }
        group.addTask { await handle("req-2", startDelayMs: 30, waitMs: 120) }
        group.addTask { await handle("req-3", startDelayMs: 60, waitMs: 160) }
    }
    for line in collector.lines { log(line) }
    log(
        "共 \(collector.lines.count) 行 log，request id 對不上的有 \(collector.mismatches) 行"
    )
}

// MARK: - Bug 版
private enum L02Bug {
    nonisolated(unsafe) static var requestId = "—"

    static func run(_ log: LabLog) async {
        await scenario(
            log,
            withRequest: { id, body in
                // 設定全域變數，之後的 log 都讀它
                requestId = id
                await body()
            },
            currentId: { requestId }
        )
    }
}

// MARK: - 修正版
private enum L02Fixed {
    @TaskLocal static var requestId = "—"

    static func run(_ log: LabLog) async {
        await scenario(
            log,
            withRequest: { id, body in
                // 值綁在這個請求的非同步工作上
                await $requestId.withValue(id) { await body() }
            },
            currentId: { requestId }
        )
    }
}

// MARK: - 演練設定
extension Advanced10Observability {
    static let l02 = Lab(
        id: "L02",
        title: "三個請求同時跑，log 的 request id 對不上",
        question: "第 1 題：可觀測性是什麼？log、指標、追蹤各回答什麼問題？（每筆 log 都帶關聯 ID）",
        goal:
            "request id 要跟著「這個請求的非同步工作」走，await 之後、子工作裡都讀得到自己的 id，不能放在大家共用的全域變數。",
        steps: [
            "選「Bug 版」執行，看每一行前面的 [id] 和後面的「req-N 的…」是不是同一個。",
            "想一想：三個請求交錯執行時，全域變數被誰設定？req-1 等回應醒來的時候，變數裡是誰的 id？",
            "選「修正版」執行：用 @TaskLocal 綁定 request id，包含 Task { } 開出來的子工作。",
            "進階：Android 用 CoroutineContext（ThreadLocal.asContextElement）做同樣的事；跨 App 與伺服器則是把 id 放進 X-Request-ID 標頭。",
        ],
        expectBug:
            "多行的 [id] 和後面的請求對不上（例如「[req-3] req-1 的收到回應」），「共 9 行 log，request id 對不上的有 4 行」：三個請求醒來後讀到的都是最後設定的 req-3。",
        expectFixed: "每一行都對得上：「共 9 行 log，request id 對不上的有 0 行」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard
                lines.contains(where: {
                    $0.hasPrefix("共 ") && $0.contains("request id 對不上的有")
                })
            else { return "演練沒有跑完：要用 scenario 執行" }
            for n in 1...3 where !lines.contains("[req-\(n)] req-\(n) 的開始") {
                return "請求一開始就要把 request id 設定好：「開始」那一行的 [id] 應該是自己的 req-\(n)"
            }
            if lines.contains(where: { $0.hasSuffix("對不上的有 0 行") }) {
                return "Bug 還沒重現：request id 要放在全域變數，請求開始時設定，後來的請求會把它蓋掉"
            }
            return nil
        },
        checkFixed: { lines in
            guard
                lines.contains(where: {
                    $0.hasPrefix("共 ") && $0.contains("request id 對不上的有")
                })
            else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("共 9 行 log，request id 對不上的有 0 行") {
                return
                    "還有對不上的行：request id 要用 @TaskLocal，並用 $requestId.withValue(id) { … } 包住整個請求的處理"
            }
            return nil
        }
    )
}
