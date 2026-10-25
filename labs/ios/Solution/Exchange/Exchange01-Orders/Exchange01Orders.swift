// Exchange01Orders：交易所業務入門
import LabKit

enum Exchange01Orders {
    static let unit = LabUnit(
        id: "exchange-business-101",
        code: "Exchange01Orders",
        title: "交易所業務入門",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05, l06, l07, l08]
    )
}
