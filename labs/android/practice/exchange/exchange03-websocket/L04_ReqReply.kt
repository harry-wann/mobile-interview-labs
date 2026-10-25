// L04：K 線頁一直轉圈（req 的回覆和錯誤訊息都被丟掉了）
// 對應複習站：Exchange03WebSocket 第 4 題「sub、unsub、req 差在哪？訊息和錯誤長什麼樣子？」
//
// 情境：打開 BTC/JPY 的 K 線頁，App 送出三個請求：req 歷史 K 線（id r1）、sub 即時 K 線（id s1）、sub 一個文件裡沒有的 market.btcjpy.kline.3min（id s2）。
//        畫面上每個「還沒收到結果的請求」都會轉圈。伺服器依序回來：
//        s1 的確認（subbed）、r1 的回覆（rep，不是 ch）、s2 的錯誤（status 是 error）、一則即時 K 線的推送（ch）、一則 ping。
//
// 你要寫：
//   ① Bug 版（L04Bug.classify）：除了心跳，只認得有 ch 欄位的推送，其他一律 Ignored。
//   ② 修正版（L04Fixed.classify）：心跳 → 錯誤 → 確認／req 的回覆 → 推送 → 忽略，用 id 對回請求。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange03-websocket/L04_ReqReply.kt
package tw.harry.labs.practice.exchange03websocket

import com.google.gson.JsonObject
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private sealed interface Incoming {
    class Heartbeat(val value: Long) : Incoming
    class Failure(val id: String, val message: String) : Incoming     // status 是 error
    class Ack(val id: String) : Incoming                              // subbed／unsubbed
    class Reply(val id: String, val topic: String) : Incoming         // req 的回覆
    class Push(val topic: String) : Incoming                          // 持續推送
    data object Ignored : Incoming
}

private val serverMessages = listOf(
    """{"id":"s1","status":"ok","subbed":"market.btcjpy.kline.1min","ts":1630994555000}""",
    """{"id":"r1","status":"ok","rep":"market.btcjpy.kline.1min","tick":[{"id":1630994400,"close":9990000},{"id":1630994460,"close":10000000}]}""",
    """{"id":"s2","status":"error","err-code":"bad-request","err-msg":"invalid topic market.btcjpy.kline.3min","ts":1630994555100}""",
    """{"ch":"market.btcjpy.kline.1min","ts":1630994555200,"tick":{"id":1630994520,"close":10010000}}""",
    """{"ping":18212558000}""",
)

/** 演練流程（兩個版本共用）：送出三個請求，依序處理伺服器的回覆，看有哪些請求一直沒有結果 */
private fun scenario(log: LabLog, classify: (JsonObject) -> Incoming) {
    val pending = sortedSetOf("r1", "s1", "s2")
    for (text in serverMessages) {
        val json = e03Parse(text) ?: continue
        when (val incoming = classify(json)) {
            is Incoming.Heartbeat -> log("心跳 ping ${incoming.value}，回 pong")
            is Incoming.Failure -> {
                pending.remove(incoming.id)
                log("請求 ${incoming.id} 失敗：${incoming.message}，畫面顯示錯誤")
            }
            is Incoming.Ack -> {
                pending.remove(incoming.id)
                log("請求 ${incoming.id} 訂閱成功")
            }
            is Incoming.Reply -> {
                pending.remove(incoming.id)
                log("請求 ${incoming.id} 的回覆：${incoming.topic} 的歷史 K 線")
            }
            is Incoming.Push -> log("推送：${incoming.topic}")
            Incoming.Ignored -> log("忽略一則訊息")
        }
    }
    log("還在轉圈、沒有收到結果的請求有 ${pending.size} 個${if (pending.isEmpty()) "" else "（${pending.joinToString("、")}）"}")
}

// MARK: - Bug 版
private object L04Bug {
    fun classify(json: JsonObject): Incoming {
        json.e03Long("ping")?.let { return Incoming.Heartbeat(it) }
        // TODO ①：有 "ch" 欄位的就是推送，回傳 Incoming.Push(ch)（其他的回覆、確認、錯誤都不處理，維持下面的 Ignored）
        return Incoming.Ignored
    }

    suspend fun run(log: LabLog) = scenario(log, ::classify)
}

// MARK: - 修正版
private object L04Fixed {
    fun classify(json: JsonObject): Incoming {
        json.e03Long("ping")?.let { return Incoming.Heartbeat(it) }                                    // 1. 心跳
        // TODO ②：照順序判斷（上面的心跳已經寫好）：
        //   2. status 是 "error" 而且有 id → Incoming.Failure(id, err-msg 欄位的文字)
        //   3. 有 "subbed" 或 "unsubbed" 欄位而且有 id → Incoming.Ack(id)；有 "rep" 欄位（req 的回覆）而且有 id → Incoming.Reply(id, rep 的文字)
        //   4. 有 "ch"，而且有 "tick" 或 "data" → Incoming.Push(ch)
        return Incoming.Ignored                                                                        // 5. 其他一律忽略
    }

    suspend fun run(log: LabLog) = scenario(log, ::classify)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "K 線頁一直轉圈，回覆被丟掉了",
    question = "第 4 題：sub、unsub、req 差在哪？訊息和錯誤長什麼樣子？",
    goal = "每個請求都帶一個 id，回覆（確認、req 的 rep、錯誤）要用 id 對回請求。分派的順序是：心跳、錯誤、確認、推送、忽略。先寫出只認得推送的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange03-websocket/L04_ReqReply.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看哪些請求一直沒有收到結果、哪些訊息被「忽略」。",
        "想一想：req 的回覆用 rep 還是 ch？訂閱不存在的 topic，伺服器回什麼？",
        "寫 TODO ②（修正版），選「修正版」執行，三個請求都要有結果：成功、成功，和一個失敗。",
        "進階：推送的資料有時放 tick、有時放 data，解析時兩個欄位都要接受；判斷「是不是推送」要放在最後。",
    ),
    expectBug = "r1、s1、s2 都沒有結果：「還在轉圈、沒有收到結果的請求有 3 個（r1、s1、s2）」，只看到推送與心跳。",
    expectFixed = "s1 訂閱成功、r1 收到歷史 K 線、s2 失敗並顯示錯誤，「還在轉圈、沒有收到結果的請求有 0 個」。",
    file = "exchange03-websocket/L04_ReqReply.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("還在轉圈") } -> "演練沒有跑完：要用 scenario(log, ::classify) 執行"
            "推送：market.btcjpy.kline.1min" !in lines -> "Bug 版也要認得推送：有 ch 欄位就是推送"
            "還在轉圈、沒有收到結果的請求有 3 個（r1、s1、s2）" !in lines -> "Bug 還沒重現：只認得有 ch 欄位的推送和 ping，req 的回覆、確認、錯誤都要被忽略"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("還在轉圈") } -> "演練沒有跑完：要用 scenario(log, ::classify) 執行"
            "請求 s2 失敗：invalid topic market.btcjpy.kline.3min，畫面顯示錯誤" !in lines -> "status 是 error 要回傳 Failure(id, message)，message 取 err-msg"
            "請求 r1 的回覆：market.btcjpy.kline.1min 的歷史 K 線" !in lines -> "req 的回覆用 rep 欄位（不是 ch），要回傳 Reply(id, topic)"
            "請求 s1 訂閱成功" !in lines -> "有 subbed 或 unsubbed 欄位的是確認，要回傳 Ack(id)"
            "推送：market.btcjpy.kline.1min" !in lines -> "有 ch 而且有 tick 或 data 的是推送"
            "還在轉圈、沒有收到結果的請求有 0 個" !in lines -> "還有請求沒有收到結果"
            else -> null
        }
    },
)
