// Exchange08OrderFlow：下單流程
import LabKit

enum Exchange08OrderFlow {
    static let unit = LabUnit(
        id: "exchange-order-flow",
        code: "Exchange08OrderFlow",
        title: "下單流程",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05, l06, l07]
    )
}
