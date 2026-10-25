// L05：畫面上的手續費合計和伺服器的帳單差了 2 円（formatter 預設是銀行家捨入）
// 對應複習站：Exchange02Precision 第 5 題「數字要怎麼顯示？」（捨入模式）
//
// 情境：日圓沒有小數，手續費要四捨五入到整數。四筆成交，金額 12,500、37,500、
// 62,500、87,500 円，手續費率 0.1%（演練假設），
//        手續費剛好是 12.5、37.5、62.5、87.5 円。伺服器的帳單規則（演練假設）
// 是四捨五入，剛好 0.5 進位：13、38、63、88，合計 202 円。
//        App 用 DecimalFormat 把手續費顯示成整數。
// Bug 版：DecimalFormat 沒有設 roundingMode，預設是 HALF_EVEN（銀行家捨入，
// 剛好 0.5 時進位到偶數）：12.5 → 12、62.5 → 62，
//        畫面上的合計是 200 円，和帳單差 2 円。
// 修正版：明確設定 roundingMode = RoundingMode.HALF_UP（四捨五入，剛好 0.5
// 進位），和伺服器的規則一致。
package tw.harry.labs.solution.exchange02precision

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val amounts = listOf("12500", "37500", "62500", "87500")

// 伺服器帳單（四捨五入）
private val serverFees = listOf(13, 38, 63, 88)
private val feeRate = e02("0.001") // 演練假設：0.1%

/** 演練流程（兩個版本共用）：逐筆算手續費、用 formatter 顯示，和帳單比對 */
private fun scenario(log: LabLog, format: (BigDecimal) -> String) {
    var shownTotal = 0
    for ((amount, serverFee) in amounts.zip(serverFees)) {
        val fee = e02(amount) * feeRate
        val shown = format(fee)
        shownTotal += shown.toIntOrNull() ?: 0
        log(
            "成交 $amount 円，手續費 ${e02Text(
                fee,
            )} 円 → 畫面顯示 $shown（伺服器帳單 $serverFee）",
        )
    }
    val serverTotal = serverFees.sum()
    log(
        "畫面手續費合計 $shownTotal 円，伺服器帳單合計 $serverTotal 円，差 ${serverTotal - shownTotal} 円",
    )
}

// MARK: - Bug 版
private object L05Bug {
    fun format(value: BigDecimal): String {
        // 日圓沒有小數
        val formatter = DecimalFormat("0", DecimalFormatSymbols(Locale.US))
        // 沒有設 roundingMode：預設是 HALF_EVEN
        return formatter.format(value)
    }

    suspend fun run(log: LabLog) = scenario(log, ::format)
}

// MARK: - 修正版
private object L05Fixed {
    fun format(value: BigDecimal): String {
        val formatter = DecimalFormat("0", DecimalFormatSymbols(Locale.US))
        // 明確指定：剛好 0.5 時進位
        formatter.roundingMode = RoundingMode.HALF_UP
        return formatter.format(value)
    }

    suspend fun run(log: LabLog) = scenario(log, ::format)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "畫面上的手續費合計和帳單差了 2 円",
    question = "第 5 題：數字要怎麼顯示？",
    goal = "畫面顯示的捨入結果要和伺服器的規則一致。formatter 的捨入模式不要靠預設值，要明確指定。",
    steps = listOf(
        "選「Bug 版」執行，看哪幾筆的顯示和伺服器帳單不一樣。",
        "想一想：12.5 和 62.5 剛好在一半。DecimalFormat 預設的 roundingMode 是哪一種？",
        "選「修正版」執行，四筆都要和帳單一樣。",
        "進階：iOS 的 NumberFormatter 預設也是 halfEven；兩個平台都要明確指定，而且捨入規則也要放進共用測試資料。",
    ),
    expectBug = "12.5 顯示 12、62.5 顯示 62（37.5、87.5 剛好一樣），「畫面手續費合計 200 円，伺服器帳單合計 202 円，差 2 円」。",
    expectFixed = "四筆都和帳單一樣：13、38、63、88，「畫面手續費合計 202 円，伺服器帳單合計 202 円，差 0 円」。",
    file = "exchange02-precision/L05_HalfEven.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("畫面手續費合計")
            } -> "演練沒有跑完：要用 scenario(log, ::format) 執行"

            "畫面手續費合計 200 円，伺服器帳單合計 202 円，差 2 円" !in lines -> "Bug 還沒重現：DecimalFormat 不要設 roundingMode（預設 HALF_EVEN），12.5 會顯示 12、62.5 會顯示 62"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("畫面手續費合計")
            } -> "演練沒有跑完：要用 scenario(log, ::format) 執行"

            "畫面手續費合計 202 円，伺服器帳單合計 202 円，差 0 円" !in lines -> "formatter 要設 roundingMode = RoundingMode.HALF_UP，剛好 0.5 才會進位"

            else -> null
        }
    },
)
