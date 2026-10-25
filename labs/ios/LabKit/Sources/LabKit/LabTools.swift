import SwiftUI
import QuartzCore

// MARK: - 存活物件計數

/// 計算某種物件目前「還活著」幾個，用來看記憶體洩漏。
///
/// ```swift
/// final class PriceViewModel {
///     init() { LiveObjects.shared.add("PriceViewModel") }
///     deinit { LiveObjects.shared.remove("PriceViewModel") }
/// }
/// ```
/// 畫面上放 `LiveObjectsView(names: ["PriceViewModel"])` 就能即時看到數字。
public final class LiveObjects: ObservableObject, @unchecked Sendable {
    public static let shared = LiveObjects()

    @Published public private(set) var counts: [String: Int] = [:]
    private let lock = NSLock()
    private var storage: [String: Int] = [:]

    public func add(_ name: String) { change(name, +1) }
    public func remove(_ name: String) { change(name, -1) }

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

    private func change(_ name: String, _ delta: Int) {
        lock.lock()
        storage[name, default: 0] += delta
        let snapshot = storage
        lock.unlock()
        DispatchQueue.main.async { self.counts = snapshot }
    }
}

public struct LiveObjectsView: View {
    @ObservedObject private var live = LiveObjects.shared
    private let names: [String]

    public init(names: [String]) {
        self.names = names
    }

    public var body: some View {
        HStack(spacing: 12) {
            ForEach(names, id: \.self) { name in
                Text("存活中 \(name)：\(live.counts[name, default: 0])")
                    .font(.system(.caption, design: .monospaced))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(
                        live.counts[name, default: 0] > 0 ? Color.orange.opacity(0.2) : Color.green.opacity(0.2),
                        in: Capsule()
                    )
            }
        }
    }
}

// MARK: - body 計算次數

/// 計算某個 view 的 `body` 被算了幾次（SwiftUI 重算畫面的次數）。
///
/// 在 body 裡寫 `let _ = BodyCount.tick("PriceRow BTC")`，
/// 再用 `BodyCount.shared.value("PriceRow BTC")` 或 `BodyCountView` 顯示。
/// 用參考型別存，不會因為計數本身造成畫面重算。
public final class BodyCount: ObservableObject, @unchecked Sendable {
    public static let shared = BodyCount()

    @Published public private(set) var counts: [String: Int] = [:]
    private let lock = NSLock()
    private var storage: [String: Int] = [:]
    private var scheduled = false

    @discardableResult
    public static func tick(_ name: String) -> Int { shared.tick(name) }

    @discardableResult
    public func tick(_ name: String) -> Int {
        lock.lock()
        storage[name, default: 0] += 1
        let n = storage[name]!
        let needSchedule = !scheduled
        scheduled = true
        lock.unlock()
        if needSchedule {
            // 下一輪再更新顯示，避免在 body 計算途中改 @Published
            DispatchQueue.main.async {
                self.lock.lock()
                let snapshot = self.storage
                self.scheduled = false
                self.lock.unlock()
                self.counts = snapshot
            }
        }
        return n
    }

    public func value(_ name: String) -> Int {
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

public struct BodyCountView: View {
    @ObservedObject private var counter = BodyCount.shared
    private let names: [String]

    public init(names: [String]) {
        self.names = names
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(names, id: \.self) { name in
                Text("body 計算 \(name)：\(counter.counts[name, default: 0]) 次")
                    .font(.system(.caption, design: .monospaced))
            }
        }
    }
}

// MARK: - 畫面流暢度

/// 顯示目前每秒畫了幾格，以及累計「卡頓」次數（某一格花的時間超過預期的 1.5 倍）。
/// 主執行緒被卡住時，這裡的數字會掉下來。
public struct FrameMeter: View {
    @StateObject private var model = FrameMeterModel()

    public init() {}

    public var body: some View {
        HStack(spacing: 10) {
            Text("FPS \(model.fps)")
            Text("卡頓 \(model.hitches) 次")
            Text("最長一格 \(model.worstMs) ms")
            Button("歸零") { model.reset() }
                .font(.caption)
        }
        .font(.system(.caption, design: .monospaced))
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(model.fps < 50 ? Color.red.opacity(0.2) : Color.green.opacity(0.2), in: Capsule())
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }
}

@MainActor
final class FrameMeterModel: NSObject, ObservableObject {
    @Published var fps = 0
    @Published var hitches = 0
    @Published var worstMs = 0

    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private var frames = 0
    private var windowStart: CFTimeInterval = 0

    func start() {
        guard link == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() {
        link?.invalidate()
        link = nil
        last = 0
    }

    func reset() {
        hitches = 0
        worstMs = 0
    }

    @objc private func tick(_ link: CADisplayLink) {
        let now = link.timestamp
        let expected = link.targetTimestamp - link.timestamp
        if last > 0 {
            let dt = now - last
            if expected > 0, dt > expected * 1.5 { hitches += 1 }
            worstMs = max(worstMs, Int(dt * 1000))
        }
        last = now
        frames += 1
        if windowStart == 0 { windowStart = now }
        if now - windowStart >= 1 {
            fps = Int(Double(frames) / (now - windowStart) + 0.5)
            frames = 0
            windowStart = now
        }
    }
}
