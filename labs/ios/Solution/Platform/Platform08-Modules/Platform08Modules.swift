// Platform08Modules：模組化
import LabKit

enum Platform08Modules {
    static let unit = LabUnit(
        id: "platform-modularization",
        code: "Platform08Modules",
        title: "模組化",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02]
    )
}
