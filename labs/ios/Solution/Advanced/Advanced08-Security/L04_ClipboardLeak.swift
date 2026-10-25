// L04：複製的入金地址一直留在剪貼簿，其他 App 隨時讀得到（剪貼簿要設過期與僅限本機）
// 對應複習站：Advanced08Security 第 6 題「怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？」（剪貼簿）
//
// 情境：入金頁有「複製地址」按鈕。複製的內容留在系統剪貼簿，任何 App 都能讀（iOS 14 起讀取會跳出提示橫幅），
//       還會透過 Universal Clipboard 同步到同一個 Apple ID 的其他裝置。實際的 App 過期時間可以設 60 秒，演練縮短成 0.6 秒。
// Bug 版：直接 UIPasteboard.general.string = 地址。永遠留在剪貼簿，直到使用者複製別的東西。
// 修正版：用 setItems 設定選項：localOnly 不同步到其他裝置，expirationDate 到期自動清除。
//        到期只會清掉這一次複製的內容；使用者中途複製了別的東西，不會被清掉。
import LabKit
import UIKit
import UniformTypeIdentifiers

private let address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
private let expireAfter: TimeInterval = 0.6

/// 演練流程（兩個版本共用）：copy 是「複製地址」按鈕做的事
@MainActor
private func scenario(_ log: LabLog, copy: (String) -> Void) async {
    let pasteboard = UIPasteboard.general
    func current() -> String { pasteboard.string ?? "（空的）" }
    defer { pasteboard.items = [] }                              // 演練結束，把剪貼簿清乾淨

    pasteboard.items = []
    copy(address)
    log("複製地址後立刻讀剪貼簿：\(current())")
    try? await Task.sleep(for: .milliseconds(1_000))
    log("過了 1 秒（超過到期時間），剪貼簿裡：\(current())")

    // 另一種情況：複製地址之後，使用者很快又複製了別的東西
    pasteboard.items = []
    copy(address)
    try? await Task.sleep(for: .milliseconds(200))
    pasteboard.string = "hello"
    try? await Task.sleep(for: .milliseconds(1_000))
    log("中途複製了別的東西，過了到期時間之後，剪貼簿裡：\(current())")
}

// MARK: - Bug 版
private enum L04Bug {
    static func copy(_ text: String) {
        UIPasteboard.general.string = text                      // 沒有過期，也會同步到其他裝置
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, copy: copy)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func copy(_ text: String) {
        UIPasteboard.general.setItems(
            [[UTType.utf8PlainText.identifier: text]],
            options: [
                .localOnly: true,                                       // 不經由 Universal Clipboard 同步到其他裝置
                .expirationDate: Date().addingTimeInterval(expireAfter), // 到期系統自動清除
            ]
        )
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log, copy: copy)
    }
}

// MARK: - 演練設定
extension Advanced08Security {
    static let l04 = Lab(
        id: "L04",
        title: "複製的地址一直留在剪貼簿",
        question: "第 6 題：怎麼防截圖、錄影、剪貼簿和背景快照洩漏資料？（剪貼簿）",
        goal: "敏感內容複製到剪貼簿時，要設定過期時間與「僅限本機」；到期只清掉自己複製的那一筆，不能清掉使用者後來複製的東西。",
        steps: [
            "選「Bug 版」執行，看過了 1 秒之後剪貼簿還有沒有地址。",
            "想一想：UIPasteboard.general.string = … 這樣寫，內容會留多久？",
            "選「修正版」執行：用 setItems 的 localOnly 與 expirationDate；看使用者中途複製別的東西時，到期後剪貼簿還是不是他複製的內容。",
            "進階：Android 13 起可以在複製時用 ClipDescription.EXTRA_IS_SENSITIVE 標記敏感內容，讓複製預覽隱藏；Android 沒有內建過期，要自己延遲呼叫 clearPrimaryClip（API 28 起）。",
        ],
        expectBug: "過了 1 秒（超過到期時間），剪貼簿裡還是完整的地址 bc1qxy2k…，沒有過期。",
        expectFixed: "過了 1 秒，剪貼簿是「（空的）」；中途複製了別的東西，到期之後剪貼簿仍是 hello（沒有被誤清）。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("複製地址後立刻讀剪貼簿") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("過了 1 秒（超過到期時間），剪貼簿裡：\(address)") { return "Bug 還沒重現：copy 要把地址直接寫進 UIPasteboard.general.string，沒有到期" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("複製地址後立刻讀剪貼簿：\(address)") else { return "複製之後剪貼簿裡應該立刻有地址：copy 要用 setItems 把地址放進去（UTType.utf8PlainText）" }
            if !lines.contains("過了 1 秒（超過到期時間），剪貼簿裡：（空的）") { return "到期後剪貼簿應該是空的：setItems 的 options 要放 .expirationDate" }
            if !lines.contains("中途複製了別的東西，過了到期時間之後，剪貼簿裡：hello") { return "使用者中途複製的東西不能被清掉：用系統的 expirationDate，不要自己到期就清空整個剪貼簿" }
            return nil
        }
    )
}
