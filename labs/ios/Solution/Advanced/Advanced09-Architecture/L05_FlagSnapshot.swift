// L05：下單時走舊流程，取消時遠端開關剛好翻成新流程，結果找不到這筆委託（feature flag 一個 session 只評估一次）
// 對應複習站：Advanced09Architecture 第 6 題「feature flag 在重構時怎麼用？要注意什麼？」
//
// 情境：新的下單流程放在遠端 feature flag 後面（newOrderFlow）。舊流程把委託記在舊的儲存，新流程記在新的儲存，兩邊互不認得。
//       使用者用舊流程下了一筆委託，這時營運把放量比例調高，App 在背景刷新了遠端設定，開關翻成「開」，使用者接著按取消。
//       演練的順序：開關=關 → 下單 → 遠端設定刷新（開關變成開）→ 取消 → 下一個 session（重新啟動 App）→ 下單 → 取消。
// Bug 版：每個動作要用哪個流程時，才去讀目前的遠端設定。下單用舊流程，取消時開關已經翻了，改用新流程找，找不到這筆委託。
// 修正版：session 一開始讀一次，存成快照，整個 session 都用同一個值。新的值等到下一個 session 才生效。
import Foundation
import LabKit

/// 假的遠端設定：App 會在背景刷新它（兩個版本共用）
private final class FakeRemoteConfig {
    var newOrderFlow = false
}

/// 下單服務：flag 決定走新流程還是舊流程。兩個流程各有自己的儲存（兩個版本共用）
private final class OrderService {
    private let flag: () -> Bool
    private var legacyOrders = Set<Int>()
    private var newOrders = Set<Int>()

    init(flag: @escaping () -> Bool) {
        self.flag = flag
    }

    func place(_ id: Int) -> String {
        if flag() { newOrders.insert(id); return "新流程" } else { legacyOrders.insert(id); return "舊流程" }
    }

    func cancel(_ id: Int) -> String {
        let removed = flag() ? newOrders.remove(id) : legacyOrders.remove(id)
        return removed == nil ? "找不到委託 #\(id)" : "取消成功"
    }
}

/// 演練流程（兩個版本共用）：makeFlagReader 建立「這個 session 用來讀開關的函式」
private func scenario(_ log: LabLog, makeFlagReader: (FakeRemoteConfig) -> () -> Bool) {
    let remote = FakeRemoteConfig()

    // session 1：開關=關
    let session1 = OrderService(flag: makeFlagReader(remote))
    log("下單 #1：走\(session1.place(1))")
    remote.newOrderFlow = true                                  // 背景刷新了遠端設定：開關翻成「開」
    log("遠端設定刷新：newOrderFlow = 開")
    log("取消 #1：\(session1.cancel(1))")

    // session 2：重新啟動 App，開關=開
    let session2 = OrderService(flag: makeFlagReader(remote))
    log("（重新啟動 App）下單 #2：走\(session2.place(2))")
    log("取消 #2：\(session2.cancel(2))")
}

// MARK: - Bug 版
private enum L05Bug {
    static func makeFlagReader(_ remote: FakeRemoteConfig) -> () -> Bool {
        { remote.newOrderFlow }                                 // 每次用到才去讀目前的值
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeFlagReader: makeFlagReader)
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func makeFlagReader(_ remote: FakeRemoteConfig) -> () -> Bool {
        let snapshot = remote.newOrderFlow                      // session 開始時只讀一次
        return { snapshot }                                     // 整個 session 都用這個值
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeFlagReader: makeFlagReader)
    }
}

// MARK: - 演練設定
extension Advanced09Architecture {
    static let l05 = Lab(
        id: "L05",
        title: "下單走舊流程，取消卻走新流程",
        question: "第 6 題：feature flag 在重構時怎麼用？要注意什麼？（同一個 session 內不要中途翻轉）",
        goal: "同一個 session 內，一個 feature flag 要得到同一個值；遠端設定更新後，新的值等到下一個 session 才生效。",
        steps: [
            "選「Bug 版」執行，看「取消 #1」的結果：下單時走哪個流程？取消時走哪個？",
            "想一想：遠端設定在 session 中途更新了。使用者操作到一半，功能從舊的突然變成新的，會發生什麼事？",
            "選「修正版」執行：取消 #1 成功；重新啟動 App 之後，#2 走新流程，取消也成功。",
            "進階：flag 取不到值（離線、請求失敗）時用 App 內寫好的預設值，金融功能預設關閉；kill switch 要「立刻生效」，那是另一個情境（Advanced10 的演練）。",
        ],
        expectBug: "「下單 #1：走舊流程」，「取消 #1：找不到委託 #1」：開關翻了，取消走去新流程的儲存找，找不到。",
        expectFixed: "「取消 #1：取消成功」（整個 session 都走舊流程）；重新啟動之後「下單 #2：走新流程」、「取消 #2：取消成功」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("下單 #1：走舊流程") else { return "演練沒有跑完：下單 #1 開關是關的，應該走舊流程" }
            if !lines.contains("取消 #1：找不到委託 #1") { return "Bug 還沒重現：取消時要重新讀目前的遠端設定（已經翻成開），改走新流程就找不到" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("下單 #1：走舊流程") else { return "演練沒有跑完：下單 #1 開關是關的，應該走舊流程" }
            if !lines.contains("取消 #1：取消成功") { return "取消 #1 應該成功：整個 session 要用同一個開關的值（session 開始時讀一次）" }
            if !lines.contains("（重新啟動 App）下單 #2：走新流程") { return "重新啟動之後，新的 session 應該讀到新的值（開），走新流程" }
            if !lines.contains("取消 #2：取消成功") { return "取消 #2 應該成功" }
            return nil
        }
    )
}
