// L11：寫了「有沒有洩漏」的檢查，卻分不出哪個有洩漏（用 weak
// 變數觀察物件有沒有被釋放）
// 對應複習站：Swift02ARC 第 6 題「懷疑有記憶體洩漏時，怎麼找出來？」
//
// 情境：修好洩漏之後，想寫一個檢查函式 isLeaked 防止再發生：建立物件、
// 放掉自己的參考，用 weak 參考看物件是不是還活著。
//       有兩個 ViewModel：CleanViewModel 沒問題，LeakyViewModel 的 closure
// 用到 self（有 retain cycle）。
// Bug 版：函式裡 let object 一直活到函式結束，weak 參考不可能變成 nil，
// 不管有沒有洩漏都回報「洩漏」，這個檢查永遠是紅燈。
// 修正版：object 宣告成 Optional var，先建立 weak 參考，再把 object 設成 nil；
// 沒洩漏的才會被釋放，有洩漏的還活著。
//
// 區域變數活多久是編譯器決定的。這個演練在 Debug 與 Release
// 建置實測的結果一樣，但寫洩漏檢查時不要靠這個行為，照修正版的寫法。
import LabKit

/// 沒有問題的 ViewModel（兩個版本共用）
private final class CleanViewModel {
    var onMessage: ((String) -> Void)?
    var lastMessage = ""

    init() {
        onMessage = { [weak self] text in self?.lastMessage = text }
    }
}

/// 有 retain cycle 的 ViewModel（兩個版本共用）
private final class LeakyViewModel {
    var onMessage: ((String) -> Void)?
    var lastMessage = ""

    init() {
        onMessage = { text in self.lastMessage = text }
    }
}

// MARK: - Bug 版
private enum L11Bug {
    /// 建立物件，回傳「放掉之後它是不是還活著」
    static func isLeaked<T: AnyObject>(_ make: () -> T) -> Bool {
        // let：這個 strong 參考一直活到函式結束
        let object = make()
        weak let observer = object
        // object 還抓著它，永遠不會是 nil
        return observer != nil
    }

    static func run(_ log: LabLog) async {
        log("CleanViewModel：\(isLeaked { CleanViewModel() } ? "洩漏" : "已釋放")")
        log("LeakyViewModel：\(isLeaked { LeakyViewModel() } ? "洩漏" : "已釋放")")
    }
}

// MARK: - 修正版
private enum L11Fixed {
    /// 建立物件，回傳「放掉之後它是不是還活著」
    static func isLeaked<T: AnyObject>(_ make: () -> T) -> Bool {
        var object: T? = make()
        // weak 參考不會讓引用計數 +1，只用來觀察
        weak let observer = object
        // 放掉自己唯一的 strong 參考
        object = nil
        // 沒洩漏就已經是 nil；有洩漏的還被別人抓著
        return observer != nil
    }

    static func run(_ log: LabLog) async {
        log("CleanViewModel：\(isLeaked { CleanViewModel() } ? "洩漏" : "已釋放")")
        log("LeakyViewModel：\(isLeaked { LeakyViewModel() } ? "洩漏" : "已釋放")")
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l11 = Lab(
        id: "L11",
        title: "洩漏檢查永遠說有洩漏",
        question: "第 6 題：懷疑有記憶體洩漏時，怎麼找出來？",
        goal:
            "寫一個 isLeaked 函式，用 weak 參考觀察物件。它要分得出沒洩漏的 CleanViewModel 和有洩漏的 LeakyViewModel。",
        steps: [
            "選「Bug 版」執行：兩個 ViewModel 都被報成「洩漏」，這個檢查沒有用。",
            "想一想：isLeaked 裡的 object 是 let，函式還沒結束它就還抓著物件，weak 參考怎麼會變成 nil？",
            "選「修正版」執行：CleanViewModel 已釋放，LeakyViewModel 洩漏。",
            "進階：把 LeakyViewModel 的 closure 改成 [weak self]，再執行，兩個都應該是「已釋放」。實務上把這個函式放進 XCTest，用 XCTAssertFalse 當作防止洩漏再發生的測試。",
        ],
        expectBug: "CleanViewModel：洩漏（誤報）；LeakyViewModel：洩漏。",
        expectFixed: "CleanViewModel：已釋放；LeakyViewModel：洩漏。",
        run: { variant, log in
            switch variant {
            case .bug: await L11Bug.run(log)
            case .fixed: await L11Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("LeakyViewModel：洩漏") else {
                return "isLeaked 要回傳「物件放掉之後 weak 參考是不是還活著」"
            }
            return lines.contains("CleanViewModel：洩漏")
                ? nil
                : "Bug 還沒重現：這個檢查的問題是沒洩漏的 CleanViewModel 也被報成洩漏（object 要用 let，一直抓著物件）"
        },
        checkFixed: { lines in
            guard lines.contains("CleanViewModel：已釋放") else {
                return
                    "CleanViewModel 沒有洩漏，應該回報已釋放：先建立 weak 參考，再把 object 設成 nil"
            }
            guard lines.contains("LeakyViewModel：洩漏") else {
                return "LeakyViewModel 有 retain cycle，應該回報洩漏"
            }
            return nil
        }
    )
}
