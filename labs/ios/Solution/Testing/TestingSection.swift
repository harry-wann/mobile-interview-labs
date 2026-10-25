import LabKit

/// AI 自動化測試 這一區的所有單元（App 首頁依這個順序列出）
public let section = LabSection(
    id: "testing", title: "AI 自動化測試",
    units: [
        Testing01Strategy.unit,
        Testing02iOSTesting.unit,
        Testing04E2E.unit,
        Testing05AITesting.unit,
        Testing06CI.unit,
    ])
