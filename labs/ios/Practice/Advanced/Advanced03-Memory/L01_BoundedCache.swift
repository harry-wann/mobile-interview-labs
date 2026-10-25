// L01：頭像一路滑下去，快取越來越大，收到記憶體警告也不放（沒有上限的字典快取，對照 NSCache）
// 對應複習站：Advanced03Memory 第 1 題「iOS 和 Android 是怎麼因為記憶體不足而殺掉 App 的？收到警告時該做什麼？」
//
// 情境：行情與社群頁會顯示很多幣種與用戶的頭像。每張解碼後的頭像約 100 KB，使用者一路往下滑，總共經過 200 張（約 20 MB）。
//        接著系統發出記憶體警告（UIApplication.didReceiveMemoryWarningNotification）。
//        被 jetsam 殺掉不會有 crash log，所以要平常就讓用量有上限，收到警告時馬上放掉「可以重建」的東西（圖片快取）。
//
// 你要寫：
//   ① Bug 版 DictionaryCache：用 Dictionary 存，沒有上限，也不處理記憶體警告。
//   ② 修正版 BoundedCache：NSCache 設 totalCostLimit = 5 MB，store 時帶上 cost（資料的位元組數）；
//      init 裡用 NotificationCenter 監聽 UIApplication.didReceiveMemoryWarningNotification，收到就 removeAllObjects()。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced03-Memory/L01_BoundedCache.swift
import UIKit
import LabKit

private let imageCount = 200
private let imageBytes = 100 * 1024

/// 頭像快取的共同介面（兩個版本共用）
private protocol AvatarCache: AnyObject {
    func store(_ data: Data, for key: String)
    func contains(_ key: String) -> Bool
}

/// 演練流程（兩個版本共用）：滑過 200 張頭像 → 看快取留了幾張 → 系統發出記憶體警告 → 再看一次
private func scenario(_ log: LabLog, cache: some AvatarCache) {
    let keys = (0..<imageCount).map { "avatar-\($0)" }
    for key in keys {
        cache.store(Data(repeating: 1, count: imageBytes), for: key)   // 解碼後的頭像，約 100 KB
    }
    let before = keys.filter { cache.contains($0) }.count
    log("滑過 \(imageCount) 張頭像，快取裡還留著 \(before) 張（約 \(before * imageBytes / 1_048_576) MB）")
    NotificationCenter.default.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    let after = keys.filter { cache.contains($0) }.count
    log("收到記憶體警告之後，快取裡還有 \(after) 張")
}

// MARK: - Bug 版
private enum L01Bug {
    final class DictionaryCache: AvatarCache {
        private var storage: [String: Data] = [:]                      // 沒有上限，也不會自己清

        func store(_ data: Data, for key: String) {
            // TODO ①-1：把 data 存進 storage[key]
        }

        func contains(_ key: String) -> Bool {
            // TODO ①-2：storage[key] 有值就是 true
            false
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, cache: DictionaryCache())
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class BoundedCache: AvatarCache {
        private let cache = NSCache<NSString, NSData>()
        private var observer: NSObjectProtocol?

        init() {
            // TODO ②-1：cache.totalCostLimit 設成 5 * 1024 * 1024（最多約 5 MB）
            // TODO ②-2：用 NotificationCenter.default.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil)
            //          收到時呼叫 cache.removeAllObjects()（closure 裡用 [weak cache]），把回傳值存進 observer
        }

        deinit {
            if let observer { NotificationCenter.default.removeObserver(observer) }
        }

        func store(_ data: Data, for key: String) {
            // TODO ②-3：cache.setObject(data as NSData, forKey: key as NSString, cost: data.count)；cost 是這一筆占多少位元組
        }

        func contains(_ key: String) -> Bool { cache.object(forKey: key as NSString) != nil }
    }

    static func run(_ log: LabLog) async {
        scenario(log, cache: BoundedCache())
    }
}

// MARK: - 演練設定
extension Advanced03Memory {
    static let l01 = Lab(
        id: "L01",
        title: "頭像一路滑下去，快取越來越大，收到警告也不放",
        question: "第 1 題：iOS 和 Android 是怎麼因為記憶體不足而殺掉 App 的？收到警告時該做什麼？",
        goal: "圖片快取要有大小上限，收到記憶體警告時立刻清掉可以重建的東西。先寫出沒有上限的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced03-Memory/L01_BoundedCache.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看兩行 log：滑完 200 張之後快取留了幾張、收到記憶體警告之後還有幾張。",
            "寫 TODO ②，選「修正版」執行：快取最多只留約 5 MB（50 張左右），收到警告之後是 0 張。",
            "進階：Xcode 的模擬器選單 Debug › Simulate Memory Warning 可以對跑著的 App 發出真的記憶體警告；真機上用 MetricKit 的 `MXAppExitMetric` 或 Xcode Organizer 看被 jetsam 終止的次數（這種終止不會有 crash log）。",
            "想一想：為什麼「有處理記憶體警告」還不夠？（jetsam 常常沒有任何警告就終止背景的 App，見這個單元的第 1 題）",
        ],
        expectBug: "滑過 200 張頭像，快取裡還留著 200 張（約 19 MB）；收到記憶體警告之後，快取裡還有 200 張。",
        expectFixed: "滑過 200 張頭像，快取裡還留著 51 張（約 4 MB）左右；收到記憶體警告之後，快取裡還有 0 張。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let before = a03Number(in: lines, prefix: "滑過 200 張頭像，快取裡還留著 "),
                  let after = a03Number(in: lines, prefix: "收到記憶體警告之後，快取裡還有 ") else { return "演練沒有跑完" }
            if before < 200 { return "快取只留了 \(Int(before)) 張：Bug 版用沒有上限的 Dictionary，200 張都要留著" }
            return after >= 200 ? nil : "記憶體警告之後只剩 \(Int(after)) 張：Bug 版不處理警告，快取要原封不動"
        },
        checkFixed: { lines in
            guard let before = a03Number(in: lines, prefix: "滑過 200 張頭像，快取裡還留著 "),
                  let after = a03Number(in: lines, prefix: "收到記憶體警告之後，快取裡還有 ") else { return "演練沒有跑完" }
            if before == 0 { return "快取裡一張都沒有：store 要呼叫 cache.setObject(…, cost: data.count)" }
            if before > 60 { return "快取留了 \(Int(before)) 張，沒有上限：NSCache 要設 totalCostLimit = 5 MB，而且 setObject 要帶 cost" }
            return after == 0 ? nil : "收到記憶體警告之後還有 \(Int(after)) 張：用 NotificationCenter 監聽 UIApplication.didReceiveMemoryWarningNotification，在裡面 removeAllObjects()"
        }
    )
}
