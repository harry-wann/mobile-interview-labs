// L01：把圖片搬進 asset catalog，.ipa 一樣大，使用者的下載卻少了（上傳大小、
// 下載大小不是同一個數字）
// 對應複習站：Advanced07AppSize 第 1 題「App 體積有哪幾種？為什麼說『變小了』
// 一定要先講是哪一種？」（asset catalog 的 slicing 見第 2 題）
//
// 情境：App 裡有一組促銷圖 promo（@2x 5 MB、@3x 11 MB），原本以散落的 png
// 檔放在 bundle 裡；工程師把它搬進 asset catalog。
//       App Store 的 App Thinning（slicing）只會對 asset catalog
// 裡的圖片「依裝置螢幕只送需要的那一份」，散落的檔案則完整送給每位使用者。
//       演練用未壓縮的大小（單位 MB）模擬 App Thinning Size Report，
// 比較搬移前後：.ipa 的大小，以及 iPhone 15（@3x）與 iPhone SE（@2x）
// 的下載大小。
// Bug 版：下載大小就是 ipa 裡所有檔案加起來。搬移前後 ipa 沒變，
// 結論是「沒有效果」，但使用者的下載其實少了。
// 修正版：依裝置計算：asset catalog 的圖片只算這台裝置的螢幕倍率（@2x 或 @3x）
// 那一份；執行檔、framework、散落的檔案都要算。
import Foundation
import LabKit

private struct BundleFile {
    let name: String
    let mb: Int
    // 圖片的螢幕倍率（@2x、@3x）；執行檔、framework 沒有
    var scale: Int? = nil
    // 是否放在 asset catalog 裡
    var inCatalog = false
}

private struct Device {
    let name: String
    let scale: Int
}

private let devices = [
    Device(name: "iPhone 15", scale: 3), Device(name: "iPhone SE", scale: 2),
]

/// App 的內容。promoInCatalog 為 true 時，促銷圖在 asset catalog 裡
private func files(promoInCatalog: Bool) -> [BundleFile] {
    [
        BundleFile(name: "MyApp 執行檔", mb: 40),
        BundleFile(name: "Frameworks", mb: 30),
        BundleFile(name: "hero_banner@2x", mb: 6, scale: 2, inCatalog: true),
        BundleFile(name: "hero_banner@3x", mb: 12, scale: 3, inCatalog: true),
        BundleFile(name: "kyc_guide@2x", mb: 4, scale: 2, inCatalog: true),
        BundleFile(name: "kyc_guide@3x", mb: 9, scale: 3, inCatalog: true),
        BundleFile(
            name: "promo@2x", mb: 5, scale: 2, inCatalog: promoInCatalog),
        BundleFile(
            name: "promo@3x", mb: 11, scale: 3, inCatalog: promoInCatalog),
    ]
}

/// 演練流程（兩個版本共用）：downloadSize(App 內容, 裝置)
/// 回傳這台裝置的下載大小
private func scenario(
    _ log: LabLog, downloadSize: ([BundleFile], Device) -> Int
) {
    let before = files(promoInCatalog: false)
    let after = files(promoInCatalog: true)
    log(
        ".ipa 大小：\(before.reduce(0) { $0 + $1.mb }) MB → \(after.reduce(0) { $0 + $1.mb }) MB（promo 搬進 asset catalog）"
    )
    for device in devices {
        let b = downloadSize(before, device)
        let a = downloadSize(after, device)
        log("\(device.name) 下載大小：\(b) MB → \(a) MB（少 \(b - a) MB）")
    }
}

// MARK: - Bug 版
private enum L01Bug {
    static func downloadSize(_ files: [BundleFile], _ device: Device) -> Int {
        // 以為使用者下載的就是整個 .ipa
        files.reduce(0) { $0 + $1.mb }
    }

    static func run(_ log: LabLog) async {
        scenario(log, downloadSize: downloadSize)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func downloadSize(_ files: [BundleFile], _ device: Device) -> Int {
        files
            .filter { file in
                // 執行檔、framework、散落的檔案：每位使用者都下載
                guard file.inCatalog, let scale = file.scale else {
                    return true
                }
                // asset catalog 的圖片：只送這台裝置的倍率
                return scale == device.scale
            }
            .reduce(0) { $0 + $1.mb }
    }

    static func run(_ log: LabLog) async {
        scenario(log, downloadSize: downloadSize)
    }
}

// MARK: - 演練設定
extension Advanced07AppSize {
    static let l01 = Lab(
        id: "L01",
        title: ".ipa 一樣大，使用者的下載卻少了",
        question: "第 1 題：App 體積有哪幾種？為什麼說「變小了」一定要先講是哪一種？",
        goal: "同一個改動，.ipa 大小與使用者的下載大小可以差很多。下載大小要依裝置計算，哪些檔案會被 slicing 要分清楚。",
        steps: [
            "選「Bug 版」執行，看 .ipa 與兩台裝置的下載大小，結論是「沒有效果」嗎？",
            "想一想：asset catalog 裡的 @2x、@3x 圖片，iPhone 15 會下載哪幾份？散落在 bundle 裡的 .png 呢？",
            "選「修正版」執行，比較兩台裝置的下載大小：誰少得比較多？為什麼？",
            "進階：真正的 App Thinning Size Report 在 Xcode Archive 之後，匯出時把 App Thinning 選為 All compatible device variants 產生，會列出每種裝置壓縮後（接近下載大小）與解壓縮後（安裝大小）。",
        ],
        expectBug: "「.ipa 大小：117 MB → 117 MB」，兩台裝置都是「117 MB → 117 MB（少 0 MB）」。",
        expectFixed:
            "「iPhone 15 下載大小：107 MB → 102 MB（少 5 MB）」，「iPhone SE 下載大小：96 MB → 85 MB（少 11 MB）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("iPhone 15 下載大小") })
            else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("iPhone 15 下載大小：117 MB → 117 MB（少 0 MB）") {
                return "Bug 還沒重現：下載大小直接把所有檔案加起來（.ipa 的大小），搬移前後都是 117 MB"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("iPhone 15 下載大小") })
            else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("iPhone 15 下載大小：107 MB → 102 MB（少 5 MB）") {
                return
                    "iPhone 15（@3x）應該是 107 MB → 102 MB：asset catalog 的圖片只算 @3x 那一份，散落的檔案兩份都要算"
            }
            if !lines.contains("iPhone SE 下載大小：96 MB → 85 MB（少 11 MB）") {
                return
                    "iPhone SE（@2x）應該是 96 MB → 85 MB：asset catalog 的圖片只算 @2x 那一份"
            }
            return nil
        }
    )
}
