// L04：畫面都還沒出現，一堆 SDK 已經同步初始化完，而且有的因為順序不對，
// 功能不完整（ContentProvider、Application.onCreate、App Startup）
// 對應複習站：Advanced01Startup 第 5 題「Android 的 `Application.onCreate`、
// ContentProvider、App Startup 是什麼關係？」
//
// 情境：App 用了 5 個元件：Logger（10 ms）、CrashReporter（15 ms，要先有
// Logger）、Analytics（80 ms，要先有 Logger）、
//        AdsSdk（120 ms，要先有 Analytics）、ImageLoader（60 ms）。
// 首畫面只需要 Logger 與 CrashReporter；
//        Analytics、AdsSdk 要到使用者打開行情頁才用得到，ImageLoader
// 要等列表顯示圖片才需要。
//        （用一個縮小版的 AppInitializer 重現 App Startup 的規則；真正的
// androidx.startup 要 Context，JVM 單元測試跑不了。）
//
// 你要寫：
//   ① Bug 版 autoInitProviders：依 manifestOrder 的順序，把每個元件都 startup.
// create(name) 一次（ContentProvider 的行為：不管依賴、全部同步）。
//   ② 修正版 initializeComponent：已經初始化過就直接回傳；否則先把
// dependencies 都 initializeComponent，再 startup.create(name)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced01-startup/L04_AppInitializer.kt
package tw.harry.labs.practice.advanced01startup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 一個要初始化的元件：花費的毫秒數，和必須先初始化的元件（兩個版本共用） */
private class Component(
    val name: String,
    val costMs: Long,
    val dependencies: List<String> = emptyList(),
)

private val components = listOf(
    Component("Logger", 10),
    Component("CrashReporter", 15, listOf("Logger")),
    Component("Analytics", 80, listOf("Logger")),
    Component("AdsSdk", 120, listOf("Analytics")),
    Component("ImageLoader", 60),
)

/** manifest 裡各個 provider 宣告的順序（兩個版本共用） */
private val manifestOrder =
    listOf("AdsSdk", "Analytics", "ImageLoader", "Logger", "CrashReporter")

/**
 * 模擬每個 Initializer 的 create()：花時間做事；依賴的元件還沒初始化，
 * 就是「功能不完整」（兩個版本共用）
 */
private class Startup(private val log: LabLog) {
    val initialized = linkedSetOf<String>()

    fun create(name: String) {
        val component = components.first { it.name == name }
        val missing = component.dependencies.filter { it !in initialized }
        a01Work(component.costMs)
        initialized += name
        if (missing.isEmpty()) {
            log(
                "初始化 $name",
            )
        } else {
            log("初始化 $name（警告：${missing.joinToString()} 還沒初始化，功能不完整）")
        }
    }
}

/** 演練流程（兩個版本共用）：首幀之前 → 首幀 → 使用者打開行情頁 */
private suspend fun scenario(
    log: LabLog,
    beforeFirstFrame: (Startup) -> Unit,
    onMarketOpened: (Startup) -> Unit,
) = withContext(Dispatchers.Default) {
    val startup = Startup(log)
    val t0 = a01NowMs()
    beforeFirstFrame(startup)
    log(
        "首幀畫出來：${(a01NowMs() - t0).toInt()} ms，首幀前初始化了 ${startup.initialized.size} 個元件",
    )
    onMarketOpened(startup)
    log("打開行情頁之後，一共初始化了 ${startup.initialized.size} 個元件")
}

// MARK: - Bug 版
private object L04Bug {
    /** 每個函式庫的 ContentProvider，依 manifest 的順序一個一個同步初始化 */
    fun autoInitProviders(startup: Startup) {
        // TODO ①：依 manifestOrder 的順序，每個名稱都呼叫 startup.create(name)
    }

    suspend fun run(log: LabLog) = scenario(log, beforeFirstFrame = {
        autoInitProviders(it)
    }, onMarketOpened = { })
}

// MARK: - 修正版
private object L04Fixed {
    /**
     * 縮小版的 AppInitializer.initializeComponent：先初始化依賴，
     * 每個元件只做一次
     */
    fun initializeComponent(startup: Startup, name: String) {
        // TODO ②-1：name 已經在 startup.initialized 裡，就直接
        // return（每個元件只做一次）
        // TODO ②-2：先對 components.first { it.name == name }.dependencies
        // 的每一個呼叫 initializeComponent（依賴要先做）
        // TODO ②-3：最後才 startup.create(name)
    }

    suspend fun run(log: LabLog) = scenario(
        log,
        // 首畫面只要它（Logger 會跟著先做）
        beforeFirstFrame = { initializeComponent(it, "CrashReporter") },
        onMarketOpened = {
            // 行情頁要用：Analytics 會先做
            initializeComponent(it, "AdsSdk")
            // 再呼叫一次：不會重複初始化
            initializeComponent(it, "AdsSdk")
            initializeComponent(it, "ImageLoader")
        },
    )
}

// MARK: - 演練設定
private fun initLines(lines: List<String>) =
    lines.filter { it.startsWith("初始化 ") }

val l04 = Lab(
    id = "L04",
    title = "畫面還沒出現，SDK 已經全部初始化，還有的順序不對",
    question = "第 5 題：Android 的 `Application.onCreate`、ContentProvider、App Startup 是什麼關係？",
    goal = "啟動時只初始化首畫面必須的元件；其餘等用到才初始化，而且依賴要先做、每個只做一次。先寫出全部同步初始化的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced01-startup/L04_AppInitializer.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看每個元件初始化的順序、哪些有「警告」，以及首幀前花了多久。",
        "寫 TODO ②，選「修正版」執行：首幀前只有 Logger 與 CrashReporter；打開行情頁之後 Analytics 先於 AdsSdk，沒有警告，AdsSdk 也不會做兩次。",
        "進階：真正的 App 用 Perfetto（Android Studio 的 Profiler 選 Capture system activities）錄冷啟動，在 Application.onCreate 之前的 ContentProvider 區段，看每個函式庫的 provider 各花多少。",
        "想一想：manifest 裡 `tools:node=\"remove\"` 是在做什麼？之後怎麼手動叫它初始化？（見這個單元的第 5 題）",
    ),
    expectBug = "5 個元件全部在首幀前同步初始化（約 285 ms）；「初始化 AdsSdk」與「初始化 Analytics」後面帶警告（它們依賴的元件還沒初始化）。",
    expectFixed = "首幀前只初始化 Logger、CrashReporter（約 25 ms）；打開行情頁後依序初始化 Analytics、AdsSdk、ImageLoader，沒有警告，一共 5 個。",
    file = "advanced01-startup/L04_AppInitializer.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val before = a01Number(lines, "首幀畫出來：")
        val count = initLines(lines)
        when {
            before == null -> "演練沒有跑完"

            count.size < 5 -> "只初始化了 ${count.size} 個元件：Bug 版要依 manifestOrder 把 5 個元件都 create() 一次"

            before < 200 -> "首幀只花了 ${before.toInt()} ms：5 個元件要全部在首幀之前同步初始化"

            count.none {
                it.startsWith("初始化 AdsSdk（警告")
            } -> "Bug 還沒重現：依 manifestOrder 的順序，AdsSdk 排在 Analytics 前面，應該會有警告"

            else -> null
        }
    },
    checkFixed = { lines ->
        val frameAt = lines.indexOfFirst { it.startsWith("首幀畫出來：") }
        val first = a01Number(lines, "首幀畫出來：")
        val count = initLines(lines)
        when {
            frameAt < 0 || first == null -> {
                "演練沒有跑完"
            }

            lines.take(frameAt).filter { it.startsWith("初始化 ") } !=
                listOf("初始化 Logger", "初始化 CrashReporter") -> {
                "首幀前應該只初始化 Logger、CrashReporter（依賴 Logger 要先做）：目前是 ${lines.take(
                    frameAt,
                ).filter {
                    it.startsWith("初始化 ")
                }}"
            }

            first > 200 -> {
                "首幀花了 ${first.toInt()} ms，太久：首幀前只留 CrashReporter（含它的依賴）"
            }

            count.any { "警告" in it } -> {
                "有元件在依賴還沒初始化時就先初始化了：initializeComponent 要先把 dependencies 都做完再 create()"
            }

            count.size != 5 || count.toSet().size != 5 -> {
                "一共應該初始化 5 個元件、每個一次，目前是 ${count.size} 次：已經做過的元件不能再做"
            }

            count.indexOf("初始化 Analytics") > count.indexOf("初始化 AdsSdk") -> {
                "Analytics 要排在 AdsSdk 前面"
            }

            else -> {
                null
            }
        }
    },
)
