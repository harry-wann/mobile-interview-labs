// L02：縮圖列表吃掉幾百 MB 記憶體（檔案大小不等於記憶體大小）
// 對應複習站：Advanced07AppSize 第 2 題「iOS 要怎麼縮小？」（圖片格式與品質；
// 縮小的是檔案，不是記憶體）
//
// 情境：KYC（身分驗證）頁的列表，用 100 pt 寬的縮圖顯示 20 張證件照。
// 每張證件照是 4000 × 3000 的 JPEG（約 12 百萬像素）。
//       有人想「把圖壓小一點」，把 JPEG 品質從 0.8 降到 0.3：檔案確實小了，
// 但這真的是在省使用者的資源嗎？
//
// 你要寫：
//   ① Bug 版：用 CGImageSourceCreateImageAtIndex 把整張 4000 × 3000
// 的圖完整解碼。解碼後是「寬 × 高 × 4 位元組」，20 張將近 1 GB。
//   ② 修正版：用 CGImageSourceCreateThumbnailAtIndex 直接解碼成 300
// 像素的縮圖，完整大小的點陣圖不會出現。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced07-AppSize/L02_DecodedMemory.swift
import ImageIO
import UIKit
import LabKit

/// 固定種子的亂數，每次畫出同一張圖（兩個版本共用）
private struct SeededRandom: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// 畫一張 4000 × 3000 的測試照片，編成 JPEG（兩個版本共用）
private func makePhoto(quality: CGFloat) -> Data {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let renderer = UIGraphicsImageRenderer(
        size: CGSize(width: 4000, height: 3000), format: format)
    var rng = SeededRandom(state: 7)
    return renderer.jpegData(withCompressionQuality: quality) { context in
        for _ in 0..<2_000 {
            UIColor(
                red: .random(in: 0...1, using: &rng),
                green: .random(in: 0...1, using: &rng),
                blue: .random(in: 0...1, using: &rng), alpha: 1
            ).setFill()
            context.fill(
                CGRect(
                    x: .random(in: 0...3_900, using: &rng),
                    y: .random(in: 0...2_900, using: &rng),
                    width: .random(in: 20...600, using: &rng),
                    height: .random(in: 20...600, using: &rng)))
        }
    }
}

/// 解碼後的點陣圖佔多少位元組（兩個版本共用）
private func bytes(of image: CGImage) -> Int {
    image.bytesPerRow * image.height
}

private func megabytes(_ bytes: Int) -> String {
    String(format: "%.1f MB", Double(bytes) / 1_048_576)
}

/// 演練流程（兩個版本共用）：decode(照片檔) 回傳「列表的縮圖」解碼後的點陣圖
private func scenario(_ log: LabLog, decode: (Data) -> CGImage?) {
    let photo = makePhoto(quality: 0.8)
    let small = makePhoto(quality: 0.3)
    // 把品質降低：檔案變小了，那解碼後的記憶體呢？（用完整解碼量一次）
    let fullOf = { (data: Data) -> Int in
        let source = CGImageSourceCreateWithData(data as CFData, nil)!
        return bytes(
            of: CGImageSourceCreateImageAtIndex(
                source, 0,
                [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)!)
    }
    log(
        "JPEG 品質 0.8 → 0.3：檔案 \(photo.count / 1024) KB → \(small.count / 1024) KB，完整解碼後 \(megabytes(fullOf(photo))) → \(megabytes(fullOf(small)))"
    )

    guard let image = decode(photo) else {
        log("沒有解碼出縮圖")
        return
    }
    log(
        "每張縮圖解碼後：\(image.width) × \(image.height)，\(megabytes(bytes(of: image)))"
    )
    log("列表 20 張縮圖共佔記憶體：\(megabytes(bytes(of: image) * 20))")
}

// MARK: - Bug 版
private enum L02Bug {
    static func decode(_ data: Data) -> CGImage? {
        // TODO ①：用 CGImageSourceCreateWithData 建立 source，再用
        // CGImageSourceCreateImageAtIndex(source, 0, …)
        //         把整張圖完整解碼（options 放
        // kCGImageSourceShouldCacheImmediately: true，讓它馬上解碼）
        return nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, decode: decode)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func decode(_ data: Data) -> CGImage? {
        // TODO ②：建立 source 之後，改用
        // CGImageSourceCreateThumbnailAtIndex(source, 0, options)
        // 直接解碼成縮圖。
        //         options 要放：kCGImageSourceCreateThumbnailFromImageAlways:
        // true、kCGImageSourceCreateThumbnailWithTransform: true、
        //         kCGImageSourceShouldCacheImmediately: true，以及
        // kCGImageSourceThumbnailMaxPixelSize: 300（100 pt × 3 倍）
        return nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, decode: decode)
    }
}

// MARK: - 演練設定
extension Advanced07AppSize {
    static let l02 = Lab(
        id: "L02",
        title: "縮圖列表吃掉幾百 MB 記憶體",
        question: "第 2 題：iOS 要怎麼縮小？（圖片格式與品質）",
        goal:
            "壓縮圖片只縮小檔案，不會縮小解碼後的記憶體。要省記憶體，要在解碼的時候就縮成顯示需要的大小。 先寫出整張解碼的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced07-AppSize/L02_DecodedMemory.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看第一行：品質降低之後，檔案與完整解碼後的記憶體各是多少？",
            "看最後一行：20 張縮圖共佔多少記憶體？想一想這個數字是怎麼算出來的（寬 × 高 × 4）。",
            "寫 TODO ②，選「修正版」執行，比較每張縮圖解碼後的尺寸與 20 張的總記憶體。",
            "進階：用 Xcode 的 Debug Memory Graph 或 Instruments 的 Allocations，搜尋 CG image 或 Image IO，可以看到真正的 App 解碼出來的點陣圖。",
        ],
        expectBug:
            "品質降到 0.3，檔案從約 629 KB 變成約 347 KB，但完整解碼的記憶體「不變」（都是 45.8 MB）；每張縮圖解碼後 4000 × 3000，列表 20 張共佔 915.5 MB。",
        expectFixed: "每張縮圖解碼後 300 × 225（0.3 MB），列表 20 張共佔 5.1 MB。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let total = megabytesValue(lines, prefix: "列表 20 張縮圖共佔記憶體：")
            else {
                return
                    "演練沒有解碼出縮圖：decode 要用 CGImageSourceCreateImageAtIndex 解碼整張圖"
            }
            return total >= 500
                ? nil
                : "Bug 還沒重現：要把整張 4000 × 3000 的圖完整解碼（CGImageSourceCreateImageAtIndex），20 張應該超過 500 MB"
        },
        checkFixed: { lines in
            guard let total = megabytesValue(lines, prefix: "列表 20 張縮圖共佔記憶體：")
            else {
                return "沒有解碼出縮圖：decode 要用 CGImageSourceCreateThumbnailAtIndex"
            }
            return total <= 20
                ? nil
                : "記憶體還是太大（\(total) MB）：要用 CGImageSourceCreateThumbnailAtIndex，kCGImageSourceThumbnailMaxPixelSize 設 300"
        }
    )
}

private func megabytesValue(_ lines: [String], prefix: String) -> Double? {
    guard let line = lines.first(where: { $0.hasPrefix(prefix) }) else {
        return nil
    }
    return Double(
        line.dropFirst(prefix.count).replacingOccurrences(of: " MB", with: ""))
}
