// Exchange04Reconnect：連線韌性
import LabKit

enum Exchange04Reconnect {
    static let unit = LabUnit(
        id: "exchange-connection-resilience",
        code: "Exchange04Reconnect",
        title: "連線韌性",
        labs: [l01, l02, l03, l04, l05, l06, l07]   // 新增演練時，把 l01、l02… 加進來
    )
}
