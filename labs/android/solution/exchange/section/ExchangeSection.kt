package tw.harry.labs.solution

import tw.harry.labs.kit.LabSection
import tw.harry.labs.solution.exchange01orders.unit as exchange01orders
import tw.harry.labs.solution.exchange02precision.unit as exchange02precision
import tw.harry.labs.solution.exchange03websocket.unit as exchange03websocket
import tw.harry.labs.solution.exchange04reconnect.unit as exchange04reconnect
import tw.harry.labs.solution.exchange05orderbook.unit as exchange05orderbook
import tw.harry.labs.solution.exchange06realtimeui.unit as exchange06realtimeui
import tw.harry.labs.solution.exchange07kline.unit as exchange07kline
import tw.harry.labs.solution.exchange08orderflow.unit as exchange08orderflow
import tw.harry.labs.solution.exchange09signing.unit as exchange09signing
import tw.harry.labs.solution.exchange10accountsecurity.unit as exchange10accountsecurity
import tw.harry.labs.solution.exchange11ekyc.unit as exchange11ekyc
import tw.harry.labs.solution.exchange12wallet.unit as exchange12wallet
import tw.harry.labs.solution.exchange13localization.unit as exchange13localization

/** 交易所 App 技術 這一區的所有單元（App 首頁依這個順序列出） */
val exchangeSection = LabSection(
    id = "exchange",
    title = "交易所 App 技術",
    units = listOf(
        exchange01orders,
        exchange02precision,
        exchange03websocket,
        exchange04reconnect,
        exchange05orderbook,
        exchange06realtimeui,
        exchange07kline,
        exchange08orderflow,
        exchange09signing,
        exchange10accountsecurity,
        exchange11ekyc,
        exchange12wallet,
        exchange13localization,
    ),
)
