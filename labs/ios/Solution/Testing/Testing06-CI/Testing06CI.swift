// Testing06CI：CI 整合
import LabKit

enum Testing06CI {
    static let unit = LabUnit(
        id: "testing-ci",
        code: "Testing06CI",
        title: "CI 整合",
        labs: [l01, l02, l03, l04]   // 新增演練時，把 l01、l02… 加進來
    )
}
