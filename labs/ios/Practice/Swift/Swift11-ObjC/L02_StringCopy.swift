// L02：下單物件的幣種，被行情解析器的緩衝改掉了（Objective-C 的 NSString
// 屬性用 strong）
// 對應複習站：Swift11ObjC 第 2 題「為什麼字串屬性要用 `copy`？」
//
// 情境：行情解析器為了省記憶體，共用一個 NSMutableString 緩衝來組字串。
// 下單物件（Order）從緩衝取出幣種「BTC」存起來，
//      解析器接著把同一個緩衝繼續往後接（變成 BTC/JPY）。
//
// 你要寫：
//   ① Bug 版：在 PRLabL02.h，把 Bug 版 Order 的 symbol 屬性改成 strong。
//   ② 修正版：把修正版 Order 的 symbol
// 屬性改成字串慣例該用的特性（設值時複製一份）。
//   Objective-C 的程式在 Practice/SwiftObjC/include/PRLabL02.h 與 PRLabL02.m。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift11-ObjC/L02_StringCopy.swift
//      Objective-C 的解答：Solution/SwiftObjC/include/SOLabL02.h、
// Solution/SwiftObjC/SOLabL02.m
import Foundation
import LabKit
import PracticeSwiftObjC

// MARK: - Bug 版
private enum L02Bug {
    static func run(_ log: LabLog) async {
        let order = PRLabL02BugOrder()
        let buffer = NSMutableString(string: "BTC")
        order.loadSymbol(fromBuffer: buffer)
        log("存進去之後，order.symbol：\(order.symbol)")
        // 解析器繼續使用同一個緩衝
        buffer.append("/JPY")
        log("緩衝被接長之後，order.symbol：\(order.symbol)")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func run(_ log: LabLog) async {
        let order = PRLabL02FixedOrder()
        let buffer = NSMutableString(string: "BTC")
        order.loadSymbol(fromBuffer: buffer)
        log("存進去之後，order.symbol：\(order.symbol)")
        // 解析器繼續使用同一個緩衝
        buffer.append("/JPY")
        log("緩衝被接長之後，order.symbol：\(order.symbol)")
    }
}

// MARK: - 演練設定
extension Swift11ObjC {
    static let l02 = Lab(
        id: "L02",
        title: "下單物件的幣種被別人偷偷改掉",
        question: "第 2 題：為什麼字串屬性要用 `copy`？",
        goal:
            "Objective-C 的 NSString 屬性可能指向可變的 NSMutableString。先寫出屬性值被偷偷改掉的 Bug 版，再改成 copy。",
        steps: [
            "打開 Practice/SwiftObjC/include/PRLabL02.h，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 log：緩衝被接長之後，order.symbol 變成什麼？",
            "寫 TODO ②，選「修正版」執行：order.symbol 維持 BTC。",
            "對照 PRLabL02.h：兩個版本只差在 symbol 屬性是 strong 還是 copy。",
            "想想：如果傳進來的本來就是不可變的 NSString，copy 會不會真的複製資料？（通常只是多持有一次，幾乎沒有成本。）",
        ],
        expectBug: "緩衝被接長之後，order.symbol 變成 BTC/JPY：strong 持有的是同一個可變字串。",
        expectFixed: "緩衝被接長之後，order.symbol 還是 BTC：copy 在設值時複製了一份。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("存進去之後，order.symbol：BTC") {
                return "存進去之後，order.symbol 應該是 BTC"
            }
            if !lines.contains("緩衝被接長之後，order.symbol：BTC/JPY") {
                return
                    "Bug 還沒重現：symbol 屬性要用 strong，緩衝被接長之後，order.symbol 會跟著變成 BTC/JPY"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("存進去之後，order.symbol：BTC") {
                return "存進去之後，order.symbol 應該是 BTC"
            }
            if !lines.contains("緩衝被接長之後，order.symbol：BTC") {
                return "緩衝被接長之後，order.symbol 應該還是 BTC：symbol 屬性要用 copy"
            }
            return nil
        }
    )
}
