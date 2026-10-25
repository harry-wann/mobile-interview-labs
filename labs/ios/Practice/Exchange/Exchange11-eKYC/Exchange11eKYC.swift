// Exchange11eKYC：eKYC 與法規
import LabKit

enum Exchange11eKYC {
    static let unit = LabUnit(
        id: "exchange-ekyc-compliance",
        code: "Exchange11eKYC",
        title: "eKYC 與法規",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05, l06]
    )
}
