// L03：移除廣告 SDK 說可以省 22 MB，實際只省了 18 MB（共用的依賴不能重複算）
// 對應複習站：Advanced07AppSize 第 4 題「為什麼精簡第三方依賴通常效果最大？怎麼找出誰最占空間？」
//
// 情境：App 整合了 5 個 SDK，它們又各自依賴其他函式庫（大小單位 MB）：
//         Charts 6 → Utils 4；Push 2 → Utils、FirebaseCore 3；Analytics 3 → FirebaseCore；
//         AdsSDK 5 → AdsAdapterA 6、AdsAdapterB 7、Utils；ImageLoader 2。
//       Utils 被 Charts、Push、AdsSDK 三個一起用，FirebaseCore 被 Push、Analytics 一起用（`./gradlew app:dependencies` 的依賴樹裡會重複出現）。
//       演練要算「移除某個 SDK 後，App 實際能少多少」，再依這個數字排出最值得處理的順序。
// Bug 版：把這個 SDK 底下所有依賴的大小加起來。共用的 Utils 被算進每一個用到它的 SDK；
//        移除廣告 SDK 後 Utils 還被 Charts、Push 使用，並不會少。各項「可省」加起來比整個 App 的依賴總和還大。
// 修正版：算「移除之前的依賴總和」減「移除之後還留著的依賴總和」。移除後，其餘 SDK 仍然用到的依賴不算省下來的。
package tw.harry.labs.solution.advanced07appsize

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val sizes = mapOf(
    "Charts" to 6, "Push" to 2, "Analytics" to 3, "AdsSDK" to 5, "ImageLoader" to 2,
    "Utils" to 4, "FirebaseCore" to 3, "AdsAdapterA" to 6, "AdsAdapterB" to 7,
)

private val dependencies = mapOf(
    "Charts" to listOf("Utils"),
    "Push" to listOf("Utils", "FirebaseCore"),
    "Analytics" to listOf("FirebaseCore"),
    "AdsSDK" to listOf("AdsAdapterA", "AdsAdapterB", "Utils"),
    "ImageLoader" to emptyList(),
)

private val roots = listOf("Charts", "Push", "Analytics", "AdsSDK", "ImageLoader")

/** 從這幾個 SDK 出發，會用到的所有依賴（含自己），每個只算一次（兩個版本共用） */
private fun reachable(from: List<String>): Set<String> {
    val seen = mutableSetOf<String>()
    val stack = ArrayDeque(from)
    while (stack.isNotEmpty()) {
        val name = stack.removeLast()
        if (seen.add(name)) stack.addAll(dependencies[name].orEmpty())
    }
    return seen
}

private fun totalSize(names: Set<String>) = names.sumOf { sizes.getValue(it) }

/** 演練流程（兩個版本共用）：savings(SDK) 回傳「移除這個 SDK 後，App 能少多少 MB」 */
private fun scenario(log: LabLog, savings: (String) -> Int) {
    val ranking = roots.map { it to savings(it) }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    for ((name, mb) in ranking) log("移除 $name 可省 $mb MB")
    log("最值得先處理：${ranking[0].first}")
    log("各項加總 ${ranking.sumOf { it.second }} MB（App 所有依賴總共 ${totalSize(reachable(roots))} MB）")
}

// MARK: - Bug 版
private object L03Bug {
    fun savings(root: String): Int =
        totalSize(reachable(listOf(root)))                      // 這個 SDK 底下所有依賴加起來，共用的也算進來

    suspend fun run(log: LabLog) = scenario(log, ::savings)
}

// MARK: - 修正版
private object L03Fixed {
    fun savings(root: String): Int {
        val remaining = roots.filter { it != root }             // 移除這個 SDK 之後，還留在 App 裡的 SDK
        return totalSize(reachable(roots)) - totalSize(reachable(remaining))
    }

    suspend fun run(log: LabLog) = scenario(log, ::savings)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "說能省 22 MB，實際只省 18 MB",
    question = "第 4 題：為什麼精簡第三方依賴通常效果最大？怎麼找出誰最占空間？",
    goal = "評估移除某個 SDK 能省多少，要看「移除之後，還有誰在用它的依賴」。共用的依賴不能算在每一個 SDK 頭上。",
    steps = listOf(
        "選「Bug 版」執行，看每個 SDK 的「可省」數字，以及最後一行：各項加總有沒有超過所有依賴的總和？",
        "想一想：Utils 同時被 Charts、Push、AdsSDK 使用，只移除 AdsSDK，Utils 會不會從 App 消失？",
        "選「修正版」執行：移除前與移除後各算一次所有依賴的總和，相減。",
        "進階：用 `./gradlew app:dependencies` 看依賴樹，APK Analyzer 看 dex、資源、原生函式庫各占多少；iOS 對應的是 link map（Write Link Map File）。",
    ),
    expectBug = "AdsSDK 可省 22 MB（最大）、Charts 10、Push 9、Analytics 6、ImageLoader 2；各項加總 49 MB，比所有依賴總共 38 MB 還大。",
    expectFixed = "AdsSDK 可省 18 MB（仍是最大）、Charts 6、Analytics 3、ImageLoader 2、Push 2；各項加總 31 MB，小於 38 MB（剩下的 7 MB 是共用的 Utils 與 FirebaseCore）。",
    file = "advanced07-app-size/L03_DependencyShare.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("各項加總") } -> "演練沒有跑完：要用 scenario 執行"
            "移除 AdsSDK 可省 22 MB" !in lines -> "Bug 還沒重現：把 AdsSDK 底下所有依賴的大小加起來（含共用的 Utils），應該是 22 MB"
            lines.none { it.startsWith("各項加總 49 MB") } -> "各項加總應該是 49 MB（比所有依賴的總和 38 MB 還大）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("各項加總") } -> "演練沒有跑完：要用 scenario 執行"
            "移除 AdsSDK 可省 18 MB" !in lines -> "移除 AdsSDK 應該只省 18 MB：Utils 還被 Charts、Push 使用，不算省下來的"
            "移除 Push 可省 2 MB" !in lines -> "移除 Push 應該只省 2 MB：Utils、FirebaseCore 還有別的 SDK 在用"
            lines.none { it.startsWith("各項加總 31 MB") } -> "各項加總應該是 31 MB"
            else -> null
        }
    },
)
