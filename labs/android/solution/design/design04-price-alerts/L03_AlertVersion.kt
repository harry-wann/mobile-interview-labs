// L03：把提醒改成 11,000,000，伺服器卻還在用舊的門檻（兩次修改的請求亂序到達，
// 沒有版本號）
// 對應複習站：Design04PriceAlerts 第 2 題「為什麼價格的判斷一定要放在伺服器？」
// （App 把條件送給伺服器，兩邊的條件要一致）
//
// 情境：伺服器上的提醒 a1：BTC 高於 10,000,000 円（版本 0）。
// 使用者連續改了兩次門檻：先改成 10,500,000（修改 1），不到一秒又改成 11,000,
// 000（修改 2）。
//       兩個請求走不同的路徑：修改 1 的第一次嘗試逾時、重試過，
// 所以伺服器先收到修改 2，後收到修改 1。
//       之後價格漲到 10,700,000 円：使用者最後設的是 11,000,000，
// 這個價格不該響。
// Bug 版：伺服器收到什麼就蓋什麼。修改 2 先到（門檻 11,000,000），修改 1
// 後到又蓋掉它（門檻變 10,500,000）。
//        價格 10,700,000 時提醒響了，但使用者最後設的明明是 11,000,000。
// 修正版：App 每次修改，把這個提醒的版本號加 1，一起送出；
// 伺服器只接受版本號比目前大的修改，舊的修改（版本號小於或等於目前的）
// 直接忽略。
//        修改 1（版本 1）晚到時，目前已經是版本 2，被忽略；門檻維持 11,000,
// 000，價格 10,700,000 不會響。
package tw.harry.labs.solution.design04pricealerts

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L03Update(val threshold: Int, val version: Int)

/** App 端：使用者改了門檻，產生要送出的修改 */
private interface L03AlertClient {
    fun makeUpdate(threshold: Int): L03Update
}

/** 伺服器端：收到修改，更新自己存的門檻 */
private interface L03AlertServer {
    fun receive(update: L03Update)
    val threshold: Int
}

/** 演練流程（兩個版本共用） */
private fun scenario(
    log: LabLog,
    client: L03AlertClient,
    server: L03AlertServer,
) {
    log("伺服器上的提醒 a1：BTC 高於 ${d04Yen(server.threshold)} 円（版本 0）")
    val first = client.makeUpdate(10_500_000)
    log("使用者把門檻改成 10,500,000 → 修改 1（版本 ${first.version}）")
    val second = client.makeUpdate(11_000_000)
    log("使用者又改成 11,000,000 → 修改 2（版本 ${second.version}）")
    server.receive(second)
    log("伺服器先收到修改 2 → 門檻 ${d04Yen(server.threshold)}")
    server.receive(first)
    log("修改 1 重試之後才到 → 門檻 ${d04Yen(server.threshold)}")
    val fires = 10_700_000 >= server.threshold
    log("價格漲到 10,700,000 円 → 提醒${if (fires) "響了" else "沒有響"}")
    log(
        "最後：伺服器的門檻是 ${d04Yen(
            server.threshold,
        )}（使用者最後設的是 11,000,000），價格 10,700,000 ${if (fires) "觸發了提醒" else "沒有觸發提醒"}",
    )
}

// MARK: - Bug 版
private object L03Bug {
    class Client : L03AlertClient {
        // 沒有版本號
        override fun makeUpdate(threshold: Int): L03Update =
            L03Update(threshold, version = 0)
    }

    class Server : L03AlertServer {
        override var threshold = 10_000_000
            private set

        override fun receive(update: L03Update) {
            // 收到什麼就蓋什麼
            threshold = update.threshold
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Client(), Server())
}

// MARK: - 修正版
private object L03Fixed {
    class Client : L03AlertClient {
        private var version = 0

        override fun makeUpdate(threshold: Int): L03Update {
            // 每次修改，版本號加 1
            version += 1
            return L03Update(threshold, version)
        }
    }

    class Server : L03AlertServer {
        override var threshold = 10_000_000
            private set
        private var version = 0

        override fun receive(update: L03Update) {
            // 版本號不比目前大：舊的修改，忽略
            if (update.version <= version) return
            version = update.version
            threshold = update.threshold
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Client(), Server())
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "把提醒改成 1100 萬，伺服器卻還用舊的門檻",
    question = "第 2 題：為什麼價格的判斷一定要放在伺服器？",
    goal = "判斷既然在伺服器，App 送上去的條件就要和使用者最後設的一致。請求可能亂序、可能重試，所以每次修改帶一個遞增的版本號，伺服器只接受比目前新的，舊的忽略。",
    steps = listOf(
        "選「Bug 版」執行，看伺服器先後收到兩個修改之後，門檻是多少、價格 10,700,000 時提醒響不響。",
        "想一想：使用者最後設的是 11,000,000，伺服器為什麼變成 10,500,000？（後到的舊修改蓋掉了新的。）",
        "選「修正版」執行：修改 1 晚到，因為版本號比較小而被忽略，門檻維持 11,000,000，提醒不響。",
        "想一想：為什麼不用手機的時間（updatedAt）當版本？（手機時間可能不準，見 Exchange04 演練 6；遞增的版本號不依賴時鐘。）",
    ),
    expectBug = "修改 1 晚到又蓋掉修改 2，門檻變成 10,500,000，價格 10,700,000 觸發了提醒：「最後：伺服器的門檻是 10,500,000（使用者最後設的是 11,000,000），價格 10,700,000 觸發了提醒」。",
    expectFixed = "修改 1（版本 1）因為比目前的版本 2 舊而被忽略：「最後：伺服器的門檻是 11,000,000（使用者最後設的是 11,000,000），價格 10,700,000 沒有觸發提醒」。",
    file = "design04-price-alerts/L03_AlertVersion.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, client, server) 執行"
            }

            "最後：伺服器的門檻是 10,500,000（使用者最後設的是 11,000,000），價格 10,700,000 觸發了提醒" !in
                lines -> {
                "Bug 還沒重現：伺服器的 receive 收到什麼就把 threshold 蓋成什麼（不看版本號），後到的修改 1 會蓋掉修改 2"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, client, server) 執行"
            }

            "最後：伺服器的門檻是 11,000,000（使用者最後設的是 11,000,000），價格 10,700,000 沒有觸發提醒" !in
                lines -> {
                "Client 每次 makeUpdate 先把 version 加 1；Server 的 receive 只接受 update.version > version 的修改，並同時更新 version 和 threshold"
            }

            else -> {
                null
            }
        }
    },
)
