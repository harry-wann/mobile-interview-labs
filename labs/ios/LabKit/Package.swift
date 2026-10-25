// swift-tools-version: 6.0
// LabKit：每個演練模組共用的畫面、log 主控台與小工具。
// 各模組（Swift01-ValueSemantics…）只要 import LabKit，專心寫演練本身。
import PackageDescription

let package = Package(
    name: "LabKit",
    platforms: [.iOS(.v17)],
    products: [.library(name: "LabKit", targets: ["LabKit"])],
    targets: [.target(name: "LabKit")],
    swiftLanguageModes: [.v5]
)
