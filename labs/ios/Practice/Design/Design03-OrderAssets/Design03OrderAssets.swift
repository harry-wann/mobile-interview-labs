// Design03OrderAssets：下單與資產一致性
import LabKit

enum Design03OrderAssets {
    static let unit = LabUnit(
        id: "sysdesign-order",
        code: "Design03OrderAssets",
        title: "下單與資產一致性",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04]
    )
}
