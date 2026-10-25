// Testing01Strategy：測試策略
import LabKit

enum Testing01Strategy {
    static let unit = LabUnit(
        id: "testing-strategy",
        code: "Testing01Strategy",
        title: "測試策略",
        labs: [l01, l02, l03]   // 新增演練時，把 l01、l02… 加進來
    )
}
