// L12：一次處理 100 張圖片，記憶體衝到好幾百 MB（迴圈裡沒有包 autoreleasepool）
// 對應複習站：Swift02ARC 第 7 題「autoreleasepool（比較少考，被問到能答就好）」
//
// 情境：相簿 App 一次替 100 張圖片產生縮圖並轉成 JPEG。每張圖用
// UIGraphicsImageRenderer 畫出來，再呼叫 jpegData。
// Bug 版：這些 UIKit（Objective-C）API 回傳的物件會先放進 autorelease pool，
// 要等 pool 被清空才真的釋放。
//         整個迴圈在同一個工作裡跑完之前 pool 不會清空，100
// 張圖的記憶體全部堆在一起。
// 修正版：每一張圖用 autoreleasepool { } 包起來，這一輪結束就清空，
// 記憶體高峰只有一張圖的大小。
//
// 數字是用 phys_footprint（系統看到的 App 記憶體用量）量的。純 Swift
// 的物件不會進 autorelease pool，這種迴圈不需要包。
import UIKit
import LabKit

/// 目前 App 的記憶體用量（MB）（兩個版本共用）
private func footprintMB() -> Double {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(
        MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let result = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return result == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : 0
}

/// 產生一張 1000×1000 的圖並轉成 JPEG（假裝是縮圖），回傳 JPEG
/// 的大小（兩個版本共用）
private func makeThumbnail(_ index: Int) -> Int {
    let format = UIGraphicsImageRendererFormat()
    // 1 像素 = 1 點，一張約 4 MB
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(
        size: CGSize(width: 1000, height: 1000), format: format)
    let image = renderer.image { context in
        UIColor(
            hue: CGFloat(index % 100) / 100, saturation: 1, brightness: 1,
            alpha: 1
        ).setFill()
        context.fill(CGRect(x: 0, y: 0, width: 1000, height: 1000))
    }
    return image.jpegData(compressionQuality: 0.8)?.count ?? 0
}

// MARK: - Bug 版
private enum L12Bug {
    static func run(_ log: LabLog) async {
        // 重運算放到背景執行緒；整個迴圈在同一個工作裡跑完，autorelease pool
        // 要等工作結束才清空
        let (peak, processed) = await Task.detached { () -> (Int, Int) in
            let start = footprintMB()
            var peak = start
            var processed = 0
            for index in 0..<100 {
                // 每一張的暫存物件都堆在 pool 裡
                if makeThumbnail(index) > 0 { processed += 1 }
                peak = max(peak, footprintMB())
            }
            return (Int(peak - start), processed)
        }.value
        log("處理了 \(processed) 張圖片")
        log("記憶體高峰比開始時多 \(peak) MB")
    }
}

// MARK: - 修正版
private enum L12Fixed {
    static func run(_ log: LabLog) async {
        let (peak, processed) = await Task.detached { () -> (Int, Int) in
            let start = footprintMB()
            var peak = start
            var processed = 0
            for index in 0..<100 {
                // 每一張圖一個 pool，這一輪結束就清空
                autoreleasepool {
                    if makeThumbnail(index) > 0 { processed += 1 }
                }
                peak = max(peak, footprintMB())
            }
            return (Int(peak - start), processed)
        }.value
        log("處理了 \(processed) 張圖片")
        log("記憶體高峰比開始時多 \(peak) MB")
    }
}

// MARK: - 演練設定
private func peakMB(_ lines: [String]) -> Int? {
    lines.last.flatMap { line in
        let prefix = "記憶體高峰比開始時多 "
        return line.hasPrefix(prefix)
            ? Int(line.dropFirst(prefix.count).dropLast(3)) : nil
    }
}

extension Swift02ARC {
    static let l12 = Lab(
        id: "L12",
        title: "批次處理 100 張圖，記憶體衝到幾百 MB",
        question: "第 7 題：autoreleasepool（比較少考，被問到能答就好）",
        goal: "迴圈裡每一輪都會呼叫 UIKit 的圖片 API。讓記憶體高峰只有一張圖的大小，而不是 100 張的總和。",
        steps: [
            "選「Bug 版」按「執行」，看最後一行「記憶體高峰比開始時多多少 MB」。",
            "選「修正版」執行，比較數字。",
            "進階：Xcode 左邊切到 Debug navigator 的 Memory，再執行 Bug 版，會看到記憶體曲線一路爬升，工作結束才掉下來。",
        ],
        expectBug:
            "處理了 100 張圖片，記憶體高峰比開始時多出幾百 MB（本機模擬器實測約 385 MB），100 張圖的暫存物件全堆著。",
        expectFixed: "處理了 100 張圖片，高峰只多幾 MB（一張圖的大小）。",
        run: { variant, log in
            switch variant {
            case .bug: await L12Bug.run(log)
            case .fixed: await L12Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("處理了 100 張圖片") else {
                return "100 張圖片都要處理（makeThumbnail 回傳大於 0 就算處理成功）"
            }
            guard let mb = peakMB(lines) else { return "找不到記憶體數字" }
            return mb >= 100
                ? nil : "記憶體高峰只多了 \(mb) MB：Bug 還沒重現（迴圈裡不要包 autoreleasepool）"
        },
        checkFixed: { lines in
            guard lines.contains("處理了 100 張圖片") else {
                return "100 張圖片都要處理（makeThumbnail 回傳大於 0 就算處理成功）"
            }
            guard let mb = peakMB(lines) else { return "找不到記憶體數字" }
            return mb <= 50
                ? nil : "記憶體高峰多了 \(mb) MB，還是太高：每一張圖的處理要包進 autoreleasepool { }"
        }
    )
}
