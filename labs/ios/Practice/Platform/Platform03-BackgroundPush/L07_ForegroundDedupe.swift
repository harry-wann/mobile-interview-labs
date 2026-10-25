// L07：App 開著的時候，同一個價格提醒跳了兩次（前景去重）
// 對應複習站：Platform03BackgroundPush 第 6 題「價格提醒要怎麼設計？」
//
// 情境：使用者正在看行情頁，App 在前景，已經用 WebSocket 串流收行情與提醒。
// 同一個價格提醒，伺服器同時用兩條路送：
//        WebSocket（App 內橫幅）和推播（iOS 在前景收到推播時，willPresent
// 回呼可以決定要不要顯示）。
//        演練送兩個提醒：提醒 a1 兩條路都到了，提醒 a2 只有推播到。
// 看畫面上一共顯示了幾次橫幅。
//
// 你要寫：
//   ① Bug 版：串流與推播收到就各自顯示橫幅。
//   ② 修正版：用 alertId 記下已經顯示過的，同一個 alertId 只顯示一次。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform03-BackgroundPush/L07_ForegroundDedupe.swift
import Foundation
import LabKit

/// 一個價格提醒（兩個版本共用）
private struct PriceAlert {
    let id: String
    let text: String
}

/// App 內顯示橫幅的地方（兩個版本各自實作）
private protocol AlertPresenter: AnyObject {
    var banners: [String] { get }
    func fromWebSocket(_ alert: PriceAlert)  // App 內串流收到
    // 推播收到（iOS：userNotificationCenter(_:willPresent:)）
    func fromPush(_ alert: PriceAlert)
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, presenter: AlertPresenter) {
    let a1 = PriceAlert(id: "a1", text: "BTC/JPY 到達 10,000,000")
    let a2 = PriceAlert(id: "a2", text: "ETH/JPY 到達 500,000")
    presenter.fromWebSocket(a1)  // 提醒 a1：串流先到
    presenter.fromPush(a1)  //        推播也到
    // 提醒 a2：只有推播到
    presenter.fromPush(a2)
    log(
        "畫面上一共顯示了 \(presenter.banners.count) 次橫幅：\(presenter.banners.joined(separator: "、"))"
    )
}

// MARK: - Bug 版
private enum L07Bug {
    final class Presenter: AlertPresenter {
        private(set) var banners: [String] = []

        // TODO ①：兩個函式都一樣：收到就把 alert.id 加進 banners（直接顯示，
        // 不檢查有沒有顯示過）
        func fromWebSocket(_ alert: PriceAlert) {}
        func fromPush(_ alert: PriceAlert) {}
    }

    static func run(_ log: LabLog) async {
        scenario(log, presenter: Presenter())
    }
}

// MARK: - 修正版
private enum L07Fixed {
    final class Presenter: AlertPresenter {
        private(set) var banners: [String] = []
        // 已經顯示過的 alertId
        private var shown = Set<String>()

        func fromWebSocket(_ alert: PriceAlert) { show(alert) }
        func fromPush(_ alert: PriceAlert) { show(alert) }

        private func show(_ alert: PriceAlert) {
            // TODO ②：同一個 alertId 只顯示一次：用 shown 記下 alert.id，
            // 已經在裡面就 return；否則把 alert.id 加進 banners
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, presenter: Presenter())
    }
}

// MARK: - 演練設定
private func bannerCount(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("畫面上一共顯示了 ") }
        .flatMap {
            Int(
                $0.replacingOccurrences(of: "畫面上一共顯示了 ", with: "").components(
                    separatedBy: " "
                ).first ?? "")
        }
}

extension Platform03BackgroundPush {
    static let l07 = Lab(
        id: "L07",
        title: "App 開著，同一個提醒跳了兩次",
        question: "第 6 題：價格提醒要怎麼設計？",
        goal:
            "同一個提醒用不同的路送到（串流、推播），畫面上只能顯示一次；只從一條路到的提醒還是要顯示。先寫出同一個提醒顯示兩次的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform03-BackgroundPush/L07_ForegroundDedupe.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「畫面上一共顯示了幾次橫幅」。",
            "寫 TODO ②（修正版），選「修正版」執行：a1 只顯示一次，a2 照常顯示，一共 2 次。",
            "想一想：alertId 要由誰產生？（由伺服器產生，推播與串流帶同一個 alertId，client 才能去重）",
        ],
        expectBug: "畫面上一共顯示了 3 次橫幅：a1、a1、a2。",
        expectFixed: "畫面上一共顯示了 2 次橫幅：a1、a2。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = bannerCount(lines) else { return "演練沒有跑完" }
            return n >= 3 ? nil : "Bug 還沒重現（只顯示 \(n) 次）：兩條路收到就各自顯示"
        },
        checkFixed: { lines in
            guard let n = bannerCount(lines) else { return "演練沒有跑完" }
            if n > 2 {
                return "顯示了 \(n) 次：用 alertId 記下已經顯示過的，同一個 alertId 不重複顯示"
            }
            if n < 2 { return "只顯示了 \(n) 次：只從一條路到的提醒（a2）還是要顯示" }
            return nil
        }
    )
}
