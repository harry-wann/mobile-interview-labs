// Testing04E2E：端到端測試
import LabKit

enum Testing04E2E {
    static let unit = LabUnit(
        id: "testing-e2e",
        code: "Testing04E2E",
        title: "端到端測試",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03]
    )
}
