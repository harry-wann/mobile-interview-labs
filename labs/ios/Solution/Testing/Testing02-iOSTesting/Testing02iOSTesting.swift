// Testing02iOSTesting：iOS 測試工具
import LabKit

enum Testing02iOSTesting {
    static let unit = LabUnit(
        id: "testing-ios",
        code: "Testing02iOSTesting",
        title: "iOS 測試工具",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04]
    )
}
