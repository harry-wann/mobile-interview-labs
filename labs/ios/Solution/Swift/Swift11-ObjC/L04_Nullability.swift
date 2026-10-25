// L04：舊 SDK 沒標 nullability，Swift 端把 nil 當成 String，一用就當掉
// 對應複習站：Swift11ObjC 第 4 題「nullability 是什麼？Swift 看到的 ObjC API 長什麼樣子？」
//
// 情境：舊的 Objective-C SDK 提供 memoForOrder:，這筆委託沒有備註時回傳 nil。Swift 端把它當成 String 來用。
// Bug 版：SDK 的 header 沒有任何 nullability 標註。Swift 把回傳值匯入成 String!（implicitly unwrapped optional），
//        可以直接當 String 用、不用 unwrap，但實際是 nil，一用就當掉：Unexpectedly found nil while implicitly unwrapping an Optional value。
// 修正版：header 用 NS_ASSUME_NONNULL_BEGIN／END 包住，只替真的可能是空的 memoForOrder: 標 nullable。
//        Swift 看到的是 String?，編譯器強迫你處理 nil；沒有標 nullable 的 symbol 則是乾淨的 String。
// Objective-C 的程式在 Solution/SwiftObjC/include/SOLabL04Legacy.h（Bug 版）與 SOLabL04.h（修正版）。
import Foundation
import LabKit
import SolutionSwiftObjC

// MARK: - Bug 版
private enum L04Bug {
    static func run(_ log: LabLog) async {
        let service = SOLabL04LegacyOrderService()
        log("Bug 版的 symbol 在 Swift 的型別：\(type(of: service.symbol))")
        let memo: String = service.memo(forOrder: "BTC-1")      // 沒標註：匯入成 String!，這裡被當成 String，實際是 nil
        log("備註的長度：\(memo.count)")
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func run(_ log: LabLog) async {
        let service = SOLabL04OrderService()
        log("symbol 在 Swift 的型別：\(type(of: service.symbol))")
        log("memo(forOrder:) 在 Swift 的型別：\(type(of: service.memo(forOrder: "BTC-1")))")
        if let memo = service.memo(forOrder: "BTC-1") {          // String?：編譯器強迫你先處理 nil
            log("備註的長度：\(memo.count)")
        } else {
            log("這筆委託沒有備註（nil）")
        }
    }
}

// MARK: - 演練設定
extension Swift11ObjC {
    static let l04 = Lab(
        id: "L04",
        title: "舊 SDK 回傳 nil，Swift 端一用就當掉",
        question: "第 4 題：nullability 是什麼？Swift 看到的 ObjC API 長什麼樣子？",
        goal: "舊的 Objective-C API 沒標 nullability，Swift 會把它匯入成 String!。看它怎麼當掉，再替 header 補上標註，讓 Swift 看到乾淨的 Optional。",
        steps: [
            "從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "選「Bug 版」執行：先印出 symbol 的型別（Optional<String>），接著 App 當掉：Unexpectedly found nil while implicitly unwrapping an Optional value。",
            "按 ■ 停止，重新執行，選「修正版」：symbol 是 String、memo 是 Optional<String>，nil 被 if let 處理，不會當掉。",
            "對照兩個 header：SOLabL04Legacy.h 沒有標註；SOLabL04.h 用 NS_ASSUME_NONNULL_BEGIN／END 包住並標了 nullable。",
        ],
        expectBug: "symbol 的型別是 Optional<String>（String!）；用 let memo: String 接 memoForOrder: 的回傳值，App 當掉：Unexpectedly found nil while implicitly unwrapping an Optional value。",
        expectFixed: "symbol 的型別是 String；memo(forOrder:) 的型別是 Optional<String>；nil 被 if let 處理，印出「這筆委託沒有備註（nil）」。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { _ in
            // 寫對的話 App 會當掉，不會走到這裡
            "App 沒有當掉：要把 memoForOrder: 回傳值直接當成 String 使用（let memo: String = …）"
        },
        checkFixed: { lines in
            if !lines.contains("symbol 在 Swift 的型別：String") { return "symbol 在 Swift 應該是乾淨的 String：用 NS_ASSUME_NONNULL_BEGIN／END 包住整個 header" }
            if !lines.contains("memo(forOrder:) 在 Swift 的型別：Optional<String>") { return "memoForOrder: 在 Swift 應該是 String?：回傳型別要標 nullable" }
            if !lines.contains("這筆委託沒有備註（nil）") { return "沒有備註時，Swift 端要用 if let 處理 nil" }
            return nil
        }
    )
}
