// Platform01Lifecycle：生命週期
import LabKit

enum Platform01Lifecycle {
    static let unit = LabUnit(
        id: "platform-lifecycle",
        code: "Platform01Lifecycle",
        title: "生命週期",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05, l06, l07]
    )
}
