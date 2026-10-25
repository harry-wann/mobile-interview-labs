// L03：重新連線之後，成交明細多了幾筆重複的，24 小時成交量也算多了（不能丟的資料還要防重複）
// 對應複習站：Advanced05HighFrequency 第 4 題「哪些資料可以丟、哪些絕對不能丟？」
//
// 情境：成交明細（每一筆成交都是獨立的事件）不能合併、不能丟，但連線斷了再接上，伺服器會把最近幾筆再送一次，同一筆成交就會收到兩次。
//        收到的順序：成交 #1（0.5 BTC）、#2（0.2）、#3（0.1），斷線重連之後，重送 #2、#3，再來新的 #4（0.3）。
//        每一筆成交有唯一的 ID（冪等鍵：同一個 ID 不管送幾次，結果都跟送一次一樣）。
//
// 你要寫：
//   ① Bug 版 receive：每收到一筆就把 trade.id 加進 rows，並把 trade.quantity 加進 volume，不管是不是已經收過。
//   ② 修正版 receive：用 seen（HashSet）記下處理過的成交 ID。seen.add(trade.id) 回傳 false 代表已經收過，就 return（重送的，跳過）；否則加進 rows、加成交量。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced05-high-frequency/L03_TradeDedupe.kt
package tw.harry.labs.practice.advanced05highfrequency

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class Trade(val id: Int, val quantity: BigDecimal)

private val received = listOf(
    Trade(1, BigDecimal("0.5")), Trade(2, BigDecimal("0.2")), Trade(3, BigDecimal("0.1")),
    Trade(2, BigDecimal("0.2")), Trade(3, BigDecimal("0.1")),            // 重連之後，伺服器重送最近兩筆
    Trade(4, BigDecimal("0.3")),
)

/** 成交明細的共同介面（兩個版本共用） */
private interface TradeTape {
    fun receive(trade: Trade)
    val rows: List<Int>              // 畫面上的成交明細，每一列是成交 ID
    val volume: BigDecimal           // 累計成交量
}

private fun scenario(log: LabLog, tape: TradeTape) {
    for (trade in received) tape.receive(trade)
    log("成交明細：${tape.rows.joinToString("、")}（共 ${tape.rows.size} 筆）")
    log("累計成交量：${tape.volume.toPlainString()} BTC")
}

// MARK: - Bug 版
private object L03Bug {
    class Tape : TradeTape {
        override val rows = mutableListOf<Int>()
        override var volume: BigDecimal = BigDecimal.ZERO

        override fun receive(trade: Trade) {
            // TODO ①：rows.add(trade.id)，volume += trade.quantity：收到就加，不管是不是已經收過
        }
    }

    fun run(log: LabLog) = scenario(log, Tape())
}

// MARK: - 修正版
private object L03Fixed {
    class Tape : TradeTape {
        override val rows = mutableListOf<Int>()
        override var volume: BigDecimal = BigDecimal.ZERO
        private val seen = HashSet<Int>()                          // 已經處理過的成交 ID

        override fun receive(trade: Trade) {
            // TODO ②：if (!seen.add(trade.id)) return（重送的，跳過）；否則 rows.add(trade.id)、volume += trade.quantity
        }
    }

    fun run(log: LabLog) = scenario(log, Tape())
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "重新連線之後，成交明細多了重複的，成交量算多了",
    question = "第 4 題：哪些資料可以丟、哪些絕對不能丟？",
    goal = "成交明細這類「每一筆都是獨立事件」的資料不能丟，但要用唯一的 ID 去重：重送的同一筆只算一次。先寫出收到就加的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced05-high-frequency/L03_TradeDedupe.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看兩行 log：成交明細有 6 筆，#2、#3 出現兩次；累計成交量 1.4 BTC，比實際多了 0.3。",
        "寫 TODO ②，選「修正版」執行：成交明細 1、2、3、4 共 4 筆，累計成交量 1.1 BTC。",
        "想一想：訂單簿的增量更新（delta）不能丟，要檢查序號；成交明細不能丟，要用 ID 去重；最新價可以只留最新的。三種資料的處理為什麼不同？（見這個單元的第 4 題）",
    ),
    expectBug = "成交明細：1、2、3、2、3、4（共 6 筆）；累計成交量：1.4 BTC。",
    expectFixed = "成交明細：1、2、3、4（共 4 筆）；累計成交量：1.1 BTC。",
    file = "advanced05-high-frequency/L03_TradeDedupe.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "成交明細：1、2、3、2、3、4（共 6 筆）" !in lines -> "Bug 版收到的每一筆都要 add（包含重送的），成交明細會有 6 筆"
            "累計成交量：1.4 BTC" !in lines -> "Bug 版重送的也要加進成交量：1.4 BTC"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "成交明細：1、2、3、4（共 4 筆）" !in lines -> "重送的 #2、#3 要跳過：用 HashSet 記下處理過的 ID，add 回傳 false 就跳過；其他筆不能丟"
            "累計成交量：1.1 BTC" !in lines -> "成交量只能算每筆一次：1.1 BTC"
            else -> null
        }
    },
)
