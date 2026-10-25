import Foundation

// ── 測試 prelude（runner 自動置於使用者程式碼之前）──
// 輸出格式：每個測試一行  @@TEST|<1|0>|<名稱>|<說明>
nonisolated(unsafe) var __testCount = 0
nonisolated(unsafe) var __passCount = 0

func __clean(_ s: String) -> String {
    s.replacingOccurrences(of: "\n", with: " ⏎ ").replacingOccurrences(of: "|", with: "¦")
}

func __report(_ name: String, _ pass: Bool, _ detail: String) {
    __testCount += 1
    if pass { __passCount += 1 }
    print("@@TEST|\(pass ? 1 : 0)|\(__clean(name))|\(__clean(detail))")
    fflush(stdout)
}

func check(_ name: String, _ cond: () throws -> Bool) {
    do {
        let ok = try cond()
        __report(name, ok, ok ? "" : "條件不成立")
    } catch {
        __report(name, false, "拋出錯誤：\(error)")
    }
}

func checkEqual<T: Equatable>(_ name: String, _ expected: T, _ actual: () throws -> T) {
    do {
        let a = try actual()
        __report(name, a == expected, a == expected ? "" : "預期 \(expected)，實際 \(a)")
    } catch {
        __report(name, false, "拋出錯誤：\(error)")
    }
}

func checkClose(_ name: String, _ expected: Double, _ eps: Double, _ actual: () throws -> Double) {
    do {
        let a = try actual()
        let ok = abs(a - expected) <= eps
        __report(name, ok, ok ? "" : "預期 \(expected)±\(eps)，實際 \(a)")
    } catch {
        __report(name, false, "拋出錯誤：\(error)")
    }
}

func checkAsync(_ name: String, _ cond: () async throws -> Bool) async {
    do {
        let ok = try await cond()
        __report(name, ok, ok ? "" : "條件不成立")
    } catch {
        __report(name, false, "拋出錯誤：\(error)")
    }
}

func checkEqualAsync<T: Equatable>(_ name: String, _ expected: T, _ actual: () async throws -> T) async {
    do {
        let a = try await actual()
        __report(name, a == expected, a == expected ? "" : "預期 \(expected)，實際 \(a)")
    } catch {
        __report(name, false, "拋出錯誤：\(error)")
    }
}

func __testsDone() {
    print("@@DONE|\(__passCount)|\(__testCount)")
    fflush(stdout)
}
// ── prelude 結束 ──
