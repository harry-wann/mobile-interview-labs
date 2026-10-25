// L02：列表上的 20 個小頭像，一個一個解碼，解到第 6 個就
// OutOfMemoryError（圖片解碼後的記憶體 = 寬 × 高 × 4）
// 對應複習站：Advanced03Memory 第 2
// 題「一張圖片實際上占多少記憶體？怎麼減少？」
//
// 情境：用戶上傳的照片是 4000 × 3000 的 JPEG（檔案只有 2 MB）。列表上只顯示
// 100 × 100 像素的頭像，一次有 20 個。
//        解碼後的記憶體 = 寬 × 高 × 4 位元組（ARGB_8888），4000 × 3000 就是 48,
// 000,000 位元組（約 46 MiB），跟檔案大小、顯示大小都無關。
//        演練用一個 256 MB 的「假的 heap」代表 App 的記憶體上限（JVM
// 單元測試沒有 BitmapFactory，所以用公式算，數字和真的 Bitmap 一樣）。
// Bug 版：不縮小，整張解碼（inSampleSize = 1）：解到第 6 個，累計超過 256 MB，
// OutOfMemoryError。
// 修正版：先算出 inSampleSize（2 的次方，縮小後的寬高仍然大於等於要求的 100 ×
// 100），解碼時就縮小：
//        inSampleSize = 16，每張 250 × 187，約 187 KB，20 張一共不到 4 MB。
package tw.harry.labs.solution.advanced03memory

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val PHOTO_WIDTH = 4000
private const val PHOTO_HEIGHT = 3000
private const val REQUEST_SIZE = 100
private const val AVATAR_COUNT = 20
private const val HEAP_LIMIT_BYTES = 256L * 1024 * 1024

/**
 * 解碼後的位元組數：（寬 ÷ inSampleSize）×（高 ÷ inSampleSize）× 每個像素 4
 * 位元組（兩個版本共用）
 */
private fun decodedBytes(width: Int, height: Int, sampleSize: Int): Long =
    (width / sampleSize).toLong() * (height / sampleSize) * 4

/**
 * 假的 heap：配置超過上限就丟 OutOfMemoryError，和真的 App 一樣（兩個版本共用）
 */
private class FakeHeap {
    var used = 0L
        private set

    fun allocate(bytes: Long) {
        if (used + bytes >
            HEAP_LIMIT_BYTES
        ) {
            throw OutOfMemoryError("Failed to allocate $bytes bytes")
        }
        used += bytes
    }
}

/**
 * 演練流程（兩個版本共用）：[sampleSizeFor] 決定每張頭像用多大的 inSampleSize
 * 解碼
 */
private fun scenario(
    log: LabLog,
    sampleSizeFor: (
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ) -> Int,
) {
    val heap = FakeHeap()
    val sampleSize =
        sampleSizeFor(PHOTO_WIDTH, PHOTO_HEIGHT, REQUEST_SIZE, REQUEST_SIZE)
    if (sampleSize < 1) {
        // 還沒寫完時，避免除以 0
        log("inSampleSize = $sampleSize：還沒有算出縮小的倍數")
        return
    }
    val bytes = decodedBytes(PHOTO_WIDTH, PHOTO_HEIGHT, sampleSize)
    log(
        "inSampleSize = $sampleSize，每張解碼後 ${PHOTO_WIDTH / sampleSize} × ${PHOTO_HEIGHT / sampleSize} 像素，${bytes / 1024} KB",
    )
    var decoded = 0
    try {
        repeat(AVATAR_COUNT) {
            // 列表上同時顯示的頭像，解碼後的 Bitmap 都留在記憶體裡
            heap.allocate(bytes)
            decoded++
        }
        log("$AVATAR_COUNT 張頭像都解碼成功，共 ${heap.used / 1_048_576} MB")
    } catch (e: OutOfMemoryError) {
        log(
            "解碼第 ${decoded + 1} 張時 OutOfMemoryError：已經用了 ${heap.used / 1_048_576} MB",
        )
    }
}

// MARK: - Bug 版
private object L02Bug {
    // 不縮小：整張解碼
    fun run(log: LabLog) = scenario(log) { _, _, _, _ -> 1 }
}

// MARK: - 修正版
private object L02Fixed {
    /** 算出 inSampleSize：2 的次方，縮小之後寬高仍然大於等於要求的大小 */
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var sample = 1
        while (width / (sample * 2) >= reqWidth &&
            height / (sample * 2) >= reqHeight
        ) {
            sample *= 2
        }
        return sample
    }

    fun run(log: LabLog) = scenario(log) { w, h, reqW, reqH ->
        calculateInSampleSize(w, h, reqW, reqH)
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "20 個小頭像，解碼到第 6 個就 OutOfMemoryError",
    question = "第 2 題：一張圖片實際上占多少記憶體？怎麼減少？",
    goal = "圖片解碼後的記憶體是寬 × 高 × 4 位元組，和檔案大小、顯示大小都無關。解碼時就縮成顯示需要的大小（inSampleSize）。",
    steps = listOf(
        "選「Bug 版」執行，看 log：每張解碼後約 46 MiB（檔案只有 2 MB），解到第 6 張就超過 256 MB。",
        "選「修正版」執行：inSampleSize = 16，每張 250 × 187 像素約 187 KB，20 張都成功。",
        "進階：Android Studio 的 Profiler 選 Memory，在真的 App 載入大圖，看 Bitmap 配置；Coil、Glide 這類圖片載入函式庫會依 ImageView 的大小自動縮小解碼，通常不需要自己寫；API 28 起也可以用 ImageDecoder 的 setTargetSize。",
        "想一想：為什麼 inSampleSize 一定是 2 的次方？（BitmapFactory 會往下取成 2 的次方，見這個單元的第 2 題）",
    ),
    expectBug = "inSampleSize = 1，每張解碼後 4000 × 3000 像素，46875 KB；解碼第 6 張時 OutOfMemoryError：已經用了 228 MB。",
    expectFixed = "inSampleSize = 16，每張解碼後 250 × 187 像素，182 KB；20 張頭像都解碼成功，共 3 MB。",
    file = "advanced03-memory/L02_ImageDownsampling.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("inSampleSize = ") } -> "演練沒有跑完"

            lines.none {
                it.startsWith("inSampleSize = 1，")
            } -> "Bug 版不縮小：inSampleSize 要是 1（整張解碼）"

            lines.none {
                it.contains("OutOfMemoryError")
            } -> "Bug 還沒重現：整張解碼 20 張應該超過 256 MB"

            else -> null
        }
    },
    checkFixed = { lines ->
        val sample = a03Number(lines, "inSampleSize = ")?.toInt()
        when {
            sample == null -> "演練沒有跑完"

            sample == 1 -> "inSampleSize 還是 1：calculateInSampleSize 要算出縮小的倍數"

            sample != 16 -> "4000 × 3000 要縮成 100 × 100 以上，inSampleSize 應該是 16（再大，高度 3000 ÷ 32 = 93 就小於 100），你算出 $sample"

            lines.any {
                it.contains("OutOfMemoryError")
            } -> "還是 OutOfMemoryError"

            lines.none { it.contains("張頭像都解碼成功") } -> "演練沒有跑完"

            else -> null
        }
    },
)
