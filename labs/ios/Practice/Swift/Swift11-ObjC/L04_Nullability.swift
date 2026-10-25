// L04：舊 SDK 沒標 nullability，Swift 端把 nil 當成 String，一用就當掉
// 對應複習站：Swift11ObjC 第 4 題「nullability 是什麼？Swift 看到的 ObjC API
// 長什麼樣子？」
//
// 情境：舊的 Objective-C SDK 提供 memoForOrder:，這筆委託沒有備註時回傳 nil。
// Swift 端把它當成 String 來用。
//
// 你要寫：
//   ① Bug 版：在 Swift 端，把舊 SDK（沒有標 nullability）的 memoForOrder:
// 回傳值，直接當成 String 使用。
//   ② 修正版：在 PRLabL04.h，用 NS_ASSUME_NONNULL_BEGIN／END 包住 header，並把
// memoForOrder: 標成 nullable。
//   Objective-C 的程式在 Practice/SwiftObjC/include/PRLabL04Legacy.h（Bug 版）
// 與 PRLabL04.h（修正版）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift11-ObjC/L04_Nullability.swift
//      Objective-C 的解答：Solution/SwiftObjC/include/SOLabL04Legacy.h 與
// SOLabL04.h、Solution/SwiftObjC/SOLabL04.m
import Foundation
import LabKit
import PracticeSwiftObjC

// MARK: - Bug 版
private enum L04Bug {
    static func run(_ log: LabLog) async {
        let service = PRLabL04LegacyOrderService()
        log("Bug 版的 symbol 在 Swift 的型別：\(type(of: service.symbol))")
        // TODO ①：用 let memo: String = service.memo(forOrder: "BTC-1")
        // 接回傳值（舊 SDK 沒標註，Swift 匯入成 String!，
        //        可以直接當 String 用），再 log("備註的長度：\(memo.count)")。
        // 這筆委託實際上沒有備註（nil），所以 App 會當掉
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func run(_ log: LabLog) async {
        let service = PRLabL04OrderService()
        log("symbol 在 Swift 的型別：\(type(of: service.symbol))")
        log(
            "memo(forOrder:) 在 Swift 的型別：\(type(of: service.memo(forOrder: "BTC-1")))"
        )
        // String?：編譯器強迫你先處理 nil
        if let memo = service.memo(forOrder: "BTC-1") {
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
        goal:
            "舊的 Objective-C API 沒標 nullability，Swift 會把它匯入成 String!。先寫出會當掉的 Bug 版，再替 header 補上標註，讓 Swift 看到乾淨的 Optional。",
        steps: [
            "打開 Practice/Swift/Swift11-ObjC/L04_Nullability.swift，寫 TODO ①。",
            "從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "選「練習」「Bug 版」執行：先印出 symbol 的型別（Optional<String>），接著 App 當掉：Unexpectedly found nil while implicitly unwrapping an Optional value。",
            "按 ■ 停止，打開 Practice/SwiftObjC/include/PRLabL04.h 寫 TODO ②，重新執行，選「修正版」：symbol 是 String、memo 是 Optional<String>，nil 被 if let 處理，不會當掉。",
            "對照兩個 header：PRLabL04Legacy.h 沒有標註；PRLabL04.h 用 NS_ASSUME_NONNULL_BEGIN／END 包住並標了 nullable。",
        ],
        expectBug:
            "symbol 的型別是 Optional<String>（String!）；用 let memo: String 接 memoForOrder: 的回傳值，App 當掉：Unexpectedly found nil while implicitly unwrapping an Optional value。",
        expectFixed:
            "symbol 的型別是 String；memo(forOrder:) 的型別是 Optional<String>；nil 被 if let 處理，印出「這筆委託沒有備註（nil）」。",
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
            if !lines.contains("symbol 在 Swift 的型別：String") {
                return
                    "symbol 在 Swift 應該是乾淨的 String：用 NS_ASSUME_NONNULL_BEGIN／END 包住整個 header"
            }
            if !lines.contains("memo(forOrder:) 在 Swift 的型別：Optional<String>") {
                return "memoForOrder: 在 Swift 應該是 String?：回傳型別要標 nullable"
            }
            if !lines.contains("這筆委託沒有備註（nil）") {
                return "沒有備註時，Swift 端要用 if let 處理 nil"
            }
            return nil
        }
    )
}
