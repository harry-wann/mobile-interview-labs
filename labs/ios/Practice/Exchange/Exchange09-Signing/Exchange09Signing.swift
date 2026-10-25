// Exchange09Signing：API 簽名
import LabKit

enum Exchange09Signing {
    static let unit = LabUnit(
        id: "exchange-api-signing",
        code: "Exchange09Signing",
        title: "API 簽名",
        labs: [l01, l02, l03, l04, l05, l06]   // 新增演練時，把 l01、l02… 加進來
    )
}
