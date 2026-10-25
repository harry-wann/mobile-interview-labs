// Testing06CI 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   L01 CI 檢查結果 T06Check；L02 測試檔與耗時 T06TestFile；
//   L03 快取 T06Cache、雜湊 T06Hash.shortHash、套件鎖定檔 T06Lockfile；
//   L04 分階段發布的統計 T06Stats 與決定 T06Decision。
package tw.harry.labs.practice.testing06ci

import java.security.MessageDigest

// L01：CI 檢查結果

/** 一個 CI job 的結果。CANCELLED 是被取消（例如逾時），SKIPPED 是被略過（例如條件不符、或前一個 job 失敗） */
enum class T06JobResult { SUCCESS, FAILURE, CANCELLED, SKIPPED }

/** required：是不是合併 PR 必須通過的檢查（branch protection 的 required checks） */
class T06Check(val name: String, val result: T06JobResult, val required: Boolean = true)

// L02：測試檔與耗時

/** 一個測試檔，seconds 是上一次 CI 量到的執行時間 */
class T06TestFile(val name: String, val seconds: Int) {
    companion object {
        /** 12 個測試檔，總共 1160 秒；分 3 份同時跑，最理想每份約 387 秒 */
        val all: List<T06TestFile> = listOf(
            T06TestFile("OrderBookTests", 240),
            T06TestFile("ChartTests", 200),
            T06TestFile("AuthTests", 180),
            T06TestFile("WebSocketTests", 150),
            T06TestFile("OrderFlowTests", 120),
            T06TestFile("AssetTests", 90),
            T06TestFile("KYCTests", 60),
            T06TestFile("SettingsTests", 40),
            T06TestFile("FormatterTests", 30),
            T06TestFile("PrecisionTests", 20),
            T06TestFile("ParserTests", 20),
            T06TestFile("UtilTests", 10),
        )
    }
}

// L03：快取

object T06Hash {
    /** 內容的 SHA-256，取前 12 個十六進位字元。內容只要差一個字，結果就不同 */
    fun shortHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
}

/** CI 的快取：用 key 存、用 key 取 */
class T06Cache {
    private val store = mutableMapOf<String, String>()

    fun restore(key: String): String? = store[key]
    fun save(key: String, artifact: String) {
        store[key] = artifact
    }
}

object T06Lockfile {
    /** 套件鎖定檔（相當於 gradle.lockfile 或 Package.resolved）的內容，這裡簡化成「套件@版本」 */
    fun content(version: String) = "okhttp@$version"

    /** 從鎖定檔讀出版本 */
    fun version(lockfile: String) = lockfile.substringAfterLast('@')
}

// L04：分階段發布

class T06Stats(val sessions: Int, val crashFreeSessions: Int) {
    /** crash-free session 比例（百分比） */
    val crashFreeRate: Double get() = crashFreeSessions.toDouble() / sessions * 100
}

enum class T06Decision(val title: String) {
    EXPAND("擴大發布"),
    HOLD("再等一等"),
    HALT("暫停發布"),
}
