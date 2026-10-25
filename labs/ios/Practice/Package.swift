// swift-tools-version: 6.0
// Practice：練習：你寫的程式。照每個檔案裡的 TODO 寫出 Bug 版和修正版。
// 每一區是一個 module（一個 Swift target），單元是 module 底下的資料夾。
import PackageDescription

let package = Package(
    name: "Practice",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "Practice", targets: ["PracticeSwift", "PracticePlatform", "PracticeAdvanced", "PracticeExchange", "PracticeTesting", "PracticeDesign", "PracticeCoding"]),
    ],
    dependencies: [
        .package(path: "../LabKit"),
    ],
    targets: [
        .target(name: "PracticeSwiftObjC", path: "SwiftObjC"),
        .target(name: "PracticeSwift", dependencies: [.product(name: "LabKit", package: "LabKit"), "PracticeSwiftObjC"], path: "Swift"),
        .target(name: "PracticePlatform", dependencies: [.product(name: "LabKit", package: "LabKit")], path: "Platform"),
        .target(name: "PracticeAdvanced", dependencies: [.product(name: "LabKit", package: "LabKit")], path: "Advanced"),
        .target(name: "PracticeExchange", dependencies: [.product(name: "LabKit", package: "LabKit")], path: "Exchange"),
        .target(name: "PracticeTesting", dependencies: [.product(name: "LabKit", package: "LabKit")], path: "Testing"),
        .target(name: "PracticeDesign", dependencies: [.product(name: "LabKit", package: "LabKit")], path: "Design"),
        .target(name: "PracticeCoding", dependencies: [.product(name: "LabKit", package: "LabKit")], path: "Coding"),
    ],
    swiftLanguageModes: [.v5]
)
