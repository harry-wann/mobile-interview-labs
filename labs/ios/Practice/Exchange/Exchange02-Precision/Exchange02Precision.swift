// Exchange02Precision：精度與金額
import LabKit

enum Exchange02Precision {
    static let unit = LabUnit(
        id: "exchange-precision",
        code: "Exchange02Precision",
        title: "精度與金額",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05, l06, l07]
    )
}
