// L04：打開個人頁，App 直接當掉（漏了注入 @EnvironmentObject）
// 對應複習站：Swift07SwiftUIState 第 4 題「@Environment 和 @EnvironmentObject 是做什麼的？漏了注入會怎樣？」
//
// 情境：個人頁的 SwiftUI view 用 @EnvironmentObject 讀登入狀態 SessionStore。UIKit 的畫面要打開它，
//      所以用 UIHostingController 把個人頁包起來。UIHostingController 是一棵全新的 SwiftUI 樹，不會繼承別的畫面的環境。
// Bug 版：包進 UIHostingController 時沒有注入 SessionStore。編譯器不會提醒，畫面一畫出來讀到 session，App 就當掉：
//        No ObservableObject of type SessionStore found.
// 修正版：在 UIHostingController 的根 view 加上 .environmentObject(session)。
import SwiftUI
import LabKit

/// 登入狀態（兩個版本共用）
private final class SessionStore: ObservableObject {
    @Published var userName = "Alice"
}

/// 個人頁（兩個版本共用）：從環境依型別取出 SessionStore
private struct ProfileView: View {
    @EnvironmentObject var session: SessionStore

    var body: some View {
        let _ = SwiftUILabProbe.note("L04 個人頁", session.userName)
        Text("你好，\(session.userName)")
    }
}

// MARK: - Bug 版
private enum L04Bug {
    /// UIKit 的畫面要打開個人頁
    @MainActor static func makeProfileController() -> UIViewController {
        UIHostingController(rootView: ProfileView())       // 沒有 .environmentObject：環境裡找不到 SessionStore
    }

    @MainActor static func run(_ log: LabLog) async {
        SwiftUILabProbe.reset()
        log("用 UIHostingController 打開個人頁，沒有注入 SessionStore")
        let stage = SwiftUILabStage(viewController: makeProfileController())
        await stage.settle()
        log("個人頁畫出來了，讀到的使用者：\(SwiftUILabProbe.value("L04 個人頁"))")
        stage.remove()
    }
}

// MARK: - 修正版
private enum L04Fixed {
    @MainActor static func makeProfileController(session: SessionStore) -> UIViewController {
        UIHostingController(rootView: ProfileView().environmentObject(session))   // 每個 UIHostingController 的根 view 都要自己注入
    }

    @MainActor static func run(_ log: LabLog) async {
        SwiftUILabProbe.reset()
        log("用 UIHostingController 打開個人頁，有注入 SessionStore")
        let stage = SwiftUILabStage(viewController: makeProfileController(session: SessionStore()))
        await stage.settle()
        log("個人頁畫出來了，讀到的使用者：\(SwiftUILabProbe.value("L04 個人頁"))")
        stage.remove()
    }
}

// MARK: - 演練設定
extension Swift07SwiftUIState {
    static let l04 = Lab(
        id: "L04",
        title: "打開個人頁，App 直接當掉",
        question: "第 4 題：@Environment 和 @EnvironmentObject 是做什麼的？漏了注入會怎樣？",
        goal: "個人頁用 @EnvironmentObject 讀登入狀態，從 UIKit 用 UIHostingController 打開它。先看漏了注入會發生什麼，再補上。",
        steps: [
            "從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "選「Bug 版」執行：App 當掉，主控台印出 No ObservableObject of type SessionStore found。",
            "看 Xcode 停在哪一行：停在個人頁 body 讀 session 的那一行，不是建立 UIHostingController 的那一行。",
            "按 ■ 停止，重新執行，選「修正版」：個人頁畫得出來，讀到使用者 Alice。",
        ],
        expectBug: "App 當掉，主控台：Fatal error: No ObservableObject of type SessionStore found. A View.environmentObject(_:) for SessionStore may be missing as an ancestor of this view.",
        expectFixed: "個人頁畫出來了，讀到的使用者：Alice。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { _ in
            // 寫對的話 App 會當掉，不會走到這裡
            "App 沒有當掉：UIHostingController 的根 view 不要注入 SessionStore"
        },
        checkFixed: { lines in
            lines.contains("個人頁畫出來了，讀到的使用者：Alice") ? nil : "個人頁要畫得出來並讀到 Alice：在 UIHostingController 的根 view 注入 SessionStore"
        }
    )
}
