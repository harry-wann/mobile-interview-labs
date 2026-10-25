// L08：App 更新之後，使用者本機的訂單全部消失了（fallbackToDestructiveMigration）
// 對應複習站：Platform04SecureStorage 第 7 題「資料庫結構改版時，migration 要怎麼做？」
//
// 情境：1.0 版（資料庫 version 1）的 orders 資料表沒有 memo 欄位，使用者手機裡已經存了 2 張訂單。1.1 版（version 2）新增了 memo 欄位。
//        使用者更新 App 之後，手機裡還是 version 1 的資料庫檔案。
//        這一區的 JVM 單元測試沒有 Android 的 SQLite，也沒有 Room 編譯器，所以演練用一個幾十行的「迷你資料庫」代替 Room：
//        MiniDbBuilder 的 addMigrations、fallbackToDestructiveMigration、build 和 Room 的 RoomDatabase.Builder 同名、規則一樣：
//        找不到 migration 又沒有開 fallback 時，build() 會丟出和 Room 一樣的錯誤；開了 fallback 就清空整個資料庫。
// Bug 版：看到「找不到 migration」的錯誤，就加上 fallbackToDestructiveMigration() 讓 App 不要當掉。結果每次改版，使用者的本機資料都被清空。
// 修正版：寫一個明確的 Migration(1, 2)：新增 memo 欄位（Room 裡寫 ALTER TABLE orders ADD COLUMN memo TEXT NOT NULL DEFAULT ''），用 addMigrations 加進去。
package tw.harry.labs.solution.platform04securestorage

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 迷你資料庫的「檔案」（兩個版本共用）：記著版本號與資料列，關掉 App 之後還在 */
private class L08DbFile(var version: Int, val rows: MutableList<MutableMap<String, String>> = mutableListOf())

/** 一個 migration：從 from 版升到 to 版要做的事（對應 Room 的 Migration） */
private class L08Migration(val from: Int, val to: Int, val migrate: (L08DbFile) -> Unit)

/** 迷你資料庫（對應 Room 的 RoomDatabase.Builder，規則和 Room 一樣） */
private class L08MiniDbBuilder(private val file: L08DbFile, private val version: Int) {
    private val migrations = mutableListOf<L08Migration>()
    private var destructive = false

    fun addMigrations(vararg migration: L08Migration) = apply { migrations += migration }
    fun fallbackToDestructiveMigration() = apply { destructive = true }

    /** 開啟資料庫：版本相同直接用；不同就找 migration 一步一步升上去 */
    fun build(): L08DbFile {
        var current = file.version
        if (current == version) return file
        val path = mutableListOf<L08Migration>()
        while (current < version) {
            val next = migrations.firstOrNull { it.from == current } ?: break
            path += next
            current = next.to
        }
        if (current == version) {
            path.forEach { it.migrate(file) }
            file.version = version
        } else if (destructive) {
            file.rows.clear()                                   // 清空整個資料庫再重建
            file.version = version
        } else {
            throw IllegalStateException(
                "A migration from ${file.version} to $version was required but not found. " +
                    "Please provide the necessary Migration path via RoomDatabase.Builder.addMigration(Migration ...) " +
                    "or allow for destructive migrations via one of the RoomDatabase.Builder.fallbackToDestructiveMigration* methods.",
            )
        }
        return file
    }
}

/** 演練流程（兩個版本共用）：version 1 的資料庫裡有 2 張訂單，App 更新後用 version 2 開啟 */
private fun scenario(log: LabLog, open: (L08DbFile) -> L08DbFile) {
    val file = L08DbFile(version = 1)
    file.rows += mutableMapOf("id" to "1", "symbol" to "btcjpy")
    file.rows += mutableMapOf("id" to "2", "symbol" to "ethjpy")
    log("更新前（version 1）：本機有 ${file.rows.size} 張訂單")
    try {
        val db = open(file)
        log("更新後（version ${db.version}）：本機剩 ${db.rows.size} 張訂單")
        val memo = when {
            db.rows.isEmpty() -> "（沒有資料）"
            db.rows.all { it.containsKey("memo") } -> "是"
            else -> "否（舊資料沒有補上預設值）"
        }
        log("訂單有 memo 欄位：$memo")
    } catch (e: IllegalStateException) {
        log("更新後打不開資料庫：${e.message?.take(60)}…")
    }
}

// MARK: - Bug 版
private object L08Bug {
    fun open(file: L08DbFile): L08DbFile =
        L08MiniDbBuilder(file, version = 2)
            .fallbackToDestructiveMigration()                   // 看到「找不到 migration」就加這行：舊資料全部清空
            .build()

    fun run(log: LabLog) = scenario(log, ::open)
}

// MARK: - 修正版
private object L08Fixed {
    /** 1 → 2：orders 新增 memo 欄位，舊資料補上預設值 ''（Room：ALTER TABLE orders ADD COLUMN memo TEXT NOT NULL DEFAULT ''） */
    val MIGRATION_1_2 = L08Migration(1, 2) { db ->
        db.rows.forEach { it["memo"] = "" }
    }

    fun open(file: L08DbFile): L08DbFile =
        L08MiniDbBuilder(file, version = 2)
            .addMigrations(MIGRATION_1_2)                       // 不要加 fallbackToDestructiveMigration()
            .build()

    fun run(log: LabLog) = scenario(log, ::open)
}

// MARK: - 演練設定
private fun remaining(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("更新後（version ") && it.contains("本機剩 ") }?.substringAfter("本機剩 ")?.removeSuffix(" 張訂單")?.toIntOrNull()

val l08 = Lab(
    id = "L08",
    title = "App 更新後，本機訂單全部消失",
    question = "第 7 題：資料庫結構改版時，migration 要怎麼做？",
    goal = "資料庫結構改版時要有明確、測試過的 migration 路徑。不要為了讓 App 不當掉就加 fallbackToDestructiveMigration()，它會清空使用者的資料。",
    steps = listOf(
        "先看一次 Room 的錯誤：把 Bug 版的 fallbackToDestructiveMigration() 那一行拿掉再執行，看到「A migration from 1 to 2 was required but not found」。",
        "選「Bug 版」按執行（加回 fallbackToDestructiveMigration()），看「更新後本機剩幾張訂單」。",
        "選「修正版」執行：用 Migration(1, 2) 補上 memo 欄位，2 張訂單都還在，而且有 memo。",
        "想一想：什麼時候可以用 fallbackToDestructiveMigration()？（只有可以從伺服器重新抓回來的快取；有使用者設定、草稿、尚未同步的資料，就是資料遺失）",
    ),
    expectBug = "更新前（version 1）本機有 2 張訂單；更新後（version 2）本機剩 0 張訂單。",
    expectFixed = "更新前本機有 2 張訂單；更新後本機剩 2 張訂單，而且訂單都有 memo 欄位。",
    file = "platform04-secure-storage/L08_Migration.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = remaining(lines)
        when {
            lines.any { it.startsWith("更新後打不開資料庫") } -> "資料庫打不開：加上 .fallbackToDestructiveMigration()（Bug 版就是用它讓 App 不要當掉）"
            n == null -> "演練沒有跑完"
            !lines.contains("更新後（version 2）：本機剩 $n 張訂單") -> "資料庫要升到 version 2：用 L08MiniDbBuilder(file, version = 2) 開啟，再 build()"
            n != 0 -> "Bug 還沒重現（剩 $n 張）：Bug 版要用 fallbackToDestructiveMigration() 開啟，舊資料會被清空"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = remaining(lines)
        when {
            lines.any { it.startsWith("更新後打不開資料庫") } -> "資料庫打不開：要用 addMigrations(MIGRATION_1_2) 加上 1 到 2 的 migration"
            n == null -> "演練沒有跑完"
            !lines.contains("更新後（version 2）：本機剩 $n 張訂單") -> "資料庫要升到 version 2：用 L08MiniDbBuilder(file, version = 2) 開啟，再 build()"
            n != 2 -> "更新後只剩 $n 張訂單：不要用 fallbackToDestructiveMigration()，改用 addMigrations(MIGRATION_1_2)"
            !lines.contains("訂單有 memo 欄位：是") -> "舊的訂單要補上 memo 欄位：MIGRATION_1_2 要對每一列加上 it[\"memo\"] = \"\""
            else -> null
        }
    },
)
