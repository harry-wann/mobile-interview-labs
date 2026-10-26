import LabKit

/// Swift & SwiftUI 這一區的所有單元（App 首頁依這個順序列出）
public let section = LabSection(
    id: "swift", title: "Swift & SwiftUI",
    units: [
        Swift01ValueSemantics.unit,
        Swift02ARC.unit,
        Swift03Generics.unit,
        Swift04StructuredConcurrency.unit,
        Swift05Actors.unit,
        Swift06AsyncStream.unit,
        Swift07SwiftUIState.unit,
        Swift08SwiftUIIdentity.unit,
        Swift09UIKitInterop.unit,
        Swift10LanguageFeatures.unit,
        Swift11ObjC.unit,
        Swift12Combine.unit,
    ])
