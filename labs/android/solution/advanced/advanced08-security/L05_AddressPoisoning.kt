// L05：從交易紀錄複製到長得一模一樣的假地址，系統卻當成已儲存的地址放行（地址要完整比對，新地址要冷卻）
// 對應複習站：Advanced08Security 第 7 題「『出金到新地址』的安全流程怎麼設計？」（address poisoning）
//
// 情境：address poisoning（地址投毒）：攻擊者先對你的錢包送一筆很小的金額，讓一個「開頭和結尾幾碼都和你常用地址一樣」的假地址出現在交易紀錄裡，
//       等你下次從紀錄複製。已儲存（白名單）的地址可以立刻出金；新地址要先冷卻 24 小時才能出金，讓使用者有時間發現、取消。
//       這是防禦演練：地址都是編出來的假資料，演練裡只有「判斷這個地址是不是已儲存」的邏輯。
// Bug 版：判斷「是不是已儲存的地址」時，只比對開頭 6 碼和結尾 4 碼（就像使用者用眼睛核對的方式）。
//        假地址通過比對，被當成已儲存的地址，沒有冷卻就放行，資金送給攻擊者。
// 修正版：完整比對整個地址（忽略英文字母大小寫，因為大小寫只是校驗用的格式）。假地址是新地址，要冷卻 24 小時。
package tw.harry.labs.solution.advanced08security

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val SAVED_ADDRESS = "0x7a3F9c2E81d4B6a05C3e9F1b2D8a4E6c7B9d1A2F"
/** 開頭 6 碼和結尾 4 碼都和 SAVED_ADDRESS 一樣，中間不同 */
private val lookAlike = SAVED_ADDRESS.take(6) + "0".repeat(32) + SAVED_ADDRESS.takeLast(4)
private const val COOLDOWN_HOURS = 24

/** 演練流程（兩個版本共用）：isSaved(地址) 判斷是不是已儲存的地址 */
private fun scenario(log: LabLog, isSaved: (String) -> Boolean) {
    /** 已儲存的地址立刻放行；新地址要加入後等滿冷卻時間 */
    fun decision(address: String, addedHoursAgo: Int?): String {
        if (isSaved(address)) return "放行（已儲存的地址）"
        if (addedHoursAgo == null) return "拒絕（新地址，先加入並冷卻 24 小時）"
        return if (addedHoursAgo >= COOLDOWN_HOURS) "放行（新地址，冷卻已滿）" else "拒絕（新地址冷卻中，還要等 ${COOLDOWN_HOURS - addedHoursAgo} 小時）"
    }

    log("出金到已儲存的地址：${decision(SAVED_ADDRESS, null)}")
    log("出金到從交易紀錄複製的假地址（頭尾相同）：${decision(lookAlike, null)}")
    log("使用者確認後把假地址加入了 1 小時，再出金：${decision(lookAlike, 1)}")
    log("加入滿 25 小時，再出金：${decision(lookAlike, 25)}")
}

// MARK: - Bug 版
private object L05Bug {
    fun isSaved(address: String): Boolean =
        address.take(6) == SAVED_ADDRESS.take(6) && address.takeLast(4) == SAVED_ADDRESS.takeLast(4)   // 只核對頭尾

    suspend fun run(log: LabLog) = scenario(log, ::isSaved)
}

// MARK: - 修正版
private object L05Fixed {
    fun isSaved(address: String): Boolean =
        address.equals(SAVED_ADDRESS, ignoreCase = true)        // 完整比對整個地址

    suspend fun run(log: LabLog) = scenario(log, ::isSaved)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "頭尾一樣的假地址被當成已儲存的地址",
    question = "第 7 題：「出金到新地址」的安全流程怎麼設計？（address poisoning）",
    goal = "判斷地址是不是已儲存，要比對整個地址，不能只比頭尾幾碼；沒見過的地址一律走新地址的冷卻流程。",
    steps = listOf(
        "選「Bug 版」執行，看從交易紀錄複製的假地址（頭尾相同）會被放行還是拒絕。",
        "想一想：使用者核對地址時通常看哪幾碼？攻擊者為什麼能做出頭尾一樣的地址？",
        "選「修正版」執行：假地址是新地址，要先加入並冷卻 24 小時，滿了才放行。",
        "進階：完整的流程還要有分段顯示地址讓使用者核對、生物辨識加 2FA、通知與稽核紀錄（第 7 題）。",
    ),
    expectBug = "「出金到從交易紀錄複製的假地址（頭尾相同）：放行（已儲存的地址）」，沒有冷卻。",
    expectFixed = "假地址：「拒絕（新地址，先加入並冷卻 24 小時）」；加入 1 小時後「拒絕（新地址冷卻中，還要等 23 小時）」；滿 25 小時才「放行（新地址，冷卻已滿）」。",
    file = "advanced08-security/L05_AddressPoisoning.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "出金到已儲存的地址：放行（已儲存的地址）" !in lines -> "已儲存的地址應該被放行：isSaved 要認得 SAVED_ADDRESS 本身"
            "出金到從交易紀錄複製的假地址（頭尾相同）：放行（已儲存的地址）" !in lines -> "Bug 還沒重現：只比對開頭 6 碼和結尾 4 碼，假地址也會通過"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "出金到已儲存的地址：放行（已儲存的地址）" !in lines -> "已儲存的地址應該被放行：isSaved 要認得 SAVED_ADDRESS 本身"
            "出金到從交易紀錄複製的假地址（頭尾相同）：拒絕（新地址，先加入並冷卻 24 小時）" !in lines -> "假地址應該被當成新地址：isSaved 要完整比對整個地址"
            "加入滿 25 小時，再出金：放行（新地址，冷卻已滿）" !in lines -> "新地址冷卻滿 24 小時後應該放行"
            else -> null
        }
    },
)
