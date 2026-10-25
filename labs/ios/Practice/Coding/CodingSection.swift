import LabKit

/// Live Coding 這一區的所有單元（App 首頁依這個順序列出）
public let section = LabSection(
    id: "livecoding", title: "Live Coding",
    units: [
        Coding01DataStructures.unit,
        Coding02TimeStreams.unit,
        Coding03ExchangeAlgorithms.unit,
        Coding04Async.unit,
    ])
