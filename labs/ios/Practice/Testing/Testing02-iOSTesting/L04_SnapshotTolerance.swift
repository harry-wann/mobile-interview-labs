// L04：換一台模擬器，截圖比對就失敗（比對太嚴格，誤報）
// 對應複習站：Testing02iOSTesting 第 4 題「截圖比對測試適合測什麼？為什麼會誤報？」
//
// 情境：訂單簿一列的截圖比對測試。T02Renderer 把一列畫成 60×12 的灰階圖（用固定的演算法模擬）。
//       三種情況要判斷：① 同一台模擬器重畫一次；② 換另一台模擬器（字體邊緣的反鋸齒有細微差異，一小部分像素的亮度差 1 到 3）；
//       ③ 真的 bug：價格被切掉右邊 20 欄。
//       isSame(a, b) 判斷兩張圖「算不算同一個畫面」。
//
// 你要寫：
//   ① Bug 版：isSame 比較兩張圖的 pixels 陣列，每個像素都要完全一樣才算相同。
//   ② 修正版：先確認寬高一樣；逐一比較像素，亮度差超過 8 才算「這個像素不一樣」；不一樣的像素不超過 1% 就算相同。
// 只改有 TODO 的地方。解答：Solution/Testing/Testing02-iOSTesting/L04_SnapshotTolerance.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：三種情況各判斷一次
private func scenario(_ log: LabLog, isSame: (T02Image, T02Image) -> Bool) {
    let reference = T02Renderer.orderBookRow(price: "15,000,000", device: .simulatorA)
    let again = T02Renderer.orderBookRow(price: "15,000,000", device: .simulatorA)
    let other = T02Renderer.orderBookRow(price: "15,000,000", device: .simulatorB)
    let clipped = T02Renderer.orderBookRow(price: "15,000,000", device: .simulatorA, clipped: true)

    let d2 = reference.diff(from: other)
    let d3 = reference.diff(from: clipped)
    log("① 同一台模擬器重畫一次：判定\(isSame(reference, again) ? "相同" : "不同")")
    log("② 換另一台模擬器（\(d2.count) 個像素不一樣，最大差 \(d2.maxDelta)）：判定\(isSame(reference, other) ? "相同" : "不同")")
    log("③ 價格被切掉一截（\(d3.count) 個像素不一樣，最大差 \(d3.maxDelta)）：判定\(isSame(reference, clipped) ? "相同" : "不同")")
}

// MARK: - Bug 版
private enum L04Bug {
    static func isSame(_ a: T02Image, _ b: T02Image) -> Bool {
        // TODO ①：比較 a.pixels 和 b.pixels，每個像素都要完全一樣才回傳 true（目前一律回傳 true）
        true
    }

    static func run(_ log: LabLog) async {
        scenario(log, isSame: isSame)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func isSame(_ a: T02Image, _ b: T02Image) -> Bool {
        // TODO ②-1：寬高不一樣就回傳 false
        // TODO ②-2：逐一比較像素，亮度差超過 8 才算「這個像素不一樣」，數出不一樣的像素有幾個
        // TODO ②-3：不一樣的像素不超過全部的 1% 才回傳 true（目前一律回傳 true）
        return true
    }

    static func run(_ log: LabLog) async {
        scenario(log, isSame: isSame)
    }
}

// MARK: - 演練設定
extension Testing02iOSTesting {
    static let l04 = Lab(
        id: "L04",
        title: "換一台模擬器，截圖比對就失敗",
        question: "第 4 題：截圖比對測試適合測什麼？為什麼會誤報？",
        goal: "截圖比對要容忍細微的繪製差異，才不會誤報；同時門檻不能太鬆，真的版面壞掉要抓得到。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看 ② 換模擬器被判成什麼，以及有幾個像素不一樣、最大差多少。",
            "想一想：② 的差異最大只有 3（滿分 255），人眼看得出來嗎？③ 呢？",
            "寫 TODO ②，選「修正版」執行：① ② 判成相同，③ 判成不同。把門檻改得很鬆（例如允許 50% 的像素不同）再跑一次，看 ③ 會怎樣。",
        ],
        expectBug: "① 相同；② 判定不同（16 個像素不一樣、最大差 3，其實畫面沒壞，誤報）；③ 判定不同（66 個像素不一樣、最大差 235）。",
        expectFixed: "① 相同；② 相同；③ 判定不同（66 個像素差很多，真的 bug 抓得到）。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("① 同一台模擬器重畫一次：判定相同") }) else { return "同一台模擬器重畫的兩張圖應該判成相同" }
            guard lines.contains(where: { $0.hasPrefix("② ") && $0.hasSuffix("判定不同") }) else { return "Bug 還沒重現：換模擬器的兩張圖，每個像素要完全一樣才算相同，所以 ② 要被判成不同（誤報）" }
            return lines.contains(where: { $0.hasPrefix("③ ") && $0.hasSuffix("判定不同") }) ? nil : "③ 價格被切掉，應該判成不同"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("① 同一台模擬器重畫一次：判定相同") }) else { return "同一台模擬器重畫的兩張圖應該判成相同" }
            guard lines.contains(where: { $0.hasPrefix("② ") && $0.hasSuffix("判定相同") }) else { return "② 還是誤報：亮度差 3 這種細微的差異要容忍（亮度差超過 8 才算像素不一樣）" }
            return lines.contains(where: { $0.hasPrefix("③ ") && $0.hasSuffix("判定不同") }) ? nil : "③ 價格被切掉卻判成相同：門檻太鬆了（不一樣的像素不超過 1% 才算相同）"
        }
    )
}
