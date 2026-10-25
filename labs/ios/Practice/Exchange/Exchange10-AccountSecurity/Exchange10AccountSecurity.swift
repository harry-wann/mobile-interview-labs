// Exchange10AccountSecurity：帳戶安全
import LabKit

enum Exchange10AccountSecurity {
    static let unit = LabUnit(
        id: "exchange-account-security",
        code: "Exchange10AccountSecurity",
        title: "帳戶安全",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05, l06]
    )
}
