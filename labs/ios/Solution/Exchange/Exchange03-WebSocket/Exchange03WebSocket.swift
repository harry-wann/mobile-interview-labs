// Exchange03WebSocket：WebSocket 協定
import LabKit

enum Exchange03WebSocket {
    static let unit = LabUnit(
        id: "exchange-ws-protocol",
        code: "Exchange03WebSocket",
        title: "WebSocket 協定",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05, l06, l07]
    )
}
