// Swift02ARC 共用的小工具（和 bug 本身無關，已經寫好，不用改）：
// 模擬「打開畫面、關閉畫面」，並檢查物件有沒有跟著被釋放。
import SwiftUI
import LabKit

extension Swift02ARC {
    /// 模擬使用者「打開畫面，再關掉畫面」，回傳關掉之後物件是不是還活著：
    /// 1. `open` 建立畫面用的物件（通常是 ViewModel）
    /// 2. 放掉唯一的 strong 參考（等於畫面被關掉）
    /// 3. 等一下，看物件有沒有跟著被釋放
    ///
    /// 判斷方式用 `weak` 參考：weak 不會讓引用計數 +1，物件還活著，weak 就不是 nil。
    /// `name` 是物件在 `LiveObjects` 登記的名稱（init 加一、deinit 減一），log 只算這一次執行多出來的個數。
    @MainActor
    @discardableResult
    static func openThenClose<T: AnyObject>(
        _ log: LabLog,
        name: String,
        stayOpen: Duration = .zero,
        settle: Duration = .milliseconds(300),
        whileOpen: (T) async -> Void = { _ in },
        beforeClose: (T) -> Void = { _ in },
        open: @MainActor () -> T
    ) async -> Bool {
        let baseline = LiveObjects.shared.count(name)
        log("打開畫面")
        var screen: T? = open()
        weak let observer = screen
        log("存活中的 \(name)：\(LiveObjects.shared.count(name) - baseline)")
        if let screen { await whileOpen(screen) }     // 畫面開著的時候要做的事（可以等待）
        if stayOpen > .zero { try? await Task.sleep(for: stayOpen) }
        if let screen { beforeClose(screen) }
        screen = nil                                  // 關閉畫面：放掉唯一的 strong 參考
        log("關閉畫面")
        try? await Task.sleep(for: settle)
        let alive = observer != nil
        log("關閉畫面後，存活中的 \(name)：\(LiveObjects.shared.count(name) - baseline)（weak 參考\(alive ? "還在" : "已變成 nil")）")
        return alive
    }

    /// 畫面版的「打開畫面／關閉畫面」：按按鈕建立與釋放物件，上方即時顯示存活數。
    /// 關閉後數字沒有回到 0，就可以用 Memory Graph Debugger 看是誰抓著它。
    struct ScreenDemo<VM: AnyObject>: View {
        let name: String
        var close: @MainActor (VM) -> Void = { _ in }       // 關閉畫面時要做的事（例如 SwiftUI 的 dismantleUIView）
        let open: @MainActor () -> VM
        @State private var screen: VM?
        @State private var opened = 0

        var body: some View {
            VStack(alignment: .leading, spacing: 8) {
                LiveObjectsView(names: [name])
                HStack {
                    Button("打開畫面") {
                        screen = open()
                        opened += 1
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(screen != nil)
                    Button("關閉畫面") {
                        if let screen { close(screen) }
                        screen = nil
                    }
                    .buttonStyle(.bordered)
                    .disabled(screen == nil)
                }
                Text(screen == nil ? "畫面已關閉（共打開過 \(opened) 次）" : "畫面開啟中")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// 計算「畫面關掉之後，Timer／通知還被呼叫了幾次」
    final class HitCounter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0
        func hit() { lock.withLock { value += 1 } }
        var count: Int { lock.withLock { value } }
    }
}
