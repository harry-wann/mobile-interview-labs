// Testing05AITesting 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   被測的程式 T05Fee（手續費），以及「被改壞的版本」T05Mutation（真正的
// mutation testing 工具會自動改程式碼，這裡用開關代表）。
//   測試套件 T05Suite：AI 產生的測試、人補強過的測試；能算覆蓋率，
// 也能對任一個改壞的版本跑一次。
//   L02 用的「亂數」與 L03 用的 CI 歷史紀錄。
package tw.harry.labs.practice.testing05aitesting

// 被測的程式：手續費

enum class T05Mutation(val title: String) {
    NONE("正確的實作"),
    BOUNDARY(">= 改成 >"),
    SWAP_RATES("maker 與 taker 費率對調"),
    ROUND_INSTEAD_OF_FLOOR("無條件捨去改成四捨五入"),
    NO_MIN_FEE("拿掉最少收 1 日圓"),
    NO_VIP_DISCOUNT("VIP 沒有減半"),
    ;

    companion object {
        /** 所有「改壞的版本」（不含 NONE） */
        val allMutants: List<T05Mutation> = entries.filter { it != NONE }
    }
}

object T05Fee {
    /** 6 個覆蓋率檢查點的名稱 */
    val probeIds =
        listOf(
            "maker",
            "taker",
            "vip",
            "normal",
            "minFeeApplied",
            "minFeeNotNeeded",
        )

    /**
     * 手續費（日圓）。maker 0.05%、taker 0.15%；單筆金額達 1,000 萬日圓（含）以上是 VIP，費率減半；無條件捨去；最少收 1 日圓。
     * 費率用「每 100,000 的幾分」表示（0.05% 是 50）。probe 用來記錄程式跑到哪幾個地方（覆蓋率）。
     */
    fun calculate(
        amountJpy: Int,
        isMaker: Boolean,
        mutation: T05Mutation = T05Mutation.NONE,
        probe: ((String) -> Unit)? = null,
    ): Int {
        probe?.invoke(if (isMaker) "maker" else "taker")
        var rate = if (isMaker) 50 else 150
        if (mutation == T05Mutation.SWAP_RATES) rate = if (isMaker) 150 else 50
        val isVip = if (mutation ==
            T05Mutation.BOUNDARY
        ) {
            amountJpy > 10_000_000
        } else {
            amountJpy >=
                10_000_000
        }
        if (isVip) {
            probe?.invoke("vip")
            if (mutation != T05Mutation.NO_VIP_DISCOUNT) rate /= 2
        } else {
            probe?.invoke("normal")
        }
        var fee = if (mutation ==
            T05Mutation.ROUND_INSTEAD_OF_FLOOR
        ) {
            (amountJpy * rate + 50_000) /
                100_000
        } else {
            amountJpy * rate / 100_000
        }
        if (mutation != T05Mutation.NO_MIN_FEE) {
            if (fee < 1) {
                probe?.invoke("minFeeApplied")
                fee = 1
            } else {
                probe?.invoke("minFeeNotNeeded")
            }
        }
        return fee
    }
}

// 測試套件

typealias T05FeeFunction = (amountJpy: Int, isMaker: Boolean) -> Int

class T05Test(val name: String, val check: (T05FeeFunction) -> Boolean)

class T05Suite(val name: String, val tests: List<T05Test>) {
    /** 對某個版本（正確的或被改壞的）跑全部測試，是不是全部通過 */
    fun passes(mutation: T05Mutation): Boolean = tests.all { test ->
        test.check {
            amount,
            isMaker,
            ->
            T05Fee.calculate(amount, isMaker, mutation)
        }
    }

    /** 覆蓋率：測試執行時，跑到幾個檢查點（共 6 個），0 到 1 */
    fun coverage(): Double {
        val hits = mutableSetOf<String>()
        for (test in tests) {
            test.check {
                amount,
                isMaker,
                ->
                T05Fee.calculate(amount, isMaker, probe = {
                    hits +=
                        it
                })
            }
        }
        return hits.size.toDouble() / T05Fee.probeIds.size
    }

    /** 被改壞的版本裡，有幾個被測試抓到（測試失敗） */
    fun killedMutants(): Int = T05Mutation.allMutants.count { !passes(it) }

    companion object {
        /**
         * AI 產生的 5 個測試：看起來很完整，每個函式、每個分支都跑到，
         * 卻沒有檢查精確的數字
         */
        val aiGenerated = T05Suite(
            "AI 產生的測試",
            listOf(
                T05Test("手續費不會是負的") { fee -> fee(1_000_000, true) >= 0 },
                T05Test("taker 手續費大於 0") { fee -> fee(1_000_000, false) > 0 },
                T05Test("手續費不會超過成交金額") { fee ->
                    fee(5_000_000, false) <=
                        5_000_000
                },
                T05Test("VIP 單有手續費") { fee -> fee(20_000_000, true) > 0 },
                T05Test("小額單也有手續費") { fee -> fee(1_000, true) > 0 },
            ),
        )

        /** 人補強之後的 5 個測試：檢查精確的數字，包含邊界 */
        val strengthened = T05Suite(
            "補強後的測試",
            listOf(
                T05Test("maker 與 taker 的費率") { fee ->
                    fee(1_000_000, true) ==
                        500 &&
                        fee(1_000_000, false) == 1_500
                },
                T05Test("無條件捨去") { fee -> fee(9_999_999, false) == 14_999 },
                T05Test("剛好 1,000 萬是 VIP") { fee ->
                    fee(10_000_000, false) ==
                        7_500
                },
                T05Test("VIP 費率減半") { fee -> fee(20_000_000, true) == 5_000 },
                T05Test("最少收 1 日圓") { fee -> fee(1_000, true) == 1 },
            ),
        )
    }
}

// L02：用「亂數」的測試

/** 演練用固定的一串數字代表亂數，這樣每次執行結果都一樣 */
class T05Random {
    private val values =
        listOf(2_000, 1_000, 777, 1_900, 450, 1_234, 3_000, 888, 1_500, 600)
    private var index = 0

    fun nextAmount(): Int = values[index++ % values.size]
}

// L03：CI 歷史紀錄

/** 一次測試執行的紀錄 */
class T05CIRun(val commit: String, val test: String, val passed: Boolean)

object T05History {
    /**
     * 3 個 commit、4 個測試的執行紀錄：
     * testFee、testChart 一直通過；testLogin 在 c2 壞掉（兩次重試都失敗，真的 bug），c3 修好；
     * testOrderUI 在 c1 與 c3 同一個 commit 上有時失敗、有時通過（flaky）。
     */
    val runs: List<T05CIRun> = listOf(
        T05CIRun("c1", "testFee", true),
        T05CIRun("c1", "testLogin", true),
        T05CIRun("c1", "testOrderUI", false),
        T05CIRun("c1", "testOrderUI", true),
        T05CIRun("c1", "testChart", true),
        T05CIRun("c2", "testFee", true),
        T05CIRun("c2", "testLogin", false),
        T05CIRun("c2", "testLogin", false),
        T05CIRun("c2", "testOrderUI", true),
        T05CIRun("c2", "testChart", true),
        T05CIRun("c3", "testFee", true),
        T05CIRun("c3", "testLogin", true),
        T05CIRun("c3", "testOrderUI", true),
        T05CIRun("c3", "testOrderUI", false),
        T05CIRun("c3", "testChart", true),
    )
}
