// L03：升級了套件，CI 卻還在用舊版本編出來的東西（快取的 key 沒有隨鎖定檔改變）
// 對應複習站：Testing06CI 第 2 題「CI 太慢的時候，怎麼讓它變快？」（快取）
//
// 情境：CI 把「編好的套件」存進快取，下次有同樣的 key 就直接拿來用，省下重新編譯的時間。
//       cacheKey(os, lockfile) 決定快取的 key。三天的情況：
//       第 1 天：鎖定檔寫 okhttp@4.11.0，第一次建置，沒有快取，編好後存進去。
//       第 2 天：有人把套件升級到 4.12.0，鎖定檔改了。
//       第 3 天：鎖定檔沒有再改。
//
// 你要寫：
//   ① Bug 版：cacheKey 回傳固定的 "gradle-<os>"（和鎖定檔無關）。
//   ② 修正版：cacheKey 回傳 "gradle-<os>-<鎖定檔內容的雜湊>"，雜湊用 T06Hash.shortHash(lockfile)。
// 只改有 TODO 的地方。解答：solution/testing/testing06-ci/L03_StaleCache.kt
package tw.harry.labs.practice.testing06ci

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：三天的建置，印出每天快取有沒有命中、用的是哪個版本 */
private fun scenario(log: LabLog, cacheKey: (os: String, lockfile: String) -> String) {
    val cache = T06Cache()
    for ((day, wanted) in listOf(1 to "4.11.0", 2 to "4.12.0", 3 to "4.12.0")) {
        val lockfile = T06Lockfile.content(wanted)
        val key = cacheKey("linux", lockfile)
        val cached = cache.restore(key)
        val artifact = cached ?: "用 ${T06Lockfile.version(lockfile)} 編出來的套件".also { cache.save(key, it) }   // 沒命中：重新編譯
        log("第 $day 天：快取${if (cached != null) "命中" else "沒命中"}，CI 拿到的是「$artifact」")
        if (wanted !in artifact) log("  ⚠ 鎖定檔要的是 $wanted，但 CI 用的是舊的產物")
    }
}

// MARK: - Bug 版
private object L03Bug {
    fun cacheKey(os: String, lockfile: String): String =
        // TODO ①：回傳固定的 "gradle-$os"，不要放任何和鎖定檔有關的東西
        //        （目前每次都回傳不同的 key，快取永遠不會命中）
        java.util.UUID.randomUUID().toString()

    fun run(log: LabLog) = scenario(log, ::cacheKey)
}

// MARK: - 修正版
private object L03Fixed {
    fun cacheKey(os: String, lockfile: String): String =
        // TODO ②：回傳 "gradle-$os-" 加上 T06Hash.shortHash(lockfile)，鎖定檔一改 key 就跟著變
        //        （目前每次都回傳不同的 key，快取永遠不會命中）
        java.util.UUID.randomUUID().toString()

    fun run(log: LabLog) = scenario(log, ::cacheKey)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "套件升級了，CI 還在用舊版本的快取",
    question = "第 2 題：CI 太慢的時候，怎麼讓它變快？（快取）",
    goal = "快取的 key 要包含「會影響內容的檔案」（鎖定檔）的雜湊：內容變了就換 key，沒變就命中。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看第 2 天快取有沒有命中、CI 拿到哪個版本，以及有沒有 ⚠ 的警告。",
        "想一想：快取命中是好事嗎？什麼情況下命中反而是 bug？",
        "寫 TODO ②，選「修正版」執行：第 2 天沒命中並重新編譯成 4.12.0，第 3 天命中。",
    ),
    expectBug = "第 1 天沒命中；第 2 天命中，拿到「用 4.11.0 編出來的套件」，並印出 ⚠ 鎖定檔要的是 4.12.0；第 3 天命中，還是 4.11.0。",
    expectFixed = "第 1 天沒命中；第 2 天沒命中，重新編譯成 4.12.0；第 3 天命中，拿到「用 4.12.0 編出來的套件」；沒有 ⚠。",
    file = "testing06-ci/L03_StaleCache.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "第 1 天：快取沒命中，CI 拿到的是「用 4.11.0 編出來的套件」" !in lines -> "第 1 天沒有快取，應該沒命中"
            "第 2 天：快取命中，CI 拿到的是「用 4.11.0 編出來的套件」" !in lines -> "Bug 還沒重現：key 要和鎖定檔無關（例如固定的 \"gradle-\$os\"），第 2 天才會命中舊的快取"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "第 2 天：快取沒命中，CI 拿到的是「用 4.12.0 編出來的套件」" !in lines -> "第 2 天鎖定檔變了，key 要不一樣（加上 T06Hash.shortHash(lockfile)），快取才不會命中"
            "第 3 天：快取命中，CI 拿到的是「用 4.12.0 編出來的套件」" !in lines -> "第 3 天鎖定檔沒變，key 要和第 2 天一樣，快取才會命中（不要放日期或亂數）"
            else -> null
        }
    },
)
