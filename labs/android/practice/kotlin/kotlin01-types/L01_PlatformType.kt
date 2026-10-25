// L01：SDK 回了 null，到畫面顯示時才 NPE（platform type）
// 對應複習站：Kotlin01Types 第 1 題「Java 傳進來的值，為什麼會在 Kotlin 裡突然
// NPE？」
//
// 情境：App 用第三方 SDK（Java 寫的）取得會員資料。使用者還沒設定暱稱時，SDK
// 的 getNickname() 回傳 null，
//        但 SDK 沒有任何 @Nullable 標註。資料層把暱稱交給 ViewModel，
// 畫面再拿來顯示。
//
// 你要寫：
//   ① Bug 版（L01Bug）：資料層直接回傳 sdk.nickname，不寫型別。
// 跑起來會看到資料層沒出錯，畫面顯示時才 NPE。
//   ② 修正版（L01Fixed）：在資料層（邊界）明確宣告回傳 String，並把 null
// 換成預設值。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin01-types/L01_PlatformType.kt
package tw.harry.labs.practice.kotlin01types

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

// MARK: - Bug 版
private object L01Bug {
    class ProfileRepository(private val sdk: SdkProfile) {
        // TODO ①：回傳 sdk.nickname。不要寫回傳型別（讓 Kotlin 推斷成
        // String!），也不要處理 null
        fun nickname(): String = ""
    }

    class ProfileViewModel(repo: ProfileRepository) {
        // 沿用資料層的型別，不用改
        val nickname = repo.nickname()
    }

    /** 畫面層：直接用 nickname.length，null 時在這一行 NPE（已寫好） */
    fun render(vm: ProfileViewModel): String =
        "歡迎，${vm.nickname}（${vm.nickname.length} 個字）"

    fun show(label: String, sdk: SdkProfile, log: LabLog) {
        val vm = ProfileViewModel(ProfileRepository(sdk))
        log("$label：資料層取得完成，沒有出錯")
        try {
            log("$label：${render(vm)}")
        } catch (e: NullPointerException) {
            log("$label：畫面顯示時 NullPointerException")
        }
    }
}

// MARK: - 修正版
private object L01Fixed {
    class ProfileRepository(private val sdk: SdkProfile) {
        // TODO ②：明確寫回傳型別 String，回傳 sdk.nickname；如果是 null，用 ?:
        // 換成 "（未設定）"
        fun nickname(): String = ""
    }

    class ProfileViewModel(repo: ProfileRepository) {
        // 型別是 String，不可能是 null
        val nickname = repo.nickname()
    }

    fun render(vm: ProfileViewModel): String =
        "歡迎，${vm.nickname}（${vm.nickname.length} 個字）"

    fun show(label: String, sdk: SdkProfile, log: LabLog) {
        val vm = ProfileViewModel(ProfileRepository(sdk))
        log("$label：資料層取得完成，沒有出錯")
        try {
            log("$label：${render(vm)}")
        } catch (e: NullPointerException) {
            log("$label：畫面顯示時 NullPointerException")
        }
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "SDK 回了 null，畫面顯示時才 NPE",
    question = "第 1 題：Java 傳進來的值，為什麼會在 Kotlin 裡突然 NPE？",
    goal = "沒設定暱稱的會員，SDK 回傳 null。先寫出到畫面才爆 NPE 的 Bug 版，再把 null 擋在資料層。",
    steps = listOf(
        "打開 practice/kotlin/kotlin01-types/L01_PlatformType.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「資料層取得完成，沒有出錯」和後面「畫面顯示時 NullPointerException」是不是相隔很遠。",
        "寫 TODO ②（修正版），選「修正版」執行，確認沒設定暱稱的會員也能顯示「（未設定）」。",
        "想一想：如果 SDK 是自己維護的 Java 程式，在 getNickname() 加上 @Nullable，Kotlin 端會怎樣？（編譯器會把它當成 String?，直接逼你處理）",
    ),
    expectBug = "會員 A 正常顯示「歡迎，harry（5 個字）」；會員 B 資料層沒有出錯，一直到畫面顯示時才出現 NullPointerException。",
    expectFixed = "會員 B 顯示「歡迎，（未設定）（5 個字）」，沒有任何例外。",
    file = "kotlin01-types/L01_PlatformType.kt",
    run = { variant, log ->
        val withNickname = SdkProfile("harry")
        val noNickname = SdkProfile(null) // 還沒設定暱稱
        when (variant) {
            Variant.BUG -> {
                L01Bug.show("會員 A", withNickname, log)
                L01Bug.show("會員 B", noNickname, log)
            }

            Variant.FIXED -> {
                L01Fixed.show("會員 A", withNickname, log)
                L01Fixed.show("會員 B", noNickname, log)
            }
        }
    },
    checkBug = { lines ->
        when {
            "會員 A：歡迎，harry（5 個字）" !in lines -> "會員 A 的暱稱不見了：資料層要回傳 sdk.nickname"
            "會員 B：資料層取得完成，沒有出錯" !in lines -> "會員 B 在資料層就出錯了：Bug 版不要在資料層處理 null"
            "會員 B：畫面顯示時 NullPointerException" !in lines -> "會員 B 沒有 NPE：Bug 版要把 sdk.nickname 原樣傳下去（不要加 ?:，也不要寫成 String?）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "會員 A：歡迎，harry（5 個字）" !in lines -> "會員 A 的暱稱不見了：有暱稱時要回傳 sdk.nickname"

            lines.any {
                it.contains("NullPointerException")
            } -> "還是 NPE：資料層要在回傳前把 null 換成預設值"

            "會員 B：歡迎，（未設定）（5 個字）" !in lines -> "會員 B 要顯示「（未設定）」：資料層用 ?: \"（未設定）\""

            else -> null
        }
    },
)
