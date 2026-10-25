// L04：Debug 版正常，Release 版一打開訂單畫面就當掉（R8 混淆後，反射找不到建構子與欄位）
// 對應複習站：Platform07Release 第 6 題「上線後 crash 報告看不懂，要怎麼處理？（dSYM 與 mapping）」（R8 與 keep rules）
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在 solution/platform/test/Platform07ReleaseTest.kt，
// 它會真的呼叫 R8（Android 的程式碼縮減與混淆工具），用你寫的規則處理下面三個類別，再載入結果執行。
//
// 情境：App 用反射把伺服器回傳的 JSON 對應到 Kotlin 物件，做法跟 Gson 的反射模式一樣：用 getDeclaredConstructor() 建立物件，
//        欄位名稱等於 JSON 的 key，就把值寫進去。Debug 版沒開 R8，一切正常；Release 版開了 R8，沒有任何程式碼「直接」
//        呼叫那個建構子、讀寫那些欄位，R8 判斷它們沒用到：把建構子刪掉，類別和欄位改成 a、b。
// Bug 版：proguard-rules.pro 只寫了入口類別的規則。DTO（Data Transfer Object，專門裝伺服器資料的物件）的建構子被刪、名稱被改，
//        一打開訂單畫面就丟 NoSuchMethodException。（Debug 版因為沒有混淆，完全看不出來。）
// 修正版：用 -keepclassmembers 保留 DTO 的建構子與欄位，名稱不改、也不刪。
package tw.harry.labs.solution.platform07release

import tw.harry.labs.kit.Lab

/** 伺服器回傳的訂單資料：用「欄位名稱」對應 JSON 的 key（兩個版本共用） */
internal class L04OrderDto {
    var symbol: String = ""
    var amount: String = ""
}

/** 反射版的 JSON 對應（兩個版本共用）：JSON 的 key 等於欄位名稱，就把值寫進去 */
internal object L04Mapper {
    fun <T : Any> fromJson(json: Map<String, String>, type: Class<T>): T {
        val instance = type.getDeclaredConstructor().newInstance()
        for (field in type.declaredFields) {
            val value = json[field.name] ?: continue
            field.isAccessible = true
            field.set(instance, value)
        }
        return instance
    }
}

/** App 的入口（兩個版本共用）：收到伺服器的訂單 JSON（已經拆成 Map），回傳畫面上要顯示的文字 */
class L04Entry {
    fun describe(): String {
        val dto = L04Mapper.fromJson(mapOf("symbol" to "BTC/JPY", "amount" to "0.5"), L04OrderDto::class.java)
        return "symbol=${dto.symbol}, amount=${dto.amount}"
    }
}

// MARK: - Bug 版
internal object L04Bug {
    /** proguard-rules.pro 的內容：只寫了入口類別（沒有它，整個 App 都被 R8 當成沒用到而刪掉） */
    val rules: List<String> = listOf(
        "-keep class ${L04Entry::class.java.name} { public *; }",
    )
}

// MARK: - 修正版
internal object L04Fixed {
    val rules: List<String> = listOf(
        "-keep class ${L04Entry::class.java.name} { public *; }",
        "-keepclassmembers class ${L04OrderDto::class.java.name} { <init>(); <fields>; }",   // DTO 的建構子與欄位：不改名、不刪除
    )
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "Debug 版正常，Release 版一開訂單畫面就當掉",
    question = "第 6 題：上線後 crash 報告看不懂，要怎麼處理？（dSYM 與 mapping）（R8 與 keep rules）",
    goal = "R8 開啟後，靠反射使用的類別與欄位要用 keep rules 保護，名稱不能被改、也不能被刪。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。終端機：cd labs/android && ./gradlew :solution-platform:testDebugUnitTest --tests '*Platform07ReleaseTest.l04' -i | grep SELFTEST（練習版把 solution 換成 practice）。",
        "看 Bug 版：只保留入口類別時，R8 處理後執行得到什麼錯誤（訊息裡的 a.a 是被改名後的 DTO）。",
        "想一想：沒有任何程式碼寫死「symbol」這個欄位名稱，R8 怎麼知道不能改它？",
        "看修正版：加上 -keepclassmembers 之後，執行結果是不是 symbol=BTC/JPY, amount=0.5。",
        "進階：真的專案用 ./gradlew :app:assembleRelease 建置，混淆前後的對照檔在 app/build/outputs/mapping/release/mapping.txt，要和每個版本一起保存（上傳到 Crashlytics 或 Play Console）。",
    ),
    expectBug = "R8 處理後執行時丟出 NoSuchMethodException（訊息像 a.a.<init>()）：DTO 的建構子被刪、類別被改名。",
    expectFixed = "R8 處理後執行得到「symbol=BTC/JPY, amount=0.5」。",
    file = "platform07-release/L04_R8KeepRules.kt",
)
