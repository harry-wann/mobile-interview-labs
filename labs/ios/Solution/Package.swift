// swift-tools-version: 6.0
// Solution：解答：完整的 Bug 版與修正版。和 Practice 的資料夾結構一模一樣，
// 可以直接對照。
// 每一區是一個 module（一個 Swift target），單元是 module 底下的資料夾。
import PackageDescription

let package = Package(
    name: "Solution",
    platforms: [.iOS(.v17)],
    products: [
        .library(
            name: "Solution",
            targets: [
                "SolutionSwift", "SolutionPlatform", "SolutionAdvanced",
                "SolutionExchange", "SolutionTesting", "SolutionDesign",
                "SolutionCoding",
            ])
    ],
    dependencies: [
        .package(path: "../LabKit")
    ],
    targets: [
        .target(name: "SolutionSwiftObjC", path: "SwiftObjC"),
        .target(
            name: "SolutionSwift",
            dependencies: [
                .product(name: "LabKit", package: "LabKit"),
                "SolutionSwiftObjC",
            ], path: "Swift"),
        .target(
            name: "SolutionPlatform",
            dependencies: [.product(name: "LabKit", package: "LabKit")],
            path: "Platform"),
        .target(
            name: "SolutionAdvanced",
            dependencies: [.product(name: "LabKit", package: "LabKit")],
            path: "Advanced"),
        .target(
            name: "SolutionExchange",
            dependencies: [.product(name: "LabKit", package: "LabKit")],
            path: "Exchange"),
        .target(
            name: "SolutionTesting",
            dependencies: [.product(name: "LabKit", package: "LabKit")],
            path: "Testing"),
        .target(
            name: "SolutionDesign",
            dependencies: [.product(name: "LabKit", package: "LabKit")],
            path: "Design"),
        .target(
            name: "SolutionCoding",
            dependencies: [.product(name: "LabKit", package: "LabKit")],
            path: "Coding"),
    ],
    swiftLanguageModes: [.v5]
)
