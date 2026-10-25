// L01：平均 FPS 看起來很好，使用者卻覺得畫面凍住過（卡頓不能只看平均 FPS）
// 對應複習站：Advanced02Jank 第 1 題「卡頓（jank、hitch）是什麼？一格畫面有多少時間可以用？」
//
// 情境：行情列表捲動 10 秒的畫面記錄：60Hz 螢幕（一格 16.7 ms），共 600 格，其中兩格花了 40 ms，另外有一格花了 800 ms（畫面凍住將近一秒）。
//        其餘 597 格都剛好 16.7 ms。這份記錄就是 MetricKit 或 Instruments 的 Animation Hitches 會給你的那種資料。
//        Apple 的分級用 hitch time ratio：每秒累積了幾毫秒的延誤。小於 5 ms/s 良好，5 到 10 ms/s 警告，大於 10 ms/s 嚴重。
// Bug 版：只看平均 FPS（格數 ÷ 總秒數），≥ 55 就判定「良好」。平均 55.3 FPS，一格 800 ms 的凍結被 597 格正常的稀釋掉了。
// 修正版：算 hitch time ratio：每一格超出 16.7 ms 的部分加起來 ÷ 總秒數，約 76 ms/s，判定「嚴重」。
import Foundation
import LabKit

private let frameBudgetMs = 16.7

/// 600 格的間隔（毫秒）：第 101、301 格是 40 ms，第 451 格是 800 ms，其餘 16.7 ms（兩個版本共用）
private let frameIntervalsMs: [Double] = {
    var frames = [Double](repeating: frameBudgetMs, count: 600)
    frames[100] = 40
    frames[300] = 40
    frames[450] = 800
    return frames
}()

/// 判定結果：等級（良好／警告／嚴重）和依據
private struct Verdict {
    var rating: String
    var detail: String
}

/// 演練流程（兩個版本共用）：記錄的概況，再用各版本的規則下判定
private func scenario(_ log: LabLog, judge: ([Double]) -> Verdict) {
    let totalMs = frameIntervalsMs.reduce(0, +)
    let avgFps = Double(frameIntervalsMs.count) / (totalMs / 1000)
    log("共 \(frameIntervalsMs.count) 格，平均 \(String(format: "%.1f", avgFps)) FPS，最長一格 \(Int(frameIntervalsMs.max() ?? 0)) ms")
    let verdict = judge(frameIntervalsMs)
    log("判定：\(verdict.rating)（\(verdict.detail)）")
}

// MARK: - Bug 版
private enum L01Bug {
    static func judge(_ frames: [Double]) -> Verdict {
        let avgFps = Double(frames.count) / (frames.reduce(0, +) / 1000)    // 只看平均 FPS
        return Verdict(rating: avgFps >= 55 ? "良好" : "嚴重", detail: "平均 \(String(format: "%.1f", avgFps)) FPS")
    }

    static func run(_ log: LabLog) async {
        scenario(log, judge: judge)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func judge(_ frames: [Double]) -> Verdict {
        let excessMs = frames.reduce(0) { $0 + max(0, $1 - frameBudgetMs) }  // 每一格超出預算的部分，加起來
        let ratio = excessMs / (frames.reduce(0, +) / 1000)                  // 每秒累積幾毫秒的延誤（ms/s）
        let rating = ratio < 5 ? "良好" : (ratio <= 10 ? "警告" : "嚴重")
        return Verdict(rating: rating, detail: "hitch time ratio \(String(format: "%.1f", ratio)) ms/s")
    }

    static func run(_ log: LabLog) async {
        scenario(log, judge: judge)
    }
}

// MARK: - 演練設定
extension Advanced02Jank {
    static let l01 = Lab(
        id: "L01",
        title: "平均 FPS 很好，使用者卻覺得畫面凍住過",
        question: "第 1 題：卡頓（jank、hitch）是什麼？一格畫面有多少時間可以用？",
        goal: "用 hitch time ratio 判斷卡頓，而不是平均 FPS：使用者感受到的是偶爾一次的長停頓。",
        steps: [
            "選「Bug 版」執行，看判定：平均 55.3 FPS 被判成「良好」，但最長一格是 800 ms。",
            "選「修正版」執行：hitch time ratio 約 76 ms/s，遠超過 10 ms/s，判定「嚴重」。",
            "進階：Instruments 選 Animation Hitches 範本，在實機錄製捲動行情列表，時間軸會標出每一次 hitch，並區分 commit hitch 與 render hitch；Xcode Organizer 的 Scrolling 指標與 MetricKit 的 `MXAnimationMetric.scrollHitchTimeRatio` 看的是線上資料。",
            "想一想：換成 120Hz 的 ProMotion 螢幕，一格的預算變多少？（見這個單元的第 1 題）",
        ],
        expectBug: "共 600 格，平均 55.3 FPS，最長一格 800 ms；判定：良好（平均 55.3 FPS）——錯的，使用者看到了一次將近一秒的凍結。",
        expectFixed: "判定：嚴重（hitch time ratio 76.5 ms/s）。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let avg = a02Number(in: lines, prefix: "共 600 格，平均 ") else { return "演練沒有跑完" }
            guard let verdict = lines.first(where: { $0.hasPrefix("判定：") }), verdict != "判定：（）" else {
                return "還沒有判定：judge 要回傳 Verdict(rating:, detail:)"
            }
            return verdict.hasPrefix("判定：良好（平均 ") ? nil : "Bug 版只看平均 FPS（格數 ÷ 總秒數）：平均 \(avg) FPS ≥ 55 就要判定「良好」"
        },
        checkFixed: { lines in
            guard let verdict = lines.first(where: { $0.hasPrefix("判定：") }) else { return "演練沒有跑完" }
            guard let ratio = a02Number(in: [verdict], prefix: "判定：嚴重（hitch time ratio ") ?? a02Number(in: [verdict], prefix: "判定：警告（hitch time ratio ")
                ?? a02Number(in: [verdict], prefix: "判定：良好（hitch time ratio ") else {
                return "判定的依據要寫成「hitch time ratio x.x ms/s」：\(verdict)"
            }
            if abs(ratio - 76.5) > 1 { return "hitch time ratio 應該約 76.5 ms/s（每一格超出 16.7 ms 的部分加起來 ÷ 總秒數），你算出 \(ratio)" }
            return verdict.hasPrefix("判定：嚴重") ? nil : "大於 10 ms/s 要判定「嚴重」：\(verdict)"
        }
    )
}
