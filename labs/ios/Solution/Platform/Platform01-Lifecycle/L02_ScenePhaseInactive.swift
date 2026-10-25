// L02：下拉通知中心，行情就斷線了（.inactive 不是進背景）
// 對應複習站：Platform01Lifecycle 第 2 題「App 切到背景、回到前景，兩個平台怎麼知道？iOS 的 .inactive 又是什麼？」
//
// 情境：行情頁依 scenePhase 決定要不要連線。使用者會做三件事：下拉通知中心、跳出 Face ID 對話框、按 Home 回桌面再回來。
//        演練把這些操作對應的 scenePhase 變化，依序交給 handle(_:)（正式的 App 裡，這個函式的內容就寫在 .onChange(of: scenePhase) 裡）。
//        App 上方的操作區是同一段程式接上真正的 scenePhase：在模擬器下拉通知中心、按 Home，看 log 和畫面。
// Bug 版：.inactive 和 .background 一律斷線。下拉通知中心、跳出 Face ID 對話框，App 都會短暫進入 .inactive，行情就白白斷了又重連。
// 修正版：.inactive 只疊上遮罩（遮住餘額，多工畫面的縮圖也不會露出資產）；.background 才斷線；回到 .active 拿掉遮罩，需要的話重連。
import SwiftUI
import LabKit

/// 行情頁的狀態（兩個版本共用）
private final class Session: ObservableObject {
    @Published var connected = true
    @Published var maskOn = false
    private(set) var disconnects = 0

    func disconnect() {
        guard connected else { return }
        connected = false
        disconnects += 1
    }

    func reconnectIfNeeded() {
        if !connected { connected = true }
    }
}

/// 演練流程（兩個版本共用）：依序做三件事，每次都把 scenePhase 的變化交給 handle
@MainActor
private func scenario(_ log: LabLog, handle: (ScenePhase, Session) -> Void) async {
    let session = Session()
    var maskedWhenInactive = 0

    func feed(_ phases: [ScenePhase]) {
        for phase in phases {
            handle(phase, session)
            if phase == .inactive && session.maskOn { maskedWhenInactive += 1 }
        }
    }

    feed([.inactive, .active])                          // 下拉通知中心再收回去
    feed([.inactive, .active])                          // 跳出 Face ID 對話框再關掉
    log("通知中心與 Face ID 對話框之後：行情斷線 \(session.disconnects) 次，.inactive 時疊上遮罩 \(maskedWhenInactive) 次")
    feed([.inactive, .background])                      // 按 Home 回桌面
    log("按 Home 之後：行情連線\(session.connected ? "還連著" : "已斷開")")
    feed([.inactive, .active])                          // 從 App 切換器回來
    log("回到前景之後：行情連線\(session.connected ? "還連著" : "已斷開")，遮罩\(session.maskOn ? "還在" : "已拿掉")")
}

/// 畫面類演練的操作區（兩個版本共用）：把真正的 scenePhase 接到 handle，在模擬器下拉通知中心、按 Home 試試看
private struct L02Demo: View {
    let variant: LabVariant
    let log: LabLog
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var session = Session()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("目前的 scenePhase：\(String(describing: scenePhase))")
            Text("行情連線：\(session.connected ? "連著" : "已斷開")　遮罩：\(session.maskOn ? "開" : "關")")
            Text("在模擬器上試：從畫面最上方往下拉出通知中心；按 Home（⇧⌘H）再從 App 切換器回來。")
                .font(.caption)
        }
        .font(.system(.footnote, design: .monospaced))
        .overlay { if session.maskOn { Color.gray.opacity(0.9).overlay { Text("餘額已遮住") } } }
        .onChange(of: scenePhase) { _, newPhase in
            log("scenePhase → \(String(describing: newPhase))")
            switch variant {
            case .bug: L02Bug.handle(newPhase, session)
            case .fixed: L02Fixed.handle(newPhase, session)
            }
        }
    }
}

// MARK: - Bug 版
private enum L02Bug {
    static func handle(_ phase: ScenePhase, _ session: Session) {
        switch phase {
        case .active:
            session.reconnectIfNeeded()
        case .inactive, .background:
            session.disconnect()                    // .inactive 也斷線：下拉通知中心就斷了
        @unknown default:
            break
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log, handle: handle)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func handle(_ phase: ScenePhase, _ session: Session) {
        switch phase {
        case .active:
            session.maskOn = false
            session.reconnectIfNeeded()
        case .inactive:
            session.maskOn = true                   // 看得到但不能操作：只疊遮罩，不斷線
        case .background:
            session.disconnect()                    // 真的進背景才斷線
        @unknown default:
            break
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log, handle: handle)
    }
}

// MARK: - 演練設定
extension Platform01Lifecycle {
    static let l02 = Lab(
        id: "L02",
        title: "下拉通知中心，行情就斷線了",
        question: "第 2 題：App 切到背景、回到前景，兩個平台怎麼知道？iOS 的 .inactive 又是什麼？",
        goal: "只有真的進背景（.background）才斷開行情；.inactive 只是失去焦點，應該疊遮罩遮住餘額，不要斷線。",
        steps: [
            "選「Bug 版」按執行，看「通知中心與 Face ID 對話框之後」行情斷線了幾次。",
            "上方的操作區接的是真正的 scenePhase：在模擬器從畫面最上方往下拉出通知中心，看行情連線有沒有變成「已斷開」。",
            "選「修正版」，同樣下拉通知中心（只疊遮罩）、按 Home（⇧⌘H）再回來（斷線後重連）。",
        ],
        expectBug: "通知中心與 Face ID 對話框之後，行情斷線 2 次，.inactive 時沒有遮罩。",
        expectFixed: "通知中心與 Face ID 對話框之後，行情斷線 0 次，.inactive 時疊上遮罩 2 次；按 Home 後斷開，回到前景重連、遮罩拿掉。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        demo: { variant, log in AnyView(L02Demo(variant: variant, log: log)) },
        checkBug: { lines in
            guard let first = lines.first(where: { $0.hasPrefix("通知中心與 Face ID 對話框之後：") }) else { return "演練沒有跑完" }
            if first.contains("斷線 0 次") { return "Bug 還沒重現：.inactive 也要斷線" }
            if !lines.contains("按 Home 之後：行情連線已斷開") { return ".background 要斷線" }
            return nil
        },
        checkFixed: { lines in
            guard let first = lines.first(where: { $0.hasPrefix("通知中心與 Face ID 對話框之後：") }) else { return "演練沒有跑完" }
            if !first.contains("斷線 0 次") { return ".inactive 不該斷線，只是失去焦點" }
            if !first.contains("疊上遮罩 2 次") { return ".inactive 時要疊上遮罩（maskOn = true），遮住餘額" }
            if !lines.contains("按 Home 之後：行情連線已斷開") { return ".background 才是真的進背景，要斷線" }
            if !lines.contains("回到前景之後：行情連線還連著，遮罩已拿掉") { return "回到 .active 要拿掉遮罩並重新連線" }
            return nil
        }
    )
}
