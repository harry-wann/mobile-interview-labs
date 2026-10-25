package tw.harry.labs.practice

import tw.harry.labs.kit.LabSection
import tw.harry.labs.practice.exchange01orders.unit as exchange01orders
import tw.harry.labs.practice.exchange02precision.unit as exchange02precision
import tw.harry.labs.practice.exchange03websocket.unit as exchange03websocket
import tw.harry.labs.practice.exchange04reconnect.unit as exchange04reconnect
import tw.harry.labs.practice.exchange05orderbook.unit as exchange05orderbook
import tw.harry.labs.practice.exchange06realtimeui.unit as exchange06realtimeui
import tw.harry.labs.practice.exchange07kline.unit as exchange07kline
import tw.harry.labs.practice.exchange08orderflow.unit as exchange08orderflow
import tw.harry.labs.practice.exchange09signing.unit as exchange09signing
import tw.harry.labs.practice.exchange10accountsecurity.unit as exchange10accountsecurity
import tw.harry.labs.practice.exchange11ekyc.unit as exchange11ekyc
import tw.harry.labs.practice.exchange12wallet.unit as exchange12wallet
import tw.harry.labs.practice.exchange13localization.unit as exchange13localization

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
