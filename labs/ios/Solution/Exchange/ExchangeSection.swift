import LabKit

/// 交易所 App 技術 這一區的所有單元（App 首頁依這個順序列出）
public let section = LabSection(id: "exchange", title: "交易所 App 技術", units: [
    Exchange01Orders.unit,
    Exchange02Precision.unit,
    Exchange03WebSocket.unit,
    Exchange04Reconnect.unit,
    Exchange05OrderBook.unit,
    Exchange06RealtimeUI.unit,
    Exchange07Kline.unit,
    Exchange08OrderFlow.unit,
    Exchange09Signing.unit,
    Exchange10AccountSecurity.unit,
    Exchange11eKYC.unit,
    Exchange12Wallet.unit,
    Exchange13Localization.unit,
])
