import SwiftUI

/// 每個演練都有兩個版本：
/// - bug：會真的出錯的寫法
/// - fixed：修正後的寫法
public enum LabVariant: String, CaseIterable, Identifiable, Sendable {
    case bug, fixed

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .bug: "Bug 版"
        case .fixed: "修正版"
        }
    }
}

/// 程式碼來源：Practice（你寫的）或 Solution（解答）
public enum LabSource: String, CaseIterable, Identifiable, Sendable {
    case practice, solution

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .practice: "練習"
        case .solution: "解答"
        }
    }
}

/// 一個演練。Practice 與 Solution 各有一份，id 相同。
///
/// - `run`：按「執行」時呼叫，從主執行緒開始。把觀察到的事情寫進 `log`。
/// - `demo`：需要畫面的演練（SwiftUI 狀態、列表捲動…）放在這裡。
/// 切換版本時會重建。
/// - `checkBug`／`checkFixed`：自動檢查。拿到這次執行的 log，回傳 `nil`
/// 代表「符合預期」，
///   否則回傳還差什麼。練習時用來告訴你寫對了沒有。
public struct Lab: Identifiable {
    public let id: String
    public let title: String
    public let question: String
    public let goal: String
    public let steps: [String]
    public let expectBug: String
    public let expectFixed: String
    /// Bug 版會讓 App 當掉（例如 unowned 存取已釋放的物件）。畫面會先提醒，
    /// 自動檢查會略過 Bug 版。
    public let bugCrashes: Bool
    public let run: ((LabVariant, LabLog) async -> Void)?
    public let demo: ((LabVariant, LabLog) -> AnyView)?
    public let checkBug: (([String]) -> String?)?
    public let checkFixed: (([String]) -> String?)?
    /// 這個演練的原始檔（顯示「要改哪個檔案」用）
    public let filePath: String

    public init(
        id: String,
        title: String,
        question: String,
        goal: String,
        steps: [String],
        expectBug: String,
        expectFixed: String,
        bugCrashes: Bool = false,
        run: ((LabVariant, LabLog) async -> Void)? = nil,
        demo: ((LabVariant, LabLog) -> AnyView)? = nil,
        checkBug: (([String]) -> String?)? = nil,
        checkFixed: (([String]) -> String?)? = nil,
        filePath: String = #filePath
    ) {
        self.id = id
        self.title = title
        self.question = question
        self.goal = goal
        self.steps = steps
        self.expectBug = expectBug
        self.expectFixed = expectFixed
        self.bugCrashes = bugCrashes
        self.run = run
        self.demo = demo
        self.checkBug = checkBug
        self.checkFixed = checkFixed
        self.filePath = filePath
    }

    /// 相對於 labs/ios 的路徑，例如
    /// Practice/Swift/Swift01-ValueSemantics/L01_StructOrClass.swift
    public var relativePath: String {
        if let r = filePath.range(of: "/labs/ios/") {
            return String(filePath[r.upperBound...])
        }
        return (filePath as NSString).lastPathComponent
    }

    public func check(_ variant: LabVariant) -> (([String]) -> String?)? {
        variant == .bug ? checkBug : checkFixed
    }
}

/// 一個單元（對應複習站的一個單元，例如 Swift01）
public struct LabUnit: Identifiable {
    /// 複習站的單元 id，例如 swift-value-semantics-cow
    public let id: String
    /// 短代號，例如 Swift01
    public let code: String
    public let title: String
    public let labs: [Lab]

    public init(id: String, code: String, title: String, labs: [Lab]) {
        self.id = id
        self.code = code
        self.title = title
        self.labs = labs
    }
}

/// 一區（一個 module），例如 Swift & SwiftUI
public struct LabSection: Identifiable {
    public let id: String
    public let title: String
    public let units: [LabUnit]

    public init(id: String, title: String, units: [LabUnit]) {
        self.id = id
        self.title = title
        self.units = units
    }
}
