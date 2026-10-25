// Design04PriceAlerts：價格提醒
import LabKit

enum Design04PriceAlerts {
    static let unit = LabUnit(
        id: "sysdesign-alerts",
        code: "Design04PriceAlerts",
        title: "價格提醒",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04]
    )
}
