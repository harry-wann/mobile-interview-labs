// L02：5 個小頭像，記憶體多了一百多 MB（圖片解碼後的記憶體 = 寬 × 高 × 4，
// 要在解碼時就縮小）
// 對應複習站：Advanced03Memory 第 2
// 題「一張圖片實際上占多少記憶體？怎麼減少？」
//
// 情境：用戶上傳的照片是 3000 × 2000 的 JPEG，檔案只有幾十 KB（演練用單色照片，
// 檔案特別小）。列表上只顯示 56 × 56 點（3x 螢幕是 168 像素）的頭像，
// 畫面上同時有 5 個。
//
// 你要寫：
//   ① Bug 版 decode：UIImage(data:) 之後呼叫 preparingForDisplay()
// （畫面第一次顯示時系統做的事）：整張 3000 × 2000 解碼進記憶體。
//   ② 修正版 decode：用 ImageIO：CGImageSourceCreateWithData 建立來源，再
// CGImageSourceCreateThumbnailAtIndex 解碼成縮圖，
//      選項 kCGImageSourceThumbnailMaxPixelSize 設 maxPointSize ×
// screenScale（168），kCGImageSourceCreateThumbnailFromImageAlways 與
//      kCGImageSourceShouldCacheImmediately 都設 true；最後包成
// UIImage(cgImage:)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced03-Memory/L02_ImageDownsampling.swift
import ImageIO
import UIKit
import LabKit

private let avatarCount = 5
private let maxPointSize: CGFloat = 56
private let screenScale: CGFloat = 3

/// 一張 3000 × 2000 的 JPEG（兩個版本共用）。單色的圖壓縮得很小，
/// 剛好示範「檔案小、解碼後很大」
@MainActor
private func makePhotoJPEG() -> Data {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let image = UIGraphicsImageRenderer(
        size: CGSize(width: 3000, height: 2000), format: format
    ).image { context in
        UIColor.systemTeal.setFill()
        context.fill(CGRect(x: 0, y: 0, width: 3000, height: 2000))
    }
    return image.jpegData(compressionQuality: 0.8) ?? Data()
}

/// 解碼之後的像素真的占的位元組數（每列的位元組數 × 列數）（兩個版本共用）
private func decodedBytes(_ image: UIImage) -> Int {
    guard let cg = image.cgImage else { return 0 }
    return cg.bytesPerRow * cg.height
}

/// 演練流程（兩個版本共用）：解碼 5 張頭像，全部留著（像列表上同時顯示 5 個）
@MainActor
private func scenario(_ log: LabLog, decode: (Data) -> UIImage?) {
    let jpeg = makePhotoJPEG()
    log("照片檔案：\(jpeg.count / 1024) KB")
    var avatars: [UIImage] = []
    for _ in 0..<avatarCount {
        if let image = decode(jpeg) { avatars.append(image) }
    }
    let total = avatars.reduce(0) { $0 + decodedBytes($1) }
    let size =
        avatars.first.flatMap { $0.cgImage }.map {
            "\($0.width) × \($0.height)"
        } ?? "（沒有圖片）"
    log("解碼後每張 \(size) 像素")
    log(
        "\(avatarCount) 張頭像，解碼後的記憶體一共 \(total / 1_048_576) MB（\(total / 1024) KB）"
    )
}

// MARK: - Bug 版
private enum L02Bug {
    @MainActor
    static func decode(_ data: Data) -> UIImage? {
        // TODO ①：UIImage(data: data)?.preparingForDisplay()：整張原圖解碼，寬
        // × 高 × 4 位元組，跟顯示多大無關
        nil
    }

    @MainActor
    static func run(_ log: LabLog) async {
        scenario(log, decode: decode)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    @MainActor
    static func decode(_ data: Data) -> UIImage? {
        // TODO ②-1：CGImageSourceCreateWithData(data as CFData,
        // [kCGImageSourceShouldCache: false] as CFDictionary)：只建立來源，
        // 不解碼
        // TODO ②-2：選項字典 [CFString: Any]：
        // kCGImageSourceCreateThumbnailFromImageAlways: true、
        //          kCGImageSourceThumbnailMaxPixelSize: maxPointSize *
        // screenScale、kCGImageSourceCreateThumbnailWithTransform: true、
        //          kCGImageSourceShouldCacheImmediately: true
        // TODO ②-3：CGImageSourceCreateThumbnailAtIndex(source, 0, options as
        // CFDictionary)，成功就回傳 UIImage(cgImage:)
        nil
    }

    @MainActor
    static func run(_ log: LabLog) async {
        scenario(log, decode: decode)
    }
}

// MARK: - 演練設定
extension Advanced03Memory {
    static let l02 = Lab(
        id: "L02",
        title: "5 個小頭像，記憶體多了一百多 MB",
        question: "第 2 題：一張圖片實際上占多少記憶體？怎麼減少？",
        goal:
            "圖片解碼後的記憶體是寬 × 高 × 4 位元組，和檔案大小、顯示大小都無關。解碼時就縮成顯示需要的大小（downsampling）。先寫出整張解碼的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced03-Memory/L02_ImageDownsampling.swift，寫 TODO ①。",
            "選「練習」「Bug 版」按「執行」，看三行 log：照片檔案只有幾十 KB，解碼後卻是 3000 × 2000 像素，5 張一共約 114 MB。",
            "寫 TODO ②，選「修正版」按「執行」：解碼後每張 168 × 112 像素，5 張一共約 370 KB。",
            "進階：Xcode 左邊切到 Debug navigator 的 Memory，執行 Bug 版，會看到記憶體曲線往上跳 120 MB；或用 Instruments 的 Allocations 看 VM: ImageIO_JPEG_Data 之類的配置。",
            "想一想：Kingfisher 的 `DownsamplingImageProcessor` 包裝的就是這件事；為什麼縮圖的 maxPixelSize 要用「point 數 × 螢幕倍率」？（見這個單元的第 2 題）",
        ],
        expectBug:
            "照片檔案只有 24 KB 左右，解碼後每張 3000 × 2000 像素，5 張一共 114 MB（約 117,000 KB）。",
        expectFixed: "解碼後每張 168 × 112 像素，5 張一共不到 1 MB（約 370 KB）。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let total = a03Number(in: lines, prefix: "5 張頭像，解碼後的記憶體一共 ")
            else { return "演練沒有跑完" }
            if total == 0 {
                return "沒有解碼出圖片：UIImage(data:) 之後要 preparingForDisplay()"
            }
            return total >= 100
                ? nil
                : "5 張只占 \(Int(total)) MB：Bug 版要把整張原圖解碼（UIImage(data:)?.preparingForDisplay()）"
        },
        checkFixed: { lines in
            guard let total = a03Number(in: lines, prefix: "5 張頭像，解碼後的記憶體一共 ")
            else { return "演練沒有跑完" }
            let size = lines.first { $0.hasPrefix("解碼後每張 ") } ?? ""
            if size.contains("沒有圖片") {
                return
                    "沒有解碼出圖片：CGImageSourceCreateThumbnailAtIndex 要回傳縮圖，再包成 UIImage(cgImage:)"
            }
            if total >= 5 {
                return
                    "5 張還是占了 \(Int(total)) MB：要設 kCGImageSourceThumbnailMaxPixelSize（56 × 3 = 168），解碼時就縮小。\(size)"
            }
            return nil
        }
    )
}
