// L03：切到深色模式，下單卡片的邊框還是淺色的（iOS 不重建畫面，要自己處理
// trait 改變）
// 對應複習站：Platform01Lifecycle 第 3 題「旋轉螢幕或切換深色模式時，
// 兩個平台各發生什麼事？」
//
// 情境：下單頁有一張卡片，邊框用系統的分隔線顏色 UIColor.separator。
// 使用者在控制中心把系統切到深色模式。
//        iOS 不會銷毀 ViewController（演練會確認前後是同一個物件），
// 只會通知它「trait（描述版面環境的資訊，例如深色或淺色）改變了」。
//
// 你要寫：
//   ① Bug 版：在 viewDidLoad 把 UIColor.separator 轉成 cgColor 設給邊框，
// 之後不更新。
//   ② 修正版：深淺色改變時（registerForTraitChanges）重新解析顏色再設給邊框。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform01-Lifecycle/L03_TraitChange.swift
import UIKit
import LabKit

/// 取得某個深淺色模式下，邊框應該是什麼顏色（兩個版本共用）
private func expectedBorder(_ style: UIUserInterfaceStyle) -> [CGFloat] {
    components(
        UIColor.separator.resolvedColor(
            with: UITraitCollection(userInterfaceStyle: style)
        ).cgColor)
}

private func components(_ color: CGColor?) -> [CGFloat] {
    guard let color,
        let rgb = color.converted(
            to: CGColorSpace(name: CGColorSpace.sRGB)!, intent: .defaultIntent,
            options: nil)
    else { return [] }
    return rgb.components ?? []
}

private func same(_ a: [CGFloat], _ b: [CGFloat]) -> Bool {
    a.count == b.count && zip(a, b).allSatisfy { abs($0 - $1) < 0.02 }
}

/// 演練流程（兩個版本共用）：先在淺色模式打開，再切到深色模式，比較邊框顏色
@MainActor
private func scenario(
    _ log: LabLog, border: (UIViewController) -> CGColor?,
    make: () -> UIViewController
) async {
    let vc = make()
    vc.overrideUserInterfaceStyle = .light
    let stage = SwiftUILabStage(viewController: vc)
    await stage.settle()
    let before = ObjectIdentifier(vc)
    log(
        "淺色模式：邊框是淺色版 \(same(components(border(vc)), expectedBorder(.light)) ? "是" : "否")"
    )
    // 等同使用者在控制中心切到深色模式
    vc.overrideUserInterfaceStyle = .dark
    await stage.settle()
    log(
        "切到深色模式後，畫面仍是同一個 ViewController：\(before == ObjectIdentifier(vc) ? "是" : "否")"
    )
    log(
        "切到深色模式後，邊框跟著變成深色版：\(same(components(border(vc)), expectedBorder(.dark)) ? "是" : "否")"
    )
    stage.remove()
}

// MARK: - Bug 版
private enum L03Bug {
    final class OrderCardViewController: UIViewController {
        let card = UIView()

        override func viewDidLoad() {
            super.viewDidLoad()
            card.frame = CGRect(x: 20, y: 40, width: 200, height: 80)
            card.layer.borderWidth = 1
            // TODO ①：把邊框顏色設成 UIColor.separator 轉成 cgColor
            // 的結果（只在 viewDidLoad 設這一次，Bug 版之後不更新）
            view.addSubview(card)
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(
            log,
            border: {
                ($0 as? OrderCardViewController)?.card.layer.borderColor
            }, make: { OrderCardViewController() })
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class OrderCardViewController: UIViewController {
        let card = UIView()

        override func viewDidLoad() {
            super.viewDidLoad()
            card.frame = CGRect(x: 20, y: 40, width: 200, height: 80)
            card.layer.borderWidth = 1
            view.addSubview(card)
            updateBorder()
            // TODO ②-2：用 registerForTraitChanges 註冊
            // UITraitUserInterfaceStyle，深淺色改變時再呼叫一次 updateBorder()
        }

        private func updateBorder() {
            // TODO ②-1：依目前的 traitCollection 解析出 UIColor.separator
            // 的顏色（resolvedColor），轉成 cgColor 設給 card.layer.borderColor
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(
            log,
            border: {
                ($0 as? OrderCardViewController)?.card.layer.borderColor
            }, make: { OrderCardViewController() })
    }
}

// MARK: - 演練設定
extension Platform01Lifecycle {
    static let l03 = Lab(
        id: "L03",
        title: "切到深色模式，邊框還是淺色的",
        question: "第 3 題：旋轉螢幕或切換深色模式時，兩個平台各發生什麼事？",
        goal:
            "切換深色模式時 iOS 不會重建 ViewController，只通知 trait 改變。卡片的邊框顏色要跟著系統的深淺色。先寫出邊框不跟著變的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform01-Lifecycle/L03_TraitChange.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「邊框跟著變成深色版」是不是「否」，並確認畫面是同一個 ViewController。",
            "想一想：為什麼 UIColor.separator 本身會跟著深淺色變，轉成 CGColor 設給 layer 就不會？",
            "寫 TODO ②（修正版），選「修正版」執行，確認邊框跟著變成深色版。",
        ],
        expectBug: "切到深色模式後，畫面仍是同一個 ViewController（是），但邊框跟著變成深色版：否。",
        expectFixed: "畫面仍是同一個 ViewController，而且邊框跟著變成深色版：是。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("淺色模式：邊框是淺色版 是") {
                return
                    "淺色模式下邊框應該是 UIColor.separator 的淺色版：要在 viewDidLoad 設定 layer.borderColor"
            }
            if !lines.contains("切到深色模式後，畫面仍是同一個 ViewController：是") {
                return "演練沒有跑完"
            }
            if lines.contains("切到深色模式後，邊框跟著變成深色版：是") {
                return
                    "Bug 還沒重現：Bug 版只在 viewDidLoad 把 UIColor.separator.cgColor 設給 layer，之後不更新"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("淺色模式：邊框是淺色版 是") {
                return "淺色模式下邊框應該是 UIColor.separator 的淺色版"
            }
            if !lines.contains("切到深色模式後，邊框跟著變成深色版：是") {
                return
                    "切到深色模式後邊框沒有變：用 registerForTraitChanges 在 userInterfaceStyle 改變時，用 resolvedColor(with: traitCollection) 重新設定"
            }
            return nil
        }
    )
}
