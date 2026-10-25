// 自動檢查 SwiftUI／UIKit 畫面用的工具（Swift07–09 最先用到，其他區也可以用）。
// 按「執行」時沒有人操作畫面，
// 所以用下面這幾個工具把畫面放進螢幕外的視窗跑起來，再把結果寫進 log，
// 自動檢查才有東西可以看。
// - SwiftUILabStage：把一個 SwiftUI view（或 UIViewController）
// 放進螢幕外的視窗（body、@StateObject、.task 都會照常運作）。
// - SwiftUILabTally：數「某個物件建立了幾次」（BodyCount 只數 body，這個數
// init）。
// - SwiftUILabProbe：讓 body 把「畫面上現在顯示什麼」記下來，自動檢查再讀出來。
import SwiftUI
import UIKit

/// 把 SwiftUI view 放進螢幕外的視窗執行
@MainActor
public final class SwiftUILabStage {
    private var host: UIViewController?
    private var window: UIWindow?

    public convenience init<Content: View>(
        _ content: Content, size: CGSize = CGSize(width: 390, height: 800)
    ) {
        self.init(
            viewController: UIHostingController(rootView: AnyView(content)),
            size: size)
    }

    /// 直接放一個 UIViewController（例如自己用 UIHostingController 包好的）
    public init(
        viewController: UIViewController,
        size: CGSize = CGSize(width: 390, height: 800)
    ) {
        host = viewController
        if let scene = UIApplication.shared.connectedScenes.compactMap({
            $0 as? UIWindowScene
        }).first {
            let window = UIWindow(windowScene: scene)
            // 放在螢幕外，不干擾畫面
            window.frame = CGRect(origin: CGPoint(x: 0, y: 10_000), size: size)
            window.rootViewController = host
            window.isHidden = false
            self.window = window
        }
    }

    /// 讓主執行緒的 run loop 跑一下，SwiftUI 才會處理剛才的狀態變更
    public func settle(ms: Int = 120) async {
        try? await Task.sleep(for: .milliseconds(ms))
    }

    /// 等到條件成立（最多 timeoutMs 毫秒），再多等一下讓畫面更新。
    /// 條件寫「背景工作做完了」，不要寫死等多久
    public func settle(
        until condition: () -> Bool, timeoutMs: Int = 3_000
    ) async {
        var waited = 0
        while !condition() && waited < timeoutMs {
            try? await Task.sleep(for: .milliseconds(20))
            waited += 20
        }
        await settle(ms: 200)
    }

    /// 關掉這個畫面：視窗拿掉、UIHostingController 釋放，SwiftUI 的 view
    /// 會「消失」並被銷毀（.task 會被取消）
    public func remove() {
        window?.rootViewController = nil
        window?.isHidden = true
        window = nil
        host = nil
    }
}

/// 數「建立了幾次」。可以從任何執行緒呼叫；畫面上的數字用 SwiftUILabTallyView
/// 顯示
public final class SwiftUILabTally: ObservableObject, @unchecked Sendable {
    public static let shared = SwiftUILabTally()

    @Published public private(set) var counts: [String: Int] = [:]
    private let lock = NSLock()
    private var storage: [String: Int] = [:]

    public func add(_ name: String) {
        lock.lock()
        storage[name, default: 0] += 1
        let snapshot = storage
        lock.unlock()
        DispatchQueue.main.async { self.counts = snapshot }
    }

    public func count(_ name: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return storage[name, default: 0]
    }

    public func reset() {
        lock.lock()
        storage.removeAll()
        lock.unlock()
        DispatchQueue.main.async { self.counts = [:] }
    }
}

public struct SwiftUILabTallyView: View {
    @ObservedObject private var tally = SwiftUILabTally.shared
    let names: [String]

    public init(names: [String]) {
        self.names = names
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(names, id: \.self) { name in
                Text("\(name) 建立了 \(tally.counts[name, default: 0]) 次")
                    .font(.system(.caption, design: .monospaced))
            }
        }
    }
}

/// 讓 body 記下「畫面上現在顯示什麼」：在 body 裡寫 `let _ = SwiftUILabProbe.
/// note("名稱", "內容")`。
/// 用普通的 class 存，不會因為記錄本身造成畫面重算
public final class SwiftUILabProbe: @unchecked Sendable {
    public static let shared = SwiftUILabProbe()

    private let lock = NSLock()
    private var storage: [String: String] = [:]

    @discardableResult
    public static func note(_ name: String, _ text: String) -> Bool {
        shared.lock.lock()
        shared.storage[name] = text
        shared.lock.unlock()
        return true
    }

    public static func value(_ name: String) -> String {
        shared.lock.lock()
        defer { shared.lock.unlock() }
        return shared.storage[name] ?? "（沒有）"
    }

    public static func reset() {
        shared.lock.lock()
        shared.storage.removeAll()
        shared.lock.unlock()
    }
}

/// 每 0.25 秒讀一次 SwiftUILabProbe 的值並顯示（畫面類演練用來對照「實際的值」
/// 和「畫面上的值」）
public struct SwiftUILabProbeText: View {
    let title: String
    let name: String

    public init(title: String, name: String) {
        self.title = title
        self.name = name
    }

    public var body: some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { _ in
            Text("\(title)：\(SwiftUILabProbe.value(name))")
                .font(.system(.caption, design: .monospaced))
        }
    }
}

/// 取得 property wrapper 的名稱（例如 `State`、`StateObject`、
/// `ObservedObject`），用 Mirror 檢查 view 是怎麼宣告屬性的
public func swiftUILabWrapperName(of view: Any, property: String) -> String {
    let children = Mirror(reflecting: view).children
    guard let child = children.first(where: { $0.label == "_\(property)" })
    else {
        return children.contains(where: { $0.label == property })
            ? "（沒有 property wrapper）" : "（沒有這個屬性）"
    }
    let full = String(describing: type(of: child.value))
    return full.split(separator: "<").first.map(String.init) ?? full
}
