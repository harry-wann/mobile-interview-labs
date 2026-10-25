// L04：換一台模擬器，截圖比對就失敗（比對太嚴格，誤報）
// 對應複習站：Testing02iOSTesting 第 4
// 題「截圖比對測試適合測什麼？為什麼會誤報？」
//
// 情境：訂單簿一列的截圖比對測試。T02Renderer 把一列畫成 60×12
// 的灰階圖（用固定的演算法模擬）。
//       三種情況要判斷：① 同一台模擬器重畫一次；②
// 換另一台模擬器（字體邊緣的反鋸齒有細微差異，一小部分像素的亮度差 1 到 3）；
//       ③ 真的 bug：價格被切掉右邊 20 欄。
//       isSame(a, b) 判斷兩張圖「算不算同一個畫面」。
// Bug 版：兩張圖的每個像素都要完全一樣才算相同。② 其實畫面沒問題，
// 卻被判成不同，CI 紅燈（誤報）。
// 修正版：容忍細微差異：亮度差超過 8 的像素才算「不一樣」，不一樣的像素不超過
// 1% 就算相同。
//        ② 判成相同，③ 因為有大約 9% 的像素差很多，還是判成不同，真的 bug
// 抓得到。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：三種情況各判斷一次
private func scenario(_ log: LabLog, isSame: (T02Image, T02Image) -> Bool) {
    let reference = T02Renderer.orderBookRow(
        price: "15,000,000", device: .simulatorA)
    let again = T02Renderer.orderBookRow(
        price: "15,000,000", device: .simulatorA)
    let other = T02Renderer.orderBookRow(
        price: "15,000,000", device: .simulatorB)
    let clipped = T02Renderer.orderBookRow(
        price: "15,000,000", device: .simulatorA, clipped: true)

    let d2 = reference.diff(from: other)
    let d3 = reference.diff(from: clipped)
    log("① 同一台模擬器重畫一次：判定\(isSame(reference, again) ? "相同" : "不同")")
    log(
        "② 換另一台模擬器（\(d2.count) 個像素不一樣，最大差 \(d2.maxDelta)）：判定\(isSame(reference, other) ? "相同" : "不同")"
    )
    log(
        "③ 價格被切掉一截（\(d3.count) 個像素不一樣，最大差 \(d3.maxDelta)）：判定\(isSame(reference, clipped) ? "相同" : "不同")"
    )
}

// MARK: - Bug 版
private enum L04Bug {
    static func isSame(_ a: T02Image, _ b: T02Image) -> Bool {
        // 每個像素都要完全一樣
        a.pixels == b.pixels
    }

    static func run(_ log: LabLog) async {
        scenario(log, isSame: isSame)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func isSame(_ a: T02Image, _ b: T02Image) -> Bool {
        guard a.width == b.width, a.height == b.height else { return false }
        var different = 0
        for i in 0..<a.pixels.count where abs(a.pixels[i] - b.pixels[i]) > 8 {
            // 亮度差超過 8 才算這個像素不一樣
            different += 1
        }
        // 不一樣的像素不超過 1% 就算相同
        return Double(different) / Double(a.pixels.count) <= 0.01
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
            "選「Bug 版」執行，看 ② 換模擬器被判成什麼，以及有幾個像素不一樣、最大差多少。",
            "想一想：② 的差異最大只有 3（滿分 255），人眼看得出來嗎？③ 呢？",
            "選「修正版」執行：① ② 判成相同，③ 判成不同。把門檻改得很鬆（例如允許 50% 的像素不同）再跑一次，看 ③ 會怎樣。",
        ],
        expectBug:
            "① 相同；② 判定不同（16 個像素不一樣、最大差 3，其實畫面沒壞，誤報）；③ 判定不同（66 個像素不一樣、最大差 235）。",
        expectFixed: "① 相同；② 相同；③ 判定不同（66 個像素差很多，真的 bug 抓得到）。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("① 同一台模擬器重畫一次：判定相同") })
            else { return "同一台模擬器重畫的兩張圖應該判成相同" }
            guard
                lines.contains(where: {
                    $0.hasPrefix("② ") && $0.hasSuffix("判定不同")
                })
            else { return "Bug 還沒重現：換模擬器的兩張圖，每個像素要完全一樣才算相同，所以 ② 要被判成不同（誤報）" }
            return lines.contains(where: {
                $0.hasPrefix("③ ") && $0.hasSuffix("判定不同")
            }) ? nil : "③ 價格被切掉，應該判成不同"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("① 同一台模擬器重畫一次：判定相同") })
            else { return "同一台模擬器重畫的兩張圖應該判成相同" }
            guard
                lines.contains(where: {
                    $0.hasPrefix("② ") && $0.hasSuffix("判定相同")
                })
            else { return "② 還是誤報：亮度差 3 這種細微的差異要容忍（亮度差超過 8 才算像素不一樣）" }
            return lines.contains(where: {
                $0.hasPrefix("③ ") && $0.hasSuffix("判定不同")
            }) ? nil : "③ 價格被切掉卻判成相同：門檻太鬆了（不一樣的像素不超過 1% 才算相同）"
        }
    )
}
