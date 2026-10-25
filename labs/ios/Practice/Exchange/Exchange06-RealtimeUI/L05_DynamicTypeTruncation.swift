// L05：字體放大之後，價格被截斷成「12,345,…」（價格欄寫死寬度，沒有考慮 Dynamic Type）
// 對應複習站：Exchange06RealtimeUI 第 6 題「數字怎麼顯示，才不會抖動、也不會拖慢畫面？」（價格欄的寬度與字體大小）
//
// 情境：行情列有交易對（BTC_JPY）與價格（12,345,678）。團隊為了讓價格欄上下對齊，給價格欄一個固定寬度，而且限定只顯示一行。
//       使用者在系統設定把字體調到最大（輔助使用的「更大的文字」，Dynamic Type 的 accessibilityExtraExtraExtraLarge），
//       價格被截斷，看不到完整的價格就下單。演練把同一列分別用「預設字體」與「最大字體」畫在螢幕外，量價格欄實際拿到的寬度，
//       和「這串價格完整寫出來需要的寬度」比較。
//
// 你要寫：
//   ① Bug 版：價格 Text 加 .lineLimit(1)、.truncationMode(.tail)，外面用 .frame(width: 100, alignment: .trailing) 寫死寬度。
//      預設字體剛好放得下，最大字體需要的寬度遠超過 100 pt，價格被截斷，而且價格欄的寬度不隨字體變化。
//   ② 修正版：不寫死寬度、不限一行；用 ViewThatFits 讓行情列「並排放得下就並排（HStack），放不下就上下排（VStack）」，價格拿到完整的寬度。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange06-RealtimeUI/L05_DynamicTypeTruncation.swift
import SwiftUI
import LabKit

private let priceSample = "12,345,678"

/// 價格的字體（兩個版本共用）：等寬數字，更新時寬度不抖動
private func priceText(_ price: String) -> Text {
    Text(price).font(.body.monospacedDigit())
}

private extension View {
    /// 量測（兩個版本共用，和 bug 無關）：記下價格這個 view 實際拿到的寬度，以及這串價格完整寫出來需要的寬度
    func priceProbe(_ price: String) -> some View {
        background(GeometryReader { geo in
            let _ = SwiftUILabProbe.note("L05 實際寬度", String(format: "%.1f", Double(geo.size.width)))
            Color.clear
        })
        .background(alignment: .leading) {
            priceText(price).fixedSize().hidden()                   // 看不見的複本：完整寫出來要多寬
                .background(GeometryReader { geo in
                    let _ = SwiftUILabProbe.note("L05 需要寬度", String(format: "%.1f", Double(geo.size.width)))
                    Color.clear
                })
        }
    }
}

/// 畫在螢幕外，量價格欄的寬度（兩個版本共用）
@MainActor
private func measure(_ size: DynamicTypeSize, row: some View) async -> (needed: Double, actual: Double) {
    SwiftUILabProbe.reset()
    let stage = SwiftUILabStage(row.dynamicTypeSize(size))
    await stage.settle()
    let needed = Double(SwiftUILabProbe.value("L05 需要寬度")) ?? 0
    let actual = Double(SwiftUILabProbe.value("L05 實際寬度")) ?? 0
    stage.remove()
    return (needed, actual)
}

/// 演練流程（兩個版本共用）：預設字體與最大字體各畫一次，比較價格欄拿到的寬度
@MainActor
private func scenario(_ log: LabLog, row: some View) async {
    let normal = await measure(.large, row: row)
    let largest = await measure(.accessibility5, row: row)
    func verdict(_ width: (needed: Double, actual: Double)) -> String {
        width.actual + 0.5 >= width.needed ? "完整顯示" : "沒顯示完整（被截斷或被擠成多行）"
    }
    func pt(_ value: Double) -> String { String(format: "%.0f", value) }
    log("預設字體：價格需要 \(pt(normal.needed)) pt，價格欄拿到 \(pt(normal.actual)) pt，\(verdict(normal))")
    log("最大字體（accessibilityExtraExtraExtraLarge）：價格需要 \(pt(largest.needed)) pt，價格欄拿到 \(pt(largest.actual)) pt，\(verdict(largest))")
    let fixedWidth = abs(normal.actual - largest.actual) < 0.5
    log("結果：預設字體\(verdict(normal))；最大字體\(verdict(largest))；價格欄寬度\(fixedWidth ? "不隨字體變化（寫死）" : "會隨字體變化")")
}

/// 畫面類演練的操作區（兩個版本共用）：切換字體大小，看價格有沒有被截斷
private struct L05Demo: View {
    let variant: LabVariant
    @State private var largest = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("字體大小", selection: $largest) {
                Text("預設").tag(false)
                Text("最大（accessibility5）").tag(true)
            }
            .pickerStyle(.segmented)
            Group {
                switch variant {
                case .bug: L05Bug.TickerRow(symbol: "BTC_JPY", price: priceSample)
                case .fixed: L05Fixed.TickerRow(symbol: "BTC_JPY", price: priceSample)
                }
            }
            .dynamicTypeSize(largest ? .accessibility5 : .large)
            .padding(.vertical, 8)
            .background(Color.secondary.opacity(0.12))
            SwiftUILabProbeText(title: "價格需要的寬度", name: "L05 需要寬度")
            SwiftUILabProbeText(title: "價格欄拿到的寬度", name: "L05 實際寬度")
            Text("也可以在模擬器的「設定 > 輔助使用 > 顯示與文字大小 > 更大的文字」調到最大，回到這個畫面看。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Bug 版
private enum L05Bug {
    struct TickerRow: View {
        let symbol: String
        let price: String

        var body: some View {
            HStack {
                Text(symbol)
                Spacer()
                // TODO ①：在 priceText(price) 後面加 .lineLimit(1)、.truncationMode(.tail)，再用 .frame(width: 100, alignment: .trailing) 寫死價格欄的寬度
                priceText(price)
                    .priceProbe(price)
            }
            .padding(.horizontal, 16)
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, row: TickerRow(symbol: "BTC_JPY", price: priceSample))
    }
}

// MARK: - 修正版
private enum L05Fixed {
    struct TickerRow: View {
        let symbol: String
        let price: String

        var body: some View {
            // TODO ②：外面包一層 ViewThatFits(in: .horizontal)，裡面依序放兩種排法：
            //          第一個是下面這個 HStack（放得下就並排）；第二個是 VStack(alignment: .leading)，交易對在上、價格在下（放不下就上下排）
            HStack {
                Text(symbol)
                Spacer()
                priceText(price).priceProbe(price)
            }
            .padding(.horizontal, 16)
        }
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, row: TickerRow(symbol: "BTC_JPY", price: priceSample))
    }
}

// MARK: - 演練設定
extension Exchange06RealtimeUI {
    static let l05 = Lab(
        id: "L05",
        title: "字體放大後，價格被截斷成「12,345,…」",
        question: "第 6 題：數字怎麼顯示，才不會抖動、也不會拖慢畫面？（價格欄的寬度與字體大小）",
        goal: "價格欄不能寫死寬度又限定一行：使用者把字體調大時，價格要完整顯示。不夠寬就換排版（或允許換行），不要讓使用者看到被截斷的價格就下單。 先寫出寫死寬度的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange06-RealtimeUI/L05_DynamicTypeTruncation.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看兩行 log：預設字體放得下，最大字體（accessibilityExtraExtraExtraLarge）價格需要多寬、價格欄只拿到多寬。",
            "在畫面上方把字體切到「最大」：價格變成「12,345,…」。也可以在模擬器開「設定 > 輔助使用 > 顯示與文字大小 > 更大的文字」拉到最大，或在終端機執行 xcrun simctl ui booted content_size accessibility-extra-extra-extra-large 再回來看（看完用 content_size large 改回來）。",
            "想一想：價格被截斷，使用者還是能按下單，會發生什麼事？金額、價格、數量這類不能少一個字的資料，應該怎麼排版？",
            "寫 TODO ②，選「修正版」執行：兩種字體都「完整顯示」，最大字體時價格欄的寬度比預設大很多。切到「最大」確認價格完整，交易對移到上一行。",
            "進階：Android 一樣，用系統的「字型大小」與「顯示大小」調到最大（fontScale 最大約 2.0）；Compose 的 Text 加 maxLines = 1、overflow = Ellipsis 加固定寬度會被截斷。",
        ],
        expectBug: "「最大字體（accessibilityExtraExtraExtraLarge）：價格需要 N pt，價格欄拿到 100 pt，沒顯示完整」，「價格欄寬度不隨字體變化（寫死）」；畫面上價格變成「12,345,…」。",
        expectFixed: "「最大字體…價格欄拿到的寬度等於價格需要的寬度，完整顯示」，「價格欄寬度會隨字體變化」；畫面上交易對與價格上下排，價格完整。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        demo: { variant, _ in AnyView(L05Demo(variant: variant)) },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("結果：") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains(where: { $0.hasPrefix("結果：預設字體完整顯示；最大字體沒顯示完整") }) {
                return "Bug 還沒重現：預設字體要放得下、最大字體要被截斷：價格 Text 加 .lineLimit(1)、.truncationMode(.tail)，外面 .frame(width: 100)"
            }
            if !lines.contains(where: { $0.hasSuffix("價格欄寬度不隨字體變化（寫死）") }) {
                return "Bug 還沒重現：價格欄要寫死寬度（.frame(width: 100)），字體放大它也不會變寬"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("結果：") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains(where: { $0.hasPrefix("結果：預設字體完整顯示；最大字體完整顯示") }) {
                return "最大字體下價格還是沒顯示完整：不要寫死寬度、不要限一行，用 ViewThatFits 讓行情列放不下時改成上下排"
            }
            if !lines.contains(where: { $0.hasSuffix("價格欄寬度會隨字體變化") }) {
                return "價格欄的寬度還是固定的：拿掉 .frame(width:)，讓價格依字體大小拿到它需要的寬度"
            }
            return nil
        }
    )
}
