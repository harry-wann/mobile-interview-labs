// L06：螢幕閱讀器只唸「12,345,678」和「按鈕」，UI 測試也找不到元素（行情列沒有無障礙標籤與 identifier）
// 對應複習站：Exchange06RealtimeUI 第 6 題「數字怎麼顯示，才不會抖動、也不會拖慢畫面？」（畫面上的數字怎麼唸出來、怎麼被測試找到）
//
// 情境：行情列有交易對 BTC_JPY、價格 12,345,678、漲跌（只用綠色向上箭頭表示上漲 1.23%），最右邊是一顆只有購物車圖示的下單按鈕。
//       視障使用者開 VoiceOver 要能聽到「比特幣、價格、漲還是跌、可以下單」；QA 寫 UI 測試（XCUITest）要能用 identifier 找到這一列和那顆按鈕。
//       演練把這一列畫在螢幕外，讀出 VoiceOver 看到的元素（accessibility 樹）：標籤（label）、值（value）、identifier、是不是按鈕。
//
// 你要寫：
//   ① Bug 版：漲跌只靠顏色與箭頭圖示（Image(systemName: "arrow.up") 加 .foregroundStyle(.green)），下單按鈕只有圖示
//      （Button 裡面只有 Image(systemName: "cart")），都不加任何無障礙修飾。VoiceOver 看到 4 個元素，沒有人說「上漲」，按鈕沒有名稱。
//   ② 修正版：行情那一塊合成一個元素，加上 label、value（把價格與漲跌唸完整）與 identifier「ticker.BTC_JPY.quote」；
//      下單按鈕加 label「下單 BTC_JPY」與 identifier「ticker.BTC_JPY.order」。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange06-RealtimeUI/L06_Accessibility.swift
import SwiftUI
import UIKit
import LabKit

/// 一個 VoiceOver 看得到的元素
private struct AXItem {
    let label: String
    let value: String
    let identifier: String
    let isButton: Bool

    /// VoiceOver 會唸的：標籤、值，按鈕再加「按鈕」
    var spoken: String {
        let text = [label, value].filter { !$0.isEmpty }.joined(separator: "，")
        let base = text.isEmpty ? "（沒有名稱）" : text
        return isButton ? "\(base)，按鈕" : base
    }
}

/// accessibilityIdentifier 屬於 UIAccessibilityIdentification，SwiftUI 的元素不一定宣告它，用選擇器讀
private func identifier(of object: NSObject) -> String {
    let selector = NSSelectorFromString("accessibilityIdentifier")
    guard object.responds(to: selector) else { return "" }
    return object.value(forKey: "accessibilityIdentifier") as? String ?? ""
}

/// 走訪 accessibility 樹（兩個版本共用）：取出所有 VoiceOver 會停留的元素
@MainActor
private func accessibilityItems(in root: UIView) -> [AXItem] {
    var items: [AXItem] = []
    func visit(_ object: NSObject) {
        if object.isAccessibilityElement {
            items.append(AXItem(
                label: object.accessibilityLabel ?? "",
                value: object.accessibilityValue ?? "",
                identifier: identifier(of: object),
                isButton: object.accessibilityTraits.contains(.button)
            ))
            return
        }
        if let elements = object.accessibilityElements {
            for case let child as NSObject in elements { visit(child) }
            return
        }
        let count = object.accessibilityElementCount()
        if count != NSNotFound, count > 0 {
            for index in 0..<count {
                if let child = object.accessibilityElement(at: index) as? NSObject { visit(child) }
            }
            return
        }
        if let view = object as? UIView {
            for subview in view.subviews { visit(subview) }
        }
    }
    visit(root)
    return items
}

/// SwiftUI 只有在系統的「無障礙」開著時才會建立 accessibility 樹（VoiceOver、XCUITest 開著時）。
/// 自動檢查沒有人開 VoiceOver，所以像 XCUITest 一樣，暫時把這個 App 的「自動化無障礙」打開（兩個版本共用）
private func setAutomationAccessibility(_ enabled: Bool) -> Bool {
    guard let handle = dlopen("/usr/lib/libAccessibility.dylib", RTLD_NOW),
          let symbol = dlsym(handle, "_AXSSetAutomationEnabled") else { return false }
    unsafeBitCast(symbol, to: (@convention(c) (Bool) -> Void).self)(enabled)
    return true
}

/// 演練流程（兩個版本共用）：把行情列畫在螢幕外，列出 VoiceOver 看到的元素，再用 identifier 找
@MainActor
private func scenario(_ log: LabLog, row: some View) async {
    let enabled = setAutomationAccessibility(true)
    defer { if enabled { _ = setAutomationAccessibility(false) } }
    let hosting = UIHostingController(rootView: AnyView(row))
    let stage = SwiftUILabStage(viewController: hosting)
    await stage.settle()
    let items = accessibilityItems(in: hosting.view)
    stage.remove()

    log("VoiceOver 看到 \(items.count) 個元素")
    for (index, item) in items.enumerated() {
        log("元素 \(index + 1)：唸出「\(item.spoken)」，identifier「\(item.identifier)」")
    }
    let spokenAll = items.map(\.spoken).joined(separator: "｜")
    log("漲跌有被唸出來（提到「上漲」）：\(spokenAll.contains("上漲") ? "是" : "否")")
    log("下單按鈕有名稱（提到「下單」）：\(items.contains { $0.isButton && $0.spoken.contains("下單") } ? "是" : "否")")
    for identifier in ["ticker.BTC_JPY.quote", "ticker.BTC_JPY.order"] {
        log("UI 測試用 identifier 找 \(identifier)：\(items.contains { $0.identifier == identifier } ? "找到" : "找不到")")
    }
}

/// 畫面類演練的操作區（兩個版本共用）：把行情列放在畫面上，用 Accessibility Inspector 或 VoiceOver 看
private struct L06Demo: View {
    let variant: LabVariant

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch variant {
            case .bug: L06Bug.TickerRow()
            case .fixed: L06Fixed.TickerRow()
            }
            Text("用 Xcode 的 Accessibility Inspector 指到這一列，看 Label、Value、Identifier；或打開 VoiceOver 聽。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 8)
        .background(Color.secondary.opacity(0.12))
    }
}

// MARK: - Bug 版
private enum L06Bug {
    struct TickerRow: View {
        var body: some View {
            HStack {
                Text("BTC_JPY")
                Spacer()
                Text("12,345,678").font(.body.monospacedDigit())
                // TODO ①-1：漲跌只靠顏色與箭頭：Image(systemName: "arrow.up")，加 .foregroundStyle(.green)
                // TODO ①-2：下單按鈕只有圖示：Button(action: {}) { Image(systemName: "cart") }（兩個都不要加任何 accessibility 修飾）
            }
            .padding(.horizontal, 16)
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, row: TickerRow())
    }
}

// MARK: - 修正版
private enum L06Fixed {
    struct TickerRow: View {
        var body: some View {
            HStack {
                HStack {
                    Text("BTC_JPY")
                    Spacer()
                    Text("12,345,678").font(.body.monospacedDigit())
                    Image(systemName: "arrow.up").foregroundStyle(.green)
                }
                // TODO ②-1：這一塊（上面的內層 HStack）加：.accessibilityElement(children: .ignore) 合成一個元素、
                //          .accessibilityLabel 寫幣種（例如「BTC 兌日圓」）、.accessibilityValue 寫「12,345,678 日圓，上漲 1.23%」、
                //          .accessibilityIdentifier("ticker.BTC_JPY.quote")
                Button(action: {}) { Image(systemName: "cart") }
                // TODO ②-2：按鈕加 .accessibilityLabel("下單 BTC_JPY") 與 .accessibilityIdentifier("ticker.BTC_JPY.order")
            }
            .padding(.horizontal, 16)
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, row: TickerRow())
    }
}

// MARK: - 演練設定
extension Exchange06RealtimeUI {
    static let l06 = Lab(
        id: "L06",
        title: "螢幕閱讀器只唸數字和「按鈕」",
        question: "第 6 題：數字怎麼顯示，才不會抖動、也不會拖慢畫面？（畫面上的數字怎麼唸出來、怎麼被測試找到）",
        goal: "行情列不能只靠顏色與圖示表達漲跌：用 accessibilityLabel／accessibilityValue 把幣種、價格、漲跌唸完整，圖示按鈕要有名稱，並加 accessibilityIdentifier 讓 UI 測試找得到。 先寫出什麼都沒加的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange06-RealtimeUI/L06_Accessibility.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 log 列出 VoiceOver 看到的元素：有幾個？哪一個有提到「上漲」？按鈕唸成什麼？UI 測試用 identifier 找得到嗎？",
            "想一想：視障使用者聽到「12,345,678」和「Shopping Cart，按鈕」，知道這是什麼幣、漲還是跌嗎？只靠綠色與紅色表示漲跌，色覺異常的使用者呢？",
            "寫 TODO ②，選「修正版」執行：行情列只剩兩個元素，唸出「BTC 兌日圓，12,345,678 日圓，上漲 1.23%」與「下單 BTC_JPY，按鈕」，兩個 identifier 都找得到。",
            "在模擬器實際看：切到上方的畫面，用 Xcode 的 Accessibility Inspector（Xcode > Open Developer Tool > Accessibility Inspector，選模擬器）指到行情列，看 Label、Value、Identifier；或在模擬器開「設定 > 輔助使用 > VoiceOver」。",
            "進階：Android 一樣，用 contentDescription 或 semantics { contentDescription = …; stateDescription = … }，測試用 testTag 找；iOS 的 accessibilityIdentifier 對應 Android 的 testTag。",
        ],
        expectBug: "「VoiceOver 看到 4 個元素」（交易對、價格、箭頭、按鈕各一個），箭頭只唸 Up、按鈕只唸 Shopping Cart；「漲跌有被唸出來（提到「上漲」）：否」，「下單按鈕有名稱（提到「下單」）：否」，兩個 identifier 都「找不到」。",
        expectFixed: "「VoiceOver 看到 2 個元素」：「BTC 兌日圓，12,345,678 日圓，上漲 1.23%」與「下單 BTC_JPY，按鈕」；「漲跌有被唸出來：是」，「下單按鈕有名稱：是」，兩個 identifier 都「找到」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        demo: { variant, _ in AnyView(L06Demo(variant: variant)) },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("VoiceOver 看到") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("VoiceOver 看到 4 個元素") { return "Bug 還沒重現：行情列要拆成 4 個元素：交易對 Text、價格 Text、箭頭 Image(systemName: \"arrow.up\")、按鈕 Button { Image(systemName: \"cart\") }，不要加任何 accessibility 修飾" }
            if !lines.contains(where: { $0.hasPrefix("元素") && $0.contains("，按鈕」") }) { return "Bug 還沒重現：要有一顆只有圖示（Image(systemName: \"cart\")）的按鈕" }
            if !lines.contains("漲跌有被唸出來（提到「上漲」）：否") { return "Bug 還沒重現：漲跌只靠 foregroundStyle 與箭頭圖示，不要加 accessibilityValue" }
            if !lines.contains("下單按鈕有名稱（提到「下單」）：否") { return "Bug 還沒重現：按鈕只有圖示，不要加 accessibilityLabel" }
            if lines.contains(where: { $0.hasPrefix("UI 測試用 identifier") && $0.hasSuffix("：找到") }) { return "Bug 還沒重現：Bug 版不要加 accessibilityIdentifier，UI 測試找不到" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("VoiceOver 看到") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains(where: { $0.hasPrefix("元素") && $0.contains("12,345,678") && $0.contains("上漲") && $0.contains("identifier「ticker.BTC_JPY.quote」") }) {
                return "行情那一塊要合成一個元素：.accessibilityElement(children: .ignore)，label 寫幣種，value 寫「12,345,678 日圓，上漲 1.23%」，.accessibilityIdentifier(\"ticker.BTC_JPY.quote\")"
            }
            if !lines.contains(where: { $0.hasPrefix("元素") && $0.contains("下單") && $0.contains("，按鈕」") && $0.contains("identifier「ticker.BTC_JPY.order」") }) {
                return "下單按鈕要有名稱與 identifier：.accessibilityLabel(\"下單 BTC_JPY\")、.accessibilityIdentifier(\"ticker.BTC_JPY.order\")"
            }
            if !lines.contains("VoiceOver 看到 2 個元素") { return "行情列應該只剩 2 個元素：行情（交易對、價格、箭頭合成一個）與下單按鈕" }
            if !lines.contains("漲跌有被唸出來（提到「上漲」）：是") { return "value 要唸出漲跌：「上漲 1.23%」" }
            return nil
        }
    )
}
