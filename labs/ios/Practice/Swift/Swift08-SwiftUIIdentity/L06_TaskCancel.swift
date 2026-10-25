// L06：離開行情畫面之後，背景還在一直打 API（onAppear 裡開的 Task 不會被取消）
// 對應複習站：Swift08SwiftUIIdentity 第 6 題「`.task` 什麼時候開始、
// 什麼時候取消？」
//
// 情境：行情畫面出現時開始輪詢報價（每 50 毫秒一次，最多 40 次）。
// 使用者離開這個畫面之後，輪詢應該要停。
//
// 你要寫：
//   ① Bug 版：在 onAppear 裡寫 Task { … } 輪詢報價。
//   ② 修正版：改用 .task 輪詢，並讓它在畫面消失時真的停下來。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift08-SwiftUIIdentity/L06_TaskCancel.swift
import SwiftUI
import LabKit

/// 輪詢一次報價（兩個版本共用）：只記下輪詢的次數
private func pollQuote(_ counter: String) {
    SwiftUILabTally.shared.add(counter)
}

/// 演練流程（兩個版本共用）：畫面出現 → 輪詢一小段時間 → 使用者離開畫面 → 再等
/// 0.5 秒看輪詢有沒有停
@MainActor
private func scenario(_ log: LabLog, screen: (String) -> some View) async {
    // 每次執行用不同的計數名稱：上一次 Bug 版漏掉的 Task 還在跑，不能算進這一次
    let counter = "L06 輪詢 \(UUID().uuidString.prefix(6))"
    let stage = SwiftUILabStage(screen(counter))
    await stage.settle(ms: 300)
    let before = SwiftUILabTally.shared.count(counter)
    log("畫面出現 0.3 秒：輪詢了 \(before) 次")
    // 使用者離開這個畫面
    stage.remove()
    await stage.settle(ms: 500)
    let after = SwiftUILabTally.shared.count(counter)
    log("離開畫面 0.5 秒後：又多輪詢了 \(after - before) 次")
}

/// 畫面類演練的操作區（兩個版本共用）：開關行情畫面，
/// 看輪詢的次數還有沒有繼續增加
private struct L06Demo: View {
    let variant: LabVariant
    @State private var showing = true

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle("顯示行情畫面", isOn: $showing)
            if showing {
                switch variant {
                case .bug: L06Bug.Screen(counter: "L06 輪詢")
                case .fixed: L06Fixed.Screen(counter: "L06 輪詢")
                }
            }
            SwiftUILabTallyView(names: ["L06 輪詢"])
        }
    }
}

// MARK: - Bug 版
private enum L06Bug {
    struct Screen: View {
        let counter: String

        var body: some View {
            Text("BTC/JPY 行情")
                // TODO ①：在 onAppear 裡開一個 Task，用 for _ in 0..<40 輪詢：
                // 每次呼叫 pollQuote(counter)，再用 Task.sleep 等 50 毫秒。
                //        這個 Task 沒有人會取消，離開畫面之後還是會跑完
                .onAppear {}
        }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    struct Screen: View {
        let counter: String

        var body: some View {
            Text("BTC/JPY 行情")
                // TODO ②：改用 task：一樣輪詢 40 次、每次等 50 毫秒。view
                // 消失時它會被自動取消，
                //        但 Task.sleep 用了 try? 不會中斷迴圈，
                // 所以迴圈裡要自己檢查 Task.isCancelled，被取消就 break
                .onAppear {}
        }
    }
}

// MARK: - 演練設定
extension Swift08SwiftUIIdentity {
    static let l06 = Lab(
        id: "L06",
        title: "離開畫面之後，背景還在一直輪詢",
        question: "第 6 題：`.task` 什麼時候開始、什麼時候取消？",
        goal: "行情畫面出現時開始輪詢報價，離開畫面之後輪詢要停。先寫出離開後還在輪詢的 Bug 版，再用 .task 修好。",
        steps: [
            "打開 Practice/Swift/Swift08-SwiftUIIdentity/L06_TaskCancel.swift，寫 TODO ①。",
            "選「練習」「Bug 版」，把操作區的「顯示行情畫面」關掉：「L06 輪詢建立了 N 次」還在往上加（最多 40）。",
            "按「執行」，看 log：離開畫面 0.5 秒後，又多輪詢了幾次。",
            "寫 TODO ②，選「修正版」，同樣關掉畫面：次數立刻停住。",
            "進階：在 Bug 版加上 Task 不取消的 print，用 Xcode 的 Debug navigator 看離開畫面後還有幾條 Task 在跑。",
        ],
        expectBug: "離開畫面 0.5 秒後，又多輪詢了約 10 次：Task 沒有被取消，一直跑到 40 次才停。",
        expectFixed: "離開畫面後輪詢立刻停住（又多輪詢 0 次）。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log) { L06Bug.Screen(counter: $0) }
            case .fixed: await scenario(log) { L06Fixed.Screen(counter: $0) }
            }
        },
        demo: { variant, _ in AnyView(L06Demo(variant: variant)) },
        checkBug: { lines in
            guard
                let before = lines.first(where: {
                    $0.hasPrefix("畫面出現 0.3 秒：輪詢了 ")
                }),
                let b = Int(
                    before.dropFirst("畫面出現 0.3 秒：輪詢了 ".count).prefix(
                        while: \.isNumber)), b >= 3
            else { return "畫面出現之後要開始輪詢（0.3 秒內至少 3 次）" }
            guard
                let after = lines.first(where: {
                    $0.hasPrefix("離開畫面 0.5 秒後：又多輪詢了 ")
                }),
                let a = Int(
                    after.dropFirst("離開畫面 0.5 秒後：又多輪詢了 ".count).prefix(
                        while: \.isNumber))
            else { return "還沒有離開畫面後的輪詢次數" }
            if a < 5 {
                return
                    "Bug 還沒重現：離開畫面之後，輪詢還要繼續跑（目前多輪詢 \(a) 次）：用 onAppear 裡開的 Task，不會被取消"
            }
            return nil
        },
        checkFixed: { lines in
            guard
                let before = lines.first(where: {
                    $0.hasPrefix("畫面出現 0.3 秒：輪詢了 ")
                }),
                let b = Int(
                    before.dropFirst("畫面出現 0.3 秒：輪詢了 ".count).prefix(
                        while: \.isNumber)), b >= 3
            else { return "畫面出現之後要開始輪詢（0.3 秒內至少 3 次）" }
            guard
                let after = lines.first(where: {
                    $0.hasPrefix("離開畫面 0.5 秒後：又多輪詢了 ")
                }),
                let a = Int(
                    after.dropFirst("離開畫面 0.5 秒後：又多輪詢了 ".count).prefix(
                        while: \.isNumber))
            else { return "還沒有離開畫面後的輪詢次數" }
            if a > 1 {
                return "離開畫面之後還多輪詢了 \(a) 次：輪詢要在畫面消失時自動取消，迴圈要檢查 Task.isCancelled"
            }
            return nil
        }
    )
}
