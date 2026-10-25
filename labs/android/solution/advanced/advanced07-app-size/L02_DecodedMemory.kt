// L02：縮圖列表吃掉幾百 MB 記憶體（檔案大小不等於記憶體大小）
// 對應複習站：Advanced07AppSize 第 3 題「Android 要怎麼縮小？」（圖片格式與品質；縮小的是檔案，不是記憶體）
//
// 情境：KYC（身分驗證）頁的列表，用 100 dp 寬的縮圖顯示 20 張證件照（螢幕密度 3，也就是 300 像素）。
//       每張證件照是 4000 × 3000 的 JPEG（約 12 百萬像素）。有人想「把圖壓小一點」，把 JPEG 品質從 80 降到 30：
//       檔案確實小了，但這真的是在省使用者的資源嗎？
//       （JVM 單元測試沒有 BitmapFactory，演練用公式算出解碼後的記憶體：ARGB_8888 一個像素 4 位元組；
//        真正的 App 用 BitmapFactory.Options 的 inJustDecodeBounds 先讀尺寸，再設 inSampleSize 解碼。）
// Bug 版：inSampleSize 一律是 1，整張圖完整解碼。點陣圖大小是「寬 × 高 × 4 位元組」，和檔案壓得多小無關：
//        每張約 46 MB，20 張就是將近 1 GB（一般 App 的記憶體上限只有幾百 MB，會 OutOfMemoryError）。
// 修正版：用 Android 官方文件的算法算出 inSampleSize（2 的次方，縮到仍然不小於顯示大小為止），解碼出來的圖小得多。
package tw.harry.labs.solution.advanced07appsize

import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val PHOTO_WIDTH = 4000
private const val PHOTO_HEIGHT = 3000
private const val THUMB_PX = 300                                 // 100 dp × 3 倍

/** ARGB_8888 解碼後的點陣圖大小（位元組） */
private fun decodedBytes(width: Int, height: Int): Long = width.toLong() * height * 4

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

/** 演練流程（兩個版本共用）：calculateInSampleSize(照片寬, 照片高, 要的寬, 要的高) 回傳 inSampleSize */
private fun scenario(log: LabLog, calculateInSampleSize: (Int, Int, Int, Int) -> Int) {
    // 把品質降低：檔案變小了，那解碼後的記憶體呢？（檔案大小是假設的數字，記憶體用公式算）
    log("JPEG 品質 80 → 30：檔案 3200 KB → 1100 KB，完整解碼後 ${megabytes(decodedBytes(PHOTO_WIDTH, PHOTO_HEIGHT))} → ${megabytes(decodedBytes(PHOTO_WIDTH, PHOTO_HEIGHT))}")

    val sample = calculateInSampleSize(PHOTO_WIDTH, PHOTO_HEIGHT, THUMB_PX, THUMB_PX)
    if (sample < 1) {
        log("inSampleSize 是 $sample：還沒寫（至少要是 1）")
        return
    }
    val width = PHOTO_WIDTH / sample
    val height = PHOTO_HEIGHT / sample
    log("inSampleSize = $sample")
    log("每張縮圖解碼後：$width × $height，${megabytes(decodedBytes(width, height))}")
    log("列表 20 張縮圖共佔記憶體：${megabytes(decodedBytes(width, height) * 20)}")
}

// MARK: - Bug 版
private object L02Bug {
    fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int =
        1                                                       // 不縮：整張圖完整解碼，再交給畫面縮小

    suspend fun run(log: LabLog) = scenario(log, ::calculateInSampleSize)
}

// MARK: - 修正版
private object L02Fixed {
    /** Android 官方文件的算法：inSampleSize 取 2 的次方，縮到「寬高仍然不小於要的大小」為止 */
    fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    suspend fun run(log: LabLog) = scenario(log, ::calculateInSampleSize)
}

// MARK: - 演練設定
private fun megabytesValue(lines: List<String>, prefix: String): Double? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.removeSuffix(" MB")?.toDoubleOrNull()

val l02 = Lab(
    id = "L02",
    title = "縮圖列表吃掉幾百 MB 記憶體",
    question = "第 3 題：Android 要怎麼縮小？（圖片格式與品質）",
    goal = "壓縮圖片只縮小檔案，不會縮小解碼後的記憶體。要省記憶體，要在解碼的時候就縮成顯示需要的大小（inSampleSize）。",
    steps = listOf(
        "選「Bug 版」執行，看第一行：品質降低之後，檔案與完整解碼後的記憶體各是多少？",
        "看最後一行：20 張縮圖共佔多少記憶體？想一想這個數字是怎麼算出來的（寬 × 高 × 4）。",
        "選「修正版」執行，比較 inSampleSize、每張縮圖解碼後的尺寸與 20 張的總記憶體。",
        "進階：用 Android Studio 的 Memory Profiler 抓 heap dump，看 Bitmap 物件各佔多少；真正的 App 也可以直接用 Coil、Glide 這類圖片載入函式庫，它們會依 ImageView 的大小自動縮放。",
    ),
    expectBug = "品質降到 30，檔案從 3200 KB 變成 1100 KB，但完整解碼的記憶體「不變」（都是 45.8 MB）；inSampleSize = 1，列表 20 張共佔 915.5 MB。",
    expectFixed = "inSampleSize = 8，每張縮圖解碼後 500 × 375（0.7 MB），列表 20 張共佔 14.3 MB。",
    file = "advanced07-app-size/L02_DecodedMemory.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val total = megabytesValue(lines, "列表 20 張縮圖共佔記憶體：")
        when {
            lines.any { it.startsWith("inSampleSize 是") } -> "calculateInSampleSize 還沒寫：Bug 版要回傳 1（不縮）"
            total == null -> "演練沒有跑完：要用 scenario 執行"
            total < 500 -> "Bug 還沒重現：inSampleSize 要是 1（整張圖完整解碼），20 張應該超過 500 MB"
            else -> null
        }
    },
    checkFixed = { lines ->
        val total = megabytesValue(lines, "列表 20 張縮圖共佔記憶體：")
        when {
            lines.any { it.startsWith("inSampleSize 是") } -> "calculateInSampleSize 還沒寫：要算出 2 的次方的 inSampleSize"
            total == null -> "演練沒有跑完：要用 scenario 執行"
            total > 20 -> "記憶體還是太大（$total MB）：要算出 inSampleSize（2 的次方，縮到寬高仍不小於 300 像素為止）"
            else -> null
        }
    },
)
