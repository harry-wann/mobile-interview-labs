import LabKit

/// iOS × Android 平台對照 這一區的所有單元（App 首頁依這個順序列出）
public let section = LabSection(
    id: "platform", title: "iOS × Android 平台對照",
    units: [
        Platform01Lifecycle.unit,
        Platform02Threading.unit,
        Platform03BackgroundPush.unit,
        Platform04SecureStorage.unit,
        Platform05Networking.unit,
        Platform06DeepLink.unit,
        Platform07Release.unit,
        Platform08Modules.unit,
    ])
