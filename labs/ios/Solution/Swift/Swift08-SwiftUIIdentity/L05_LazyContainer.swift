// L05：打開成交紀錄，畫面卡一下才出現（VStack 一次建立所有的列）
// 對應複習站：Swift08SwiftUIIdentity 第 5 題「AnyView 和 Lazy 容器該怎麼用？」
//
// 情境：成交紀錄畫面有 500 筆，放在 ScrollView 裡，畫面一次只看得到十幾列。
// Bug 版：用普通的 VStack。VStack 會一次把 500 列都建立出來，包括根本沒顯示在畫面上的。
// 修正版：改用 LazyVStack。只建立「現在需要顯示的」那幾列，捲到哪裡才建立到哪裡。
// （這一題只做 Lazy 容器；AnyView 的部分沒有演練，見複習站的說明。）
import SwiftUI
import LabKit

/// 成交紀錄的一列（兩個版本共用）
private struct TradeRow: View {
    let id: Int

    var body: some View {
        let _ = BodyCount.tick("L05 成交紀錄列")
        Text("成交 #\(id)　BTC/JPY　0.01 BTC")
            .frame(height: 40)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 演練流程（兩個版本共用）：打開有 500 筆成交紀錄的畫面
@MainActor
private func scenario(_ log: LabLog, screen: some View) async {
    BodyCount.shared.reset()
    let start = DispatchTime.now()
    let stage = SwiftUILabStage(screen)
    await stage.settle()
    let ms = Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
    log("成交紀錄一共 500 筆，畫面打開後建立了 \(BodyCount.shared.value("L05 成交紀錄列")) 列")
    log("從打開到畫面穩定花了約 \(ms) ms（含 settle 的等待，只供參考）")
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）：捲動看看，列是什麼時候建立的
private struct L05Demo: View {
    let variant: LabVariant

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Group {
                switch variant {
                case .bug: L05Bug.Screen()
                case .fixed: L05Fixed.Screen()
                }
            }
            .frame(height: 220)
            BodyCountView(names: ["L05 成交紀錄列"])
        }
    }
}

// MARK: - Bug 版
private enum L05Bug {
    struct Screen: View {
        var body: some View {
            ScrollView {
                VStack {                                    // 一次建立 500 列
                    ForEach(0..<500, id: \.self) { TradeRow(id: $0) }
                }
            }
        }
    }
}

// MARK: - 修正版
private enum L05Fixed {
    struct Screen: View {
        var body: some View {
            ScrollView {
                LazyVStack {                                // 只建立畫面附近的列
                    ForEach(0..<500, id: \.self) { TradeRow(id: $0) }
                }
            }
        }
    }
}

// MARK: - 演練設定
extension Swift08SwiftUIIdentity {
    static let l05 = Lab(
        id: "L05",
        title: "打開成交紀錄，一次建立 500 列",
        question: "第 5 題：AnyView 和 Lazy 容器該怎麼用？",
        goal: "成交紀錄有 500 筆，畫面一次只看得到十幾列。比較 VStack 和 LazyVStack 各建立了幾列。",
        steps: [
            "選「Bug 版」，看操作區的「body 計算 成交紀錄列」：一打開就是 500。",
            "按「執行」，看 log：畫面打開後建立了幾列。",
            "選「修正版」：只建立十幾列；在操作區往下捲，數字會一點一點增加。",
            "對照兩個版本：只差在 VStack 與 LazyVStack。",
        ],
        expectBug: "畫面打開後建立了 500 列，包括根本沒顯示的。",
        expectFixed: "畫面打開後只建立畫面附近的十幾列（實測 16 列），往下捲才會一點一點增加。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log, screen: L05Bug.Screen())
            case .fixed: await scenario(log, screen: L05Fixed.Screen())
            }
        },
        demo: { variant, _ in AnyView(L05Demo(variant: variant)) },
        checkBug: { lines in
            lines.contains("成交紀錄一共 500 筆，畫面打開後建立了 500 列") ? nil : "Bug 還沒重現：用普通的 VStack，500 列都要一次建立"
        },
        checkFixed: { lines in
            guard let line = lines.first(where: { $0.hasPrefix("成交紀錄一共 500 筆，畫面打開後建立了 ") }),
                  let n = Int(line.dropFirst("成交紀錄一共 500 筆，畫面打開後建立了 ".count).prefix(while: \.isNumber)) else { return "還沒有建立列數" }
            if n == 0 { return "畫面沒有畫出任何一列：ScrollView 裡要放 ForEach 的 500 列" }
            if n > 100 { return "建立了 \(n) 列，太多了：換成只建立畫面附近的容器（Lazy）" }
            return nil
        }
    )
}
