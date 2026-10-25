import SwiftUI

enum LabRoute: Hashable {
    case unit(String)
    case lab(String, String)
}

/// App 首頁：依區列出每個單元。清單以 Solution 為準，Practice 用同樣的 id
/// 對應。
///
/// 啟動參數（截圖與自動檢查用）：
/// - `-open Swift01`、`-open Swift01 L03`：直接打開某個單元或演練；加 `-source
/// solution` 預設顯示解答
/// - `-selftest [Swift01]`：跑解答的自動檢查（Bug 版要重現問題、
/// 修正版要正確），印出結果後結束
/// - `-selftest-practice [Swift01]`：跑練習的骨架，
/// 確認不會當掉（還沒寫的會顯示 FAIL）
/// - `-run Swift01 L05 solution bug`：只跑一個版本，用來確認「Bug
/// 版真的會當掉」
/// 首頁的排法：「四大主題」是記憶體、效能、架構、UI/UX
/// 四項各自對應的演練（labs/topics.yaml），「依區」是技術分區
enum HomeMode: String, CaseIterable, Identifiable {
    case focus, section
    var id: String { rawValue }
    var title: String { self == .focus ? "四大主題" : "依區" }
}

public struct LabRootView: View {
    private let practice: [LabSection]
    private let solution: [LabSection]
    @State private var path: [LabRoute]
    @AppStorage("labs.homeMode") private var mode: HomeMode = .focus

    public init(practice: [LabSection], solution: [LabSection]) {
        self.practice = practice
        self.solution = solution
        _path = State(initialValue: LabLaunch.initialPath(solution))
    }

    public var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    Text(
                        "每個演練都有「練習」和「解答」兩份程式碼。先打開 Practice 資料夾裡的檔案，照 TODO 寫出 Bug 版和修正版，回到這裡執行；自動檢查會告訴你寫對了沒有。卡住時切到「解答」執行，或打開 Solution 資料夾裡同名的檔案對照。"
                    )
                    .font(.callout)
                    .foregroundStyle(.secondary)
                }
                Section {
                    Picker("排法", selection: $mode) {
                        ForEach(HomeMode.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                if mode == .focus {
                    focusSections
                } else {
                    ForEach(solution) { section in
                        Section(section.title) {
                            ForEach(section.units) { unit in
                                NavigationLink(value: LabRoute.unit(unit.code))
                                {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text("\(unit.code)　\(unit.title)").font(
                                            .headline)
                                        Text(
                                            unit.labs.isEmpty
                                                ? "還沒有演練"
                                                : "\(unit.labs.count) 個演練"
                                        )
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    }
                                }
                                .disabled(unit.labs.isEmpty)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Mobile Labs")
            .navigationDestination(for: LabRoute.self) { route in
                switch route {
                case .unit(let code):
                    if let unit = findUnit(code, in: solution) {
                        UnitView(unit: unit)
                    }
                case .lab(let code, let labID):
                    if let unit = findUnit(code, in: solution),
                        let lab = unit.labs.first(where: { $0.id == labID })
                    {
                        LabDetailView(
                            unitCode: code,
                            practice: findUnit(code, in: practice)?.labs.first(
                                where: { $0.id == labID }),
                            solution: lab,
                            initialSource: LabLaunch.initialSource
                        )
                    }
                }
            }
        }
        .task {
            await LabSelfTest.runIfRequested(
                practice: practice, solution: solution)
        }
    }
}

extension LabRootView {
    /// 「四大主題」：每一項一個 Section，
    /// 裡面依小標題列出要寫的演練（順序就是建議的練習順序）
    @ViewBuilder
    fileprivate var focusSections: some View {
        ForEach(Array(JdFocus.topics.enumerated()), id: \.element.id) {
            index, topic in
            Section {
                ForEach(Array(topic.groups.enumerated()), id: \.offset) {
                    _, group in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(group.title).font(.subheadline.weight(.semibold))
                        if let note = group.note {
                            Text(note).font(.caption).foregroundStyle(
                                .secondary)
                        }
                    }
                    .padding(.vertical, 2)
                    ForEach(group.labs, id: \.self) { ref in
                        if let unit = findUnit(ref.code, in: solution),
                            let lab = unit.labs.first(where: {
                                $0.id == ref.labID
                            })
                        {
                            NavigationLink(
                                value: LabRoute.lab(ref.code, ref.labID)
                            ) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(lab.title).font(.body)
                                    Text("\(ref.code) \(ref.labID)")
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            } header: {
                Text("\(index + 1)　\(topic.title)")
            } footer: {
                Text(topic.why)
            }
        }
    }
}

func findUnit(_ code: String, in sections: [LabSection]) -> LabUnit? {
    for section in sections {
        if let unit = section.units.first(where: { $0.code == code }) {
            return unit
        }
    }
    return nil
}

struct UnitView: View {
    let unit: LabUnit

    var body: some View {
        List(unit.labs) { lab in
            NavigationLink(value: LabRoute.lab(unit.code, lab.id)) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(lab.id)　\(lab.title)").font(.headline)
                    Text(lab.question).font(.caption).foregroundStyle(
                        .secondary)
                }
                .padding(.vertical, 2)
            }
        }
        .navigationTitle("\(unit.code) \(unit.title)")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 單一演練：切換「練習／解答」與「Bug 版／修正版」→ 執行 → 看 log、
/// 畫面與自動檢查結果
struct LabDetailView: View {
    let unitCode: String
    let practice: Lab?
    let solution: Lab

    @State private var source: LabSource
    @State private var variant: LabVariant = .bug
    // 用 @State 持有、不觀察：寫 log 時只有 LogConsole 重畫，demo
    // 不會跟著被重建
    @State private var log = LabLog()
    @State private var running = false
    @State private var runTask: Task<Void, Never>?
    @State private var showGuide = true
    @State private var showHint = false
    /// 每個「來源 × 版本」最近一次的檢查結果：nil = 還沒跑，"" = 符合預期，
    /// 其他 = 還差什麼
    @State private var results: [String: String] = [:]

    init(
        unitCode: String, practice: Lab?, solution: Lab,
        initialSource: LabSource
    ) {
        self.unitCode = unitCode
        self.practice = practice
        self.solution = solution
        _source = State(
            initialValue: practice == nil ? .solution : initialSource)
    }

    private var lab: Lab? { source == .practice ? practice : solution }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 8) {
                Picker("來源", selection: $source) {
                    ForEach(LabSource.allCases) { Text($0.title).tag($0) }
                }
                Picker("版本", selection: $variant) {
                    ForEach(LabVariant.allCases) { Text($0.title).tag($0) }
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)

            if let lab {
                statusBar(lab)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let lab {
                        if lab.bugCrashes && variant == .bug {
                            Label(
                                "這個 Bug 版寫對了會讓 App 當掉。Xcode 會停在出錯的那一行，看完按 ■ 停止再重新執行。",
                                systemImage: "exclamationmark.triangle.fill"
                            )
                            .font(.caption)
                            .foregroundStyle(.orange)
                        }
                        // 需要操作畫面的演練，操作區放最上面
                        if let demo = lab.demo {
                            demo(variant, log)
                                .id("\(source.rawValue)-\(variant.rawValue)")
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(10)
                                .background(
                                    .quaternary.opacity(0.5),
                                    in: RoundedRectangle(cornerRadius: 10))
                        }
                        guide(lab)
                    } else {
                        Text("練習檔案還沒建立。請先切到「解答」。")
                            .foregroundStyle(.secondary)
                    }
                }
                .padding()
            }

            Divider()
            HStack {
                if let lab, let run = lab.run {
                    Button {
                        start(lab, run)
                    } label: {
                        Label(
                            "執行\(source.title)的\(variant.title)",
                            systemImage: "play.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(running)
                }
                Button("清除 log") { log.clear() }
                    .buttonStyle(.bordered)
                Spacer()
                if running { ProgressView() }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)

            LogConsole(log: log)
                .frame(height: 200)
        }
        .navigationTitle("\(solution.id) \(solution.title)")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: source) { _, _ in runTask?.cancel() }
        .onChange(of: variant) { _, _ in runTask?.cancel() }
        .onDisappear { runTask?.cancel() }
    }

    @ViewBuilder
    private func statusBar(_ lab: Lab) -> some View {
        HStack(spacing: 12) {
            ForEach(LabVariant.allCases) { v in
                let key = "\(source.rawValue)-\(v.rawValue)"
                let text: String = {
                    if lab.run == nil && lab.demo == nil {
                        return "\(v.title)：在電腦上跑單元測試"
                    }
                    guard lab.check(v) != nil else { return "\(v.title)：看畫面判斷" }
                    switch results[key] {
                    case nil: return "\(v.title)：還沒執行"
                    case ""?: return "\(v.title)：✓ 符合預期"
                    case let reason?: return "\(v.title)：✗ \(reason)"
                    }
                }()
                Text(text)
                    .font(.caption)
                    .lineLimit(2)
                    .foregroundStyle(
                        results[key] == ""
                            ? Color.green
                            : (results[key] == nil
                                ? Color.secondary : Color.orange)
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal)
        .padding(.bottom, 6)
    }

    @ViewBuilder
    private func guide(_ lab: Lab) -> some View {
        DisclosureGroup("題目與步驟", isExpanded: $showGuide) {
            VStack(alignment: .leading, spacing: 8) {
                Text(lab.question).font(.caption).foregroundStyle(.secondary)
                Text(lab.goal)
                ForEach(Array(lab.steps.enumerated()), id: \.offset) {
                    i, step in
                    Text("\(i + 1). \(step)").font(.callout)
                }
                Text("檔案：\(lab.relativePath)")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                DisclosureGroup("提示：寫對了會看到什麼", isExpanded: $showHint) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Bug 版：\(lab.expectBug)")
                        Text("修正版：\(lab.expectFixed)")
                    }
                    .font(.callout)
                    .padding(.top, 4)
                }
            }
            .padding(.top, 6)
        }
    }

    private func start(
        _ lab: Lab, _ run: @escaping (LabVariant, LabLog) async -> Void
    ) {
        let v = variant
        let s = source
        let check = lab.check(v)
        running = true
        runTask = Task {
            log("──── \(s.title)・\(v.title) ────")
            let mark = log.mark()
            await run(v, log)
            if let check {
                let reason = check(log.texts(since: mark))
                results["\(s.rawValue)-\(v.rawValue)"] = reason ?? ""
                log(reason.map { "✗ 還沒符合預期：\($0)" } ?? "✓ 符合預期")
            }
            running = false
        }
    }
}

/// log 主控台：等寬字、自動捲到最新一行
public struct LogConsole: View {
    @ObservedObject var log: LabLog

    public init(log: LabLog) {
        self.log = log
    }

    public var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(log.lines) { line in
                        Text("+\(line.ms)ms [\(line.thread)] \(line.text)")
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(lineColor(line.text))
                            .textSelection(.enabled)
                            .id(line.id)
                    }
                }
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Color(.secondarySystemBackground))
            .onChange(of: log.lines.last?.id) { _, id in
                if let id { proxy.scrollTo(id, anchor: .bottom) }
            }
        }
    }

    private func lineColor(_ text: String) -> Color {
        if text.hasPrefix("────") { return .accentColor }
        if text.hasPrefix("✓") { return .green }
        if text.hasPrefix("✗") { return .orange }
        return .primary
    }
}

/// 啟動參數
enum LabLaunch {
    static var args: [String] { CommandLine.arguments }

    static func value(after flag: String, offset: Int = 1) -> String? {
        guard let i = args.firstIndex(of: flag), i + offset < args.count else {
            return nil
        }
        let v = args[i + offset]
        return v.hasPrefix("-") ? nil : v
    }

    static func initialPath(_ sections: [LabSection]) -> [LabRoute] {
        guard let code = value(after: "-open") else { return [] }
        if let labID = value(after: "-open", offset: 2) {
            return [.unit(code), .lab(code, labID)]
        }
        return [.unit(code)]
    }

    static var initialSource: LabSource {
        value(after: "-source").flatMap(LabSource.init(rawValue:)) ?? .practice
    }
}
