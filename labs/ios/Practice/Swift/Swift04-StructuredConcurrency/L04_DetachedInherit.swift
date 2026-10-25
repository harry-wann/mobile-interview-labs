// L04：下單的子任務讀不到 requestID，也不在主 actor 上（Task.detached
// 什麼都不繼承）
// 對應複習站：Swift04StructuredConcurrency 第 3 題「Task {} 和 Task.detached
// 差在哪？」
//
// 情境：使用者按下「下單」。App 為這一筆委託設定一個 requestID（task-local 值：
// 綁在 Task 上、會自動傳給底下任務的變數），
//       ViewModel 的 submit 開一個子任務送出委託，送出時要把 requestID
// 帶給伺服器，方便出問題時追查。ViewModel 標了 @MainActor。
//
// 你要寫：
//   ① Bug 版：submit 裡用 Task.detached 開子任務，印出子任務裡的 requestID
// 與是否在主執行緒。
//   ② 修正版：submit 裡改用 Task { } 開子任務，印出同樣的內容。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift04-StructuredConcurrency/L04_DetachedInherit.swift
import Foundation
import LabKit

/// 這一筆委託的 requestID：預設 none（兩個版本共用）
private enum RequestContext {
    @TaskLocal static var id = "none"
}

/// 目前是不是在主執行緒（Thread.isMainThread 不能直接寫在 async 函式裡，
/// 包成同步函式）（兩個版本共用）
private func onMainThread() -> Bool { Thread.isMainThread }

// MARK: - Bug 版
private enum L04Bug {
    @MainActor
    final class OrderViewModel {
        func submit(_ log: LabLog) async {
            // TODO ①：await Task.detached { … }.value，裡面
            // log("子任務送出委託：requestID = …，在主執行緒：…")
            //        requestID 用 RequestContext.id，是否在主執行緒用
            // onMainThread()
        }
    }

    static func run(_ log: LabLog) async {
        await RequestContext.$id.withValue("ORD-1001") {
            // 模擬使用者按下按鈕（在主 actor 上）
            await Task { @MainActor in
                log(
                    "按下下單：requestID = \(RequestContext.id)，在主執行緒：\(onMainThread())"
                )
                await OrderViewModel().submit(log)
            }.value
        }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    @MainActor
    final class OrderViewModel {
        func submit(_ log: LabLog) async {
            // TODO ②：改用 await Task { … }.value，裡面印出同樣的 log
        }
    }

    static func run(_ log: LabLog) async {
        await RequestContext.$id.withValue("ORD-1001") {
            await Task { @MainActor in
                log(
                    "按下下單：requestID = \(RequestContext.id)，在主執行緒：\(onMainThread())"
                )
                await OrderViewModel().submit(log)
            }.value
        }
    }
}

// MARK: - 演練設定
extension Swift04StructuredConcurrency {
    static let l04 = Lab(
        id: "L04",
        title: "下單的子任務讀不到 requestID",
        question: "第 3 題：Task {} 和 Task.detached 差在哪？",
        goal:
            "子任務送出委託時要讀得到這一筆委託的 requestID，而且和 ViewModel 一樣在主 actor 上。比較 Task.detached 和 Task { } 的差別。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，比較兩行 log：按下下單時 requestID 是 ORD-1001，子任務裡卻是 none，也不在主執行緒，出現 ✓。",
            "想一想：Task.detached 會繼承什麼？Task { } 又會繼承什麼？",
            "寫 TODO ②，選「修正版」執行，子任務讀得到 ORD-1001，也在主執行緒。",
            "進階：Bug 版想在子任務裡直接寫 @MainActor 屬性，會需要加什麼？（await 回到 MainActor）",
        ],
        expectBug:
            "按下下單：requestID = ORD-1001；子任務送出委託：requestID = none，在主執行緒：false。",
        expectFixed: "子任務送出委託：requestID = ORD-1001，在主執行緒：true。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("按下下單：requestID = ORD-1001，在主執行緒：true") else {
                return
                    "按下下單時應該讀得到 requestID = ORD-1001、在主執行緒（用 RequestContext.$id.withValue 設定，Task { @MainActor in … }）"
            }
            return lines.contains("子任務送出委託：requestID = none，在主執行緒：false")
                ? nil : "Bug 還沒重現：子任務應該讀到 none 而且不在主執行緒（要用 Task.detached 開子任務）"
        },
        checkFixed: { lines in
            guard lines.contains("按下下單：requestID = ORD-1001，在主執行緒：true") else {
                return
                    "按下下單時應該讀得到 requestID = ORD-1001、在主執行緒（用 RequestContext.$id.withValue 設定，Task { @MainActor in … }）"
            }
            return lines.contains("子任務送出委託：requestID = ORD-1001，在主執行緒：true")
                ? nil : "子任務應該讀得到 ORD-1001、在主執行緒執行：改用 Task { }"
        }
    )
}
