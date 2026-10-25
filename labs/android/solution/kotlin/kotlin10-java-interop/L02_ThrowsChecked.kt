// L02：Java 的 catch (IOException) 編譯不過（Kotlin 沒有 checked exception，要標 @Throws）
// 對應複習站：Kotlin10JavaInterop 第 3 題「混用時還有哪些容易踩到的地方？」
//
// 情境：Kotlin 寫的 Loader.load() 連線逾時時會丟 IOException（Java 的 checked exception：編譯器強制呼叫端處理或宣告的例外）。
//        Java 呼叫端會寫 try { loader.load("a"); } catch (IOException e) { … }。
//        演練用反射查看 Java 眼中的 load() 簽名有沒有 `throws IOException`。
//        （沒有的話，Java 的 catch (IOException e) 會編譯錯誤：「這個例外從未被宣告可能丟出」。）
// Bug 版：load() 沒有標 @Throws。Kotlin 編譯出來的方法簽名沒有 throws，Java 的 catch (IOException) 編譯不過。
// 修正版：load() 加上 @Throws(IOException::class)，簽名有 `throws IOException`。
//        （同樣的規則也適用於 Kotlin Multiplatform 匯出給 Swift 的 suspend fun：沒標 @Throws，例外 Swift 端接不住。）
package tw.harry.labs.solution.kotlin10javainterop

import java.io.IOException
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：Kotlin 呼叫 load() 會丟 IOException；再用反射查看 Java 眼中的簽名 */
private fun scenario(log: LabLog, loader: Class<*>, load: () -> String) {
    val kotlinThrows = try {
        load()
        false
    } catch (e: IOException) {
        true
    }
    log("Kotlin 呼叫 load() 會丟出 IOException：${if (kotlinThrows) "是" else "否"}")
    val declared = loader.getMethod("load", String::class.java).exceptionTypes.map { it.simpleName }
    log("Java 眼中的簽名：String load(String path) throws ${declared.joinToString().ifEmpty { "（沒有）" }}")
    log("Java 的 catch (IOException e) 能編譯：${if (IOException::class.java in loader.getMethod("load", String::class.java).exceptionTypes) "是" else "否"}")
}

// MARK: - Bug 版
private object L02Bug {
    class Loader {
        fun load(path: String): String = throw IOException("連線逾時：$path")      // 沒有 @Throws
    }

    fun run(log: LabLog) = scenario(log, Loader::class.java) { Loader().load("/orders") }
}

// MARK: - 修正版
private object L02Fixed {
    class Loader {
        @Throws(IOException::class)                                              // 簽名加上 throws IOException
        fun load(path: String): String = throw IOException("連線逾時：$path")
    }

    fun run(log: LabLog) = scenario(log, Loader::class.java) { Loader().load("/orders") }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "Java 的 catch (IOException) 編譯不過",
    question = "第 3 題：混用時還有哪些容易踩到的地方？",
    goal = "Kotlin 沒有 checked exception，編譯出來的方法簽名不會有 throws。看沒標 @Throws 時，Java 眼中的簽名是什麼，再補上 @Throws。",
    steps = listOf(
        "選「Bug 版」執行，看「Java 眼中的簽名」和「Java 的 catch (IOException e) 能編譯」。",
        "選「修正版」執行，確認簽名有 throws IOException，Java 的 catch 能編譯。",
        "想一想：Kotlin Multiplatform 把 suspend fun 匯出給 Swift 時，沒標 @Throws 會怎樣？（例外 Swift 端接不住，App 直接終止）",
    ),
    expectBug = "「Java 眼中的簽名：String load(String path) throws （沒有）」，「Java 的 catch (IOException e) 能編譯：否」。",
    expectFixed = "「Java 眼中的簽名：String load(String path) throws IOException」，「Java 的 catch (IOException e) 能編譯：是」。",
    file = "kotlin10-java-interop/L02_ThrowsChecked.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "Kotlin 呼叫 load() 會丟出 IOException：是" !in lines -> "Kotlin 呼叫 load() 要丟出 IOException（throw IOException(\"連線逾時：\$path\")）"
            "Java 的 catch (IOException e) 能編譯：是" in lines -> "簽名已經有 throws IOException：Bug 版的 load() 不要加 @Throws"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "Kotlin 呼叫 load() 會丟出 IOException：是" !in lines -> "Kotlin 呼叫 load() 要丟出 IOException（throw IOException(\"連線逾時：\$path\")）"
            "Java 的 catch (IOException e) 能編譯：是" !in lines -> "簽名還沒有 throws IOException：在 load() 上加 @Throws(IOException::class)"
            else -> null
        }
    },
)
