package tw.harry.labs.practice

import com.android.tools.r8.ClassFileConsumer
import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.JdkClassFileProvider
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test
import tw.harry.labs.practice.platform07release.L04Bug
import tw.harry.labs.practice.platform07release.L04Entry
import tw.harry.labs.practice.platform07release.L04Fixed

/**
 * Platform07Release L04：真的用 R8 處理一次，看反射找不到建構子與欄位的問題（解答：Bug 版要重現問題、修正版要正確）。
 *
 * 在 Android Studio 點測試旁邊的 ▶ 執行；結果印成 [SELFTEST] 開頭的行，FAIL 的後面會說還差什麼。
 * 還沒寫完時印 FAIL 是正常的，這個測試不會因此失敗。
 */
class Platform07ReleaseTest {
    /** R8 處理後執行的結果 */
    private sealed class Outcome {
        /** 入口類別不見了：規則沒有保留入口，R8 把整個 App 都刪掉 */
        object EntryMissing : Outcome()

        /** R8 看不懂規則（語法寫錯） */
        class RuleError(val error: String) : Outcome()

        /** 執行時出錯 */
        class Crashed(val error: String) : Outcome()

        /** 正常執行，拿到畫面上要顯示的文字 */
        class Worked(val text: String) : Outcome()
    }

    private fun jarOf(clazz: Class<*>): Path =
        Path.of(clazz.protectionDomain!!.codeSource!!.location.toURI())

    /**
     * 用 rules 當 R8 的規則，處理 L04 的三個類別（入口、反射對應、DTO），
     * 再載入處理後的結果，呼叫入口
     */
    private fun runWithR8(rules: List<String>): Outcome {
        val dir = Files.createTempDirectory("r8-lab")
        val output = dir.resolve("app-release.jar")
        val kotlinStdlib = jarOf(Unit::class.java)
        val entryName = L04Entry::class.java.name
        val classNames =
            listOf(
                entryName,
                entryName.replace("L04Entry", "L04Mapper"),
                entryName.replace("L04Entry", "L04OrderDto"),
            )

        val builder = R8Command.builder()
            .setMode(CompilationMode.RELEASE)
            .setProgramConsumer(ClassFileConsumer.ArchiveConsumer(output))
            .addLibraryResourceProvider(
                JdkClassFileProvider.fromJdkHome(
                    Path.of(System.getProperty("java.home")),
                ),
            )
            .addLibraryFiles(kotlinStdlib)
        for (name in classNames) {
            val bytes = L04Entry::class.java.getResourceAsStream(
                "/" + name.replace('.', '/') + ".class",
            )!!.readBytes()
            builder.addClassProgramData(bytes, Origin.unknown())
        }
        builder.addProguardConfiguration(
            rules + listOf("-dontwarn **"),
            Origin.unknown(),
        )
        try {
            try {
                R8.run(builder.build())
            } catch (e: CompilationFailedException) {
                return Outcome.RuleError(
                    e.cause?.message ?: e.message ?: "R8 失敗",
                )
            }
            // 父層放 null：不要借用測試自己載入的那份 L04Entry，只用 R8
            // 處理後的結果（加上 Kotlin 標準函式庫）
            val loader =
                URLClassLoader(
                    arrayOf(
                        output.toUri().toURL(),
                        kotlinStdlib.toUri().toURL(),
                    ),
                    null,
                )
            val entry = try {
                Class.forName(entryName, true, loader)
            } catch (e: ClassNotFoundException) {
                return Outcome.EntryMissing
            }
            return try {
                Outcome.Worked(
                    entry.getMethod(
                        "describe",
                    ).invoke(
                        entry.getDeclaredConstructor().newInstance(),
                    ) as String,
                )
            } catch (e: Exception) {
                Outcome.Crashed(
                    "${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}",
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun report(
        variant: String,
        reason: String?,
        detail: String,
        failures: MutableList<String>,
    ) {
        if (reason == null) {
            println("[SELFTEST] Platform07Release L04 $variant PASS　$detail")
        } else {
            println("[SELFTEST] Platform07Release L04 $variant FAIL：$reason")
            failures += "$variant：$reason"
        }
    }

    @Test
    fun l04() {
        val failures = mutableListOf<String>()

        val bug = runWithR8(L04Bug.rules)
        val bugReason = when (bug) {
            Outcome.EntryMissing -> "入口類別被 R8 刪掉了：Bug 版至少要寫入口類別的規則 -keep class …L04Entry { public *; }，不然整個 App 都消失"
            is Outcome.RuleError -> "R8 看不懂你寫的規則：${bug.error}"
            is Outcome.Worked -> "Bug 還沒重現：Release 版應該一打開訂單畫面就出錯，但是 R8 處理後還能正常執行（${bug.text}）：Bug 版只保留入口類別，不要保留 DTO 的建構子與欄位"
            is Outcome.Crashed -> null
        }
        report(
            "Bug 版",
            bugReason,
            "R8 處理後執行時出錯：${(bug as? Outcome.Crashed)?.error}",
            failures,
        )

        val fixed = runWithR8(L04Fixed.rules)
        val fixedReason = when (fixed) {
            Outcome.EntryMissing -> "入口類別被 R8 刪掉了：修正版也要保留入口類別 -keep class …L04Entry { public *; }"

            is Outcome.RuleError -> "R8 看不懂你寫的規則：${fixed.error}"

            is Outcome.Crashed -> "R8 處理後執行還是出錯（${fixed.error}）：用 -keepclassmembers class …L04OrderDto { <init>(); <fields>; } 保留 DTO 的建構子與欄位"

            is Outcome.Worked -> if (fixed.text ==
                "symbol=BTC/JPY, amount=0.5"
            ) {
                null
            } else {
                "欄位的值不對（${fixed.text}）：欄位名稱不能被改"
            }
        }
        report(
            "修正版",
            fixedReason,
            "R8 處理後執行得到 ${(fixed as? Outcome.Worked)?.text}",
            failures,
        )

        println("[SELFTEST] Platform07Release L04 還差 ${failures.size} 個版本沒通過")
    }
}
