// L01：用 abiFilters 把 AAB 縮小了 39 MB，arm64 手機的下載大小卻一點都沒少（上傳大小、下載大小不是同一個數字）
// 對應複習站：Advanced07AppSize 第 1 題「App 體積有哪幾種？為什麼說『變小了』一定要先講是哪一種？」（ABI 篩選見第 3 題）
//
// 情境：AAB 裡有 4 種 CPU 架構的原生函式庫（arm64-v8a 20 MB、armeabi-v7a 16 MB、x86 18 MB、x86_64 21 MB），
//       還有各種螢幕密度的資源。工程師用 `ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }` 拿掉了 x86 與 x86_64。
//       Google Play 會依使用者的手機，只送「基本 APK + 這台手機的 ABI + 這台手機的螢幕密度」。
//       演練用未壓縮的大小（單位 MB）模擬，比較改動前後：AAB 的大小，以及 arm64-v8a 手機與 armeabi-v7a 手機的下載大小。
//
// 你要寫：
//   ① Bug 版：下載大小就是所有檔案加起來（以為下載的就是整個 AAB）。AAB 少了 39 MB，就宣稱每支手機都少下載 39 MB。
//   ② 修正版：依裝置算：不分 ABI／密度的檔案都要算，原生函式庫只算這支手機的 ABI，資源只算這支手機的螢幕密度。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced07-app-size/L01_DownloadSize.kt
package tw.harry.labs.practice.advanced07appsize

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** AAB 裡的一個檔案：abi 或 density 不是 null，代表只有符合的手機會下載它 */
private class L01Entry(val name: String, val mb: Int, val abi: String? = null, val density: String? = null)

private class L01Device(val name: String, val abi: String, val density: String)

private val devices = listOf(
    L01Device("arm64-v8a 手機", "arm64-v8a", "xxhdpi"),
    L01Device("armeabi-v7a 手機", "armeabi-v7a", "xhdpi"),
)

private val aab = listOf(
    L01Entry("classes.dex", 25),
    L01Entry("基本資源", 4),
    L01Entry("lib/arm64-v8a", 20, abi = "arm64-v8a"),
    L01Entry("lib/armeabi-v7a", 16, abi = "armeabi-v7a"),
    L01Entry("lib/x86", 18, abi = "x86"),
    L01Entry("lib/x86_64", 21, abi = "x86_64"),
    L01Entry("res/xxhdpi", 10, density = "xxhdpi"),
    L01Entry("res/xhdpi", 7, density = "xhdpi"),
    L01Entry("res/hdpi", 5, density = "hdpi"),
    L01Entry("res/mdpi", 3, density = "mdpi"),
)

/** abiFilters 之後，AAB 剩下的檔案（兩個版本共用） */
private fun entriesAfterFilter(abiFilters: Set<String>) = aab.filter { it.abi == null || it.abi in abiFilters }

/** 演練流程（兩個版本共用）：downloadSize(AAB 內容, 裝置) 回傳這支手機的下載大小 */
private fun scenario(log: LabLog, downloadSize: (List<L01Entry>, L01Device) -> Int) {
    val before = aab
    val after = entriesAfterFilter(setOf("arm64-v8a", "armeabi-v7a"))
    log("AAB 大小：${before.sumOf { it.mb }} MB → ${after.sumOf { it.mb }} MB（abiFilters 只留 arm64-v8a、armeabi-v7a）")
    for (device in devices) {
        val b = downloadSize(before, device)
        val a = downloadSize(after, device)
        log("${device.name}的下載大小：$b MB → $a MB（少 ${b - a} MB）")
    }
}

// MARK: - Bug 版
private object L01Bug {
    fun downloadSize(entries: List<L01Entry>, device: L01Device): Int {
        // TODO ①：把所有檔案的 mb 加起來回傳（以為使用者下載的就是整個 AAB）
        return 0
    }

    suspend fun run(log: LabLog) = scenario(log, ::downloadSize)
}

// MARK: - 修正版
private object L01Fixed {
    fun downloadSize(entries: List<L01Entry>, device: L01Device): Int {
        // TODO ②：依裝置計算：先挑出這支手機會下載的檔案，再把 mb 加起來。
        //         - abi 是 null 的檔案：每支手機都下載；abi 不是 null：只有 device.abi 一樣的才下載
        //         - density 是 null 的檔案：每支手機都下載；density 不是 null：只有 device.density 一樣的才下載
        return 0
    }

    suspend fun run(log: LabLog) = scenario(log, ::downloadSize)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "AAB 縮小 39 MB，下載大小一點都沒少",
    question = "第 1 題：App 體積有哪幾種？為什麼說「變小了」一定要先講是哪一種？（ABI 篩選）",
    goal = "AAB 的大小不等於使用者的下載大小：Google Play 只送這支手機需要的 ABI 與螢幕密度。要說「變小了」，先說是哪一種大小。 先寫出把 AAB 當成下載大小的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced07-app-size/L01_DownloadSize.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看 AAB 與兩支手機的「下載大小」：每支手機都少了多少？",
        "想一想：arm64-v8a 的手機，本來就會下載 x86 的函式庫嗎？Google Play 會送什麼給它？",
        "寫 TODO ②，選「修正版」執行，比較兩支手機的下載大小有沒有變。",
        "進階：真正的 AAB 用 `bundletool build-apks` 產生各種裝置的 APK，再用 `bundletool get-size total` 看下載大小；Play Console 的 App size 報告也有。",
    ),
    expectBug = "「AAB 大小：129 MB → 90 MB」，兩支手機都是「少 39 MB」（Bug 版把 AAB 的差距當成使用者的差距）。",
    expectFixed = "AAB 一樣是 129 MB → 90 MB，但「arm64-v8a 手機的下載大小：59 MB → 59 MB（少 0 MB）」、「armeabi-v7a 手機的下載大小：52 MB → 52 MB（少 0 MB）」。",
    file = "advanced07-app-size/L01_DownloadSize.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("arm64-v8a 手機的下載大小") } -> "演練沒有跑完：要用 scenario 執行"
            "arm64-v8a 手機的下載大小：129 MB → 90 MB（少 39 MB）" !in lines -> "Bug 還沒重現：下載大小直接把所有檔案加起來（AAB 的大小），改動前 129 MB、改動後 90 MB"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("arm64-v8a 手機的下載大小") } -> "演練沒有跑完：要用 scenario 執行"
            "arm64-v8a 手機的下載大小：59 MB → 59 MB（少 0 MB）" !in lines ->
                "arm64-v8a 手機應該是 59 MB → 59 MB：不分 ABI 的檔案 + lib/arm64-v8a + res/xxhdpi"
            "armeabi-v7a 手機的下載大小：52 MB → 52 MB（少 0 MB）" !in lines ->
                "armeabi-v7a 手機應該是 52 MB → 52 MB：不分 ABI 的檔案 + lib/armeabi-v7a + res/xhdpi"
            else -> null
        }
    },
)
