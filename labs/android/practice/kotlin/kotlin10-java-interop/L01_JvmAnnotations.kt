// L01：Java 呼叫 Kotlin 的 API 很難用（@JvmOverloads、@JvmStatic、@JvmField）
// 對應複習站：Kotlin10JavaInterop 第 1 題「Kotlin 寫的程式給 Java 呼叫時，要加哪些註解？」
//
// 情境：App 有一個 Kotlin 寫的 Client，要給 Java 寫的模組（或合作廠商的 SDK）呼叫。它有三個地方：
//        有預設值的建構子參數（timeoutMs）、companion object 裡的 create()、companion object 裡的 TAG。
//        演練用 Java 的反射（reflection，執行時查看類別有哪些成員）查看「Java 眼中的 Client」，
//        結果和 Java 程式碼 `new Client(url)`、`Client.create()`、`Client.TAG` 能不能編譯一致。
//
// 你要寫：
//   ① Bug 版（L01Bug.Client）：Client(val baseUrl: String, val timeoutMs: Long = 10_000)，companion object 裡 create() 與 TAG，什麼註解都不加。
//   ② 修正版（L01Fixed.Client）：同樣的 Client，建構子加 @JvmOverloads、create() 加 @JvmStatic、TAG 加 @JvmField。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin10-java-interop/L01_JvmAnnotations.kt
package tw.harry.labs.practice.kotlin10javainterop

import java.lang.reflect.Modifier
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：先確認 Kotlin 這邊的 API 正常，再用反射查看 Java 看到什麼 */
private fun scenario(log: LabLog, client: Class<*>, kotlinApiWorks: Boolean) {
    log("Kotlin 這邊的 API 正常（預設逾時 10 秒、create()、TAG）：${if (kotlinApiWorks) "是" else "否"}")
    val oneArgConstructor = client.constructors.any { !it.isSynthetic && it.parameterCount == 1 }
    log("Java 能寫 new Client(url)（只傳一個參數）：${if (oneArgConstructor) "是" else "否"}")
    val staticCreate = client.methods.any { it.name == "create" && Modifier.isStatic(it.modifiers) }
    log("Java 能寫 Client.create()（真正的 static 方法）：${if (staticCreate) "是" else "否"}")
    val publicTag = runCatching { client.getField("TAG") }.map { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }.getOrDefault(false)
    log("Java 能寫 Client.TAG（公開的 static 欄位）：${if (publicTag) "是" else "否"}")
}

// MARK: - Bug 版
private object L01Bug {
    // TODO ①：寫出 Client，什麼註解都不加：建構子 Client(val baseUrl: String, val timeoutMs: Long = 10_000)；
    //         companion object 裡 fun create() = Client("https://api.example.com") 與 val TAG = "Client"
    class Client(val baseUrl: String, val timeoutMs: Long = 0) {
        companion object {
            fun create() = Client("")
            val TAG = ""
        }
    }

    fun run(log: LabLog) = scenario(
        log,
        Client::class.java,
        kotlinApiWorks = Client.create().baseUrl == "https://api.example.com" && Client("x").timeoutMs == 10_000L && Client.TAG == "Client",
    )
}

// MARK: - 修正版
private object L01Fixed {
    // TODO ②：同樣的 Client（timeoutMs 預設 10_000、create() 回傳 Client("https://api.example.com")、TAG 是 "Client"），
    //         建構子加 @JvmOverloads（Client @JvmOverloads constructor(…)）、create() 加 @JvmStatic、TAG 加 @JvmField
    class Client(val baseUrl: String, val timeoutMs: Long = 0) {
        companion object {
            fun create() = Client("")
            val TAG = ""
        }
    }

    fun run(log: LabLog) = scenario(
        log,
        Client::class.java,
        kotlinApiWorks = Client.create().baseUrl == "https://api.example.com" && Client("x").timeoutMs == 10_000L && Client.TAG == "Client",
    )
}

// MARK: - 演練設定
private const val KOTLIN_OK = "Kotlin 這邊的 API 正常（預設逾時 10 秒、create()、TAG）：是"
private const val ONE_ARG = "Java 能寫 new Client(url)（只傳一個參數）："
private const val STATIC_CREATE = "Java 能寫 Client.create()（真正的 static 方法）："
private const val PUBLIC_TAG = "Java 能寫 Client.TAG（公開的 static 欄位）："

private fun yes(lines: List<String>, prefix: String): Boolean = lines.any { it == prefix + "是" }

val l01 = Lab(
    id = "L01",
    title = "Java 呼叫 Kotlin 的 API 很難用",
    question = "第 1 題：Kotlin 寫的程式給 Java 呼叫時，要加哪些註解？",
    goal = "同一個 Client 要給 Java 呼叫。看沒加註解時 Java 眼中的 API 長怎樣（用反射查看），再補上 @JvmOverloads、@JvmStatic、@JvmField。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin10-java-interop/L01_JvmAnnotations.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看三行「Java 能寫…」的結果（反射看到的，等於 Java 程式碼能不能這樣寫）。",
        "寫 TODO ②（修正版），選「修正版」執行，確認三行都是「是」。",
        "想一想：沒加註解時，Java 要怎麼呼叫？（new Client(url, 10_000L)、Client.Companion.create()、Client.Companion.getTAG()）",
    ),
    expectBug = "「Kotlin 這邊的 API 正常」是「是」，三行「Java 能寫…」全部是「否」。",
    expectFixed = "三行「Java 能寫…」全部是「是」。",
    file = "kotlin10-java-interop/L01_JvmAnnotations.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            KOTLIN_OK !in lines -> "Kotlin 這邊的 API 不正常：Client 的 timeoutMs 預設 10_000，create() 回傳 Client(\"https://api.example.com\")，TAG 是 \"Client\""
            yes(lines, ONE_ARG) -> "Java 已經能只傳一個參數：Bug 版的建構子不要加 @JvmOverloads"
            yes(lines, STATIC_CREATE) -> "create() 已經是 static 方法：Bug 版不要加 @JvmStatic"
            yes(lines, PUBLIC_TAG) -> "TAG 已經是公開欄位：Bug 版不要加 @JvmField"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            KOTLIN_OK !in lines -> "Kotlin 這邊的 API 不正常：Client 的 timeoutMs 預設 10_000，create() 回傳 Client(\"https://api.example.com\")，TAG 是 \"Client\""
            !yes(lines, ONE_ARG) -> "Java 還不能只傳一個參數：建構子加上 @JvmOverloads（Client @JvmOverloads constructor(…)）"
            !yes(lines, STATIC_CREATE) -> "create() 還不是 static 方法：在 companion object 裡的 create() 加上 @JvmStatic"
            !yes(lines, PUBLIC_TAG) -> "TAG 還不是公開欄位：在 companion object 裡的 TAG 加上 @JvmField"
            else -> null
        }
    },
)
