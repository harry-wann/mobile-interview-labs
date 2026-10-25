// L02：刪掉第一筆委託單，備註卻跑到別筆委託單上（ForEach 用 index 當 id）
// 對應複習站：Swift08SwiftUIIdentity 第 2 題「`.id()` 和 `ForEach` 的 id 要怎麼用才對？」
//
// 情境：委託單列表，每一列有一個備註欄位（用 @State 存），使用者在每一列都打了備註。把第一筆委託單刪掉之後，剩下的列備註全都錯位。
// Bug 版：ForEach(orders.indices, id: \.self)，用 index 當 id。刪掉第 0 筆後，SwiftUI 看到的是「index 0 那一列還在，只是資料換了」，
//        所以保留 index 0 那一列的備註狀態，上一筆委託單的備註就出現在下一筆委託單的那一列。
// 修正版：Order 遵守 Identifiable，ForEach(orders) 用委託單編號當 id。SwiftUI 知道被刪掉的是哪一列，狀態跟著正確的列走。
import SwiftUI
import LabKit

private struct Order: Identifiable {
    let id: String
}

/// 委託單列表（兩個版本共用）
private final class OrderList: ObservableObject {
    @Published var orders = [Order(id: "A"), Order(id: "B"), Order(id: "C")]
}

/// 委託單那一列（兩個版本共用）：備註是這一列自己的 @State
private struct OrderRow: View {
    let order: Order
    @State private var note = ""

    var body: some View {
        let _ = SwiftUILabProbe.note("L02 委託單 \(order.id) 那一列的備註", note.isEmpty ? "（空白）" : note)
        HStack {
            Text("委託單 \(order.id)")
            TextField("備註", text: $note).textFieldStyle(.roundedBorder)
        }
        .onAppear { note = "備註 \(order.id)" }      // 模擬使用者在每一列打了備註（寫著它原本屬於哪一筆委託單）
    }
}

/// 演練流程（兩個版本共用）：三列都打了備註 → 刪掉第一筆委託單 A
@MainActor
private func scenario(_ log: LabLog, screen: (OrderList) -> some View) async {
    SwiftUILabProbe.reset()
    let list = OrderList()
    let stage = SwiftUILabStage(screen(list))
    await stage.settle()
    log("刪除前：委託單 B 那一列的備註是 \(SwiftUILabProbe.value("L02 委託單 B 那一列的備註"))")
    list.orders.remove(at: 0)                        // 使用者刪掉第一筆委託單 A
    await stage.settle()
    log("刪掉委託單 A 後：委託單 B 那一列的備註是 \(SwiftUILabProbe.value("L02 委託單 B 那一列的備註"))")
    log("刪掉委託單 A 後：委託單 C 那一列的備註是 \(SwiftUILabProbe.value("L02 委託單 C 那一列的備註"))")
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）：刪掉第一筆，看備註有沒有跟著自己的委託單
private struct L02Demo: View {
    let variant: LabVariant
    @StateObject private var list = OrderList()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            switch variant {
            case .bug: L02Bug.Screen(list: list)
            case .fixed: L02Fixed.Screen(list: list)
            }
            Button("刪掉第一筆") { if !list.orders.isEmpty { list.orders.removeFirst() } }
                .buttonStyle(.bordered)
        }
    }
}

// MARK: - Bug 版
private enum L02Bug {
    struct Screen: View {
        @ObservedObject var list: OrderList

        var body: some View {
            VStack {
                ForEach(list.orders.indices, id: \.self) { i in     // 用 index 當 id：刪掉第 0 筆，index 0 那一列還在
                    OrderRow(order: list.orders[i])
                }
            }
        }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    struct Screen: View {
        @ObservedObject var list: OrderList

        var body: some View {
            VStack {
                ForEach(list.orders) { order in                     // Order 遵守 Identifiable：用委託單編號當 id
                    OrderRow(order: order)
                }
            }
        }
    }
}

// MARK: - 演練設定
extension Swift08SwiftUIIdentity {
    static let l02 = Lab(
        id: "L02",
        title: "刪掉第一筆，備註跑到別筆委託單上",
        question: "第 2 題：`.id()` 和 `ForEach` 的 id 要怎麼用才對？",
        goal: "三筆委託單各有一個備註（存在那一列的 @State）。刪掉第一筆之後，每筆委託單的備註要還是它自己的。",
        steps: [
            "選「Bug 版」，看操作區：每一列的備註都寫著自己的委託單（備註 A、備註 B、備註 C）。",
            "按「刪掉第一筆」：委託單 B 那一列的備註變成「備註 A」，錯位了。",
            "按「執行」，看 log：刪掉委託單 A 後，委託單 B、C 那一列的備註各是什麼。",
            "選「修正版」，同樣刪掉第一筆：委託單 B 的備註還是「備註 B」。",
        ],
        expectBug: "刪掉委託單 A 後，委託單 B 那一列的備註是「備註 A」、委託單 C 那一列的備註是「備註 B」。",
        expectFixed: "刪掉委託單 A 後，委託單 B 那一列的備註是「備註 B」、委託單 C 那一列的備註是「備註 C」。",
        run: { variant, log in
            switch variant {
            case .bug: await scenario(log) { L02Bug.Screen(list: $0) }
            case .fixed: await scenario(log) { L02Fixed.Screen(list: $0) }
            }
        },
        demo: { variant, _ in AnyView(L02Demo(variant: variant)) },
        checkBug: { lines in
            if !lines.contains("刪除前：委託單 B 那一列的備註是 備註 B") { return "列表沒有跑起來：刪除前，委託單 B 那一列的備註要是「備註 B」" }
            if !lines.contains("刪掉委託單 A 後：委託單 B 那一列的備註是 備註 A") { return "Bug 還沒重現：ForEach 要用 index 當 id，刪掉第一筆後，B 那一列會留著 A 的備註" }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("刪除前：委託單 B 那一列的備註是 備註 B") { return "列表沒有跑起來：刪除前，委託單 B 那一列的備註要是「備註 B」" }
            if !lines.contains("刪掉委託單 A 後：委託單 B 那一列的備註是 備註 B") { return "刪掉 A 之後，B 那一列的備註應該還是「備註 B」：ForEach 要用委託單編號當 id" }
            if !lines.contains("刪掉委託單 A 後：委託單 C 那一列的備註是 備註 C") { return "刪掉 A 之後，C 那一列的備註應該還是「備註 C」" }
            return nil
        }
    )
}
