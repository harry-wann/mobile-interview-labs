// L02：100 張價格卡片一起捲動，GPU 一直在做離屏渲染（圓角加 masksToBounds、
// 沒有 shadowPath 的陰影、shouldRasterize）
// 對應複習站：Advanced02Jank 第 4 題「iOS 列表捲動卡頓的常見原因是什麼？」
//
// 情境：行情頁的每一格是一張價格卡片：圓角的卡片底、有陰影、一個圓形頭像、
// 一個每秒都在變的漲跌標籤。
//        render hitch（GPU 與 render server 超時）最常見的原因是離屏渲染：GPU
// 得先把內容畫到另一塊暫時的緩衝區，處理完再合成回來。
//        演練用 A02LayerAudit 數 100 張卡片的圖層設定（就是模擬器 Debug ›
// Color Offscreen-Rendered／Color Blended Layers 在找的條件）。
//
// 你要寫：
//   ① Bug 版 style：卡片背景半透明白色（alpha 0.95）、cornerRadius 12、
// 有陰影卻沒有 shadowPath、
//      頭像 cornerRadius 28 加 masksToBounds = true、漲跌標籤 shouldRasterize
// = true。
//   ② 修正版 style：卡片背景改成不透明的實色；陰影加上 shadowPath；
// 頭像預先裁成圓形（用寫好的 circleImage），不用 masksToBounds；標籤不開
// shouldRasterize。
//      圓角、陰影、頭像都要還在。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced02-Jank/L02_OffscreenAudit.swift
import SwiftUI
import UIKit
import LabKit

private let cardCount = 100

/// 價格卡片（兩個版本共用）：頭像、名稱、漲跌標籤。大小固定 300 × 80
@MainActor
private final class PriceCard: UIView {
    let avatar = UIImageView()
    let nameLabel = UILabel()
    let badge = UILabel()

    init() {
        super.init(frame: CGRect(x: 0, y: 0, width: 300, height: 80))
        avatar.frame = CGRect(x: 12, y: 12, width: 56, height: 56)
        avatar.image = sampleAvatar
        nameLabel.frame = CGRect(x: 80, y: 14, width: 130, height: 24)
        nameLabel.text = "BTC/JPY"
        badge.frame = CGRect(x: 218, y: 28, width: 70, height: 24)
        badge.text = "+1.2%"
        badge.textAlignment = .center
        addSubview(avatar)
        addSubview(nameLabel)
        addSubview(badge)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

/// 一張方形的頭像圖片（112 × 112 像素），兩個版本共用
@MainActor
private let sampleAvatar: UIImage = {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 2
    return UIGraphicsImageRenderer(
        size: CGSize(width: 56, height: 56), format: format
    ).image { context in
        UIColor.systemOrange.setFill()
        context.fill(CGRect(x: 0, y: 0, width: 56, height: 56))
    }
}()

/// 預先把圖片裁成圓形（修正版用）：裁切發生在畫圖的時候，之後 GPU
/// 拿到的就是一張現成、有透明角落的圖
@MainActor
private func circleImage(_ image: UIImage) -> UIImage {
    let format = UIGraphicsImageRendererFormat()
    format.scale = image.scale
    return UIGraphicsImageRenderer(size: image.size, format: format).image {
        _ in
        UIBezierPath(ovalIn: CGRect(origin: .zero, size: image.size)).addClip()
        image.draw(at: .zero)
    }
}

/// 演練流程（兩個版本共用）：建立 100 張卡片、套用樣式，數圖層設定
@MainActor
private func scenario(_ log: LabLog, style: (PriceCard) -> Void) {
    let cards = (0..<cardCount).map { _ -> PriceCard in
        let card = PriceCard()
        style(card)
        return card
    }
    let audit = A02LayerAudit.audit(cards)
    log(
        "\(cardCount) 張卡片，離屏渲染的觸發點：\(audit.offscreenTotal) 個（圓角加 masksToBounds \(audit.maskedCorners)、陰影沒有 shadowPath \(audit.shadowWithoutPath)、layer.mask \(audit.masks)、shouldRasterize \(audit.rasterized)）"
    )
    log("背景半透明（混合）的卡片：\(audit.blended) 張")
    if let first = cards.first {
        let hasPath = first.layer.shadowPath != nil ? "有" : "沒有"
        log(
            "第一張卡片：圓角 \(Int(first.layer.cornerRadius))、陰影 \(first.layer.shadowOpacity)、shadowPath \(hasPath)、頭像圖片 \(first.avatar.image == nil ? "沒有" : "有")"
        )
    }
}

// MARK: - Bug 版
private enum L02Bug {
    @MainActor
    static func style(_ card: PriceCard) {
        // TODO ①-1：card.backgroundColor 用半透明的白色（UIColor.white.
        // withAlphaComponent(0.95)）；card.layer.cornerRadius = 12
        // TODO ①-2：卡片的陰影：layer.shadowOpacity 0.25、shadowRadius 8、
        // shadowOffset (0, 2)，不要設 shadowPath
        // TODO ①-3：頭像 card.avatar.layer.cornerRadius = 28，加上
        // masksToBounds = true
        // TODO ①-4：漲跌標籤 card.badge.layer.shouldRasterize = true
        _ = card
    }

    @MainActor
    static func run(_ log: LabLog) async {
        scenario(log, style: style)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    @MainActor
    static func style(_ card: PriceCard) {
        // TODO ②-1：card.backgroundColor = white、card.isOpaque = true；card.
        // layer.cornerRadius = 12（不要 masksToBounds）
        // TODO ②-2：陰影一樣（0.25、8、(0, 2)），再加 card.layer.shadowPath =
        // UIBezierPath(roundedRect: card.bounds, cornerRadius: 12).cgPath
        // TODO ②-3：頭像預先裁成圓形：card.avatar.image = card.avatar.image.
        // map(circleImage)；不要設 cornerRadius、masksToBounds
        // TODO ②-4：漲跌標籤不要開 shouldRasterize
        _ = card
    }

    @MainActor
    static func run(_ log: LabLog) async {
        scenario(log, style: style)
    }
}

/// 畫面操作區：把 30 張卡片排成可以捲動的列表。在模擬器選單 Debug › Color
/// Offscreen-Rendered 打勾，Bug 版的卡片會蓋上黃色
private struct L02Demo: UIViewRepresentable {
    let style: @MainActor (PriceCard) -> Void

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.backgroundColor = UIColor.systemGray5
        for i in 0..<30 {
            let card = PriceCard()
            style(card)
            card.frame.origin = CGPoint(x: 12, y: 12 + CGFloat(i) * 92)
            scroll.addSubview(card)
        }
        scroll.contentSize = CGSize(width: 324, height: 12 + 30 * 92)
        return scroll
    }

    func updateUIView(_ uiView: UIScrollView, context: Context) {}
}

// MARK: - 演練設定
extension Advanced02Jank {
    static let l02 = Lab(
        id: "L02",
        title: "100 張卡片一起捲動，GPU 一直在做離屏渲染",
        question: "第 4 題：iOS 列表捲動卡頓的常見原因是什麼？",
        goal:
            "改掉會觸發離屏渲染與混合的圖層設定，圓角與陰影的樣子保留。用圖層設定的數字確認（等於 Instruments 與模擬器在找的東西）。先寫出會離屏渲染的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced02-Jank/L02_OffscreenAudit.swift，寫 TODO ①。",
            "選「練習」「Bug 版」按「執行」，看第一行 log：離屏渲染的觸發點 300 個，由圓角加 masksToBounds、陰影沒有 shadowPath、shouldRasterize 各 100 個組成；第二行：100 張卡片是半透明背景。",
            "寫 TODO ②，選「修正版」按「執行」：觸發點與混合都是 0，第一張卡片的圓角 12、陰影 0.25、shadowPath 有、頭像還在。",
            "在畫面上看：App 在模擬器執行，選單列 Debug › Color Offscreen-Rendered 打勾，捲動操作區的卡片。Bug 版的卡片會蓋上黃色，修正版沒有；再勾 Color Blended Layers，Bug 版的卡片底會變紅色。",
            "進階：Instruments 選 Animation Hitches 範本，在實機錄製捲動，看 hitch 是 commit hitch（App 的主執行緒）還是 render hitch（GPU 與 render server）；離屏渲染造成的是 render hitch。",
        ],
        expectBug:
            "100 張卡片，離屏渲染的觸發點：300 個（圓角加 masksToBounds 100、陰影沒有 shadowPath 100、layer.mask 0、shouldRasterize 100）；背景半透明（混合）的卡片：100 張。",
        expectFixed:
            "離屏渲染的觸發點：0 個；背景半透明（混合）的卡片：0 張；第一張卡片：圓角 12、陰影 0.25、shadowPath 有、頭像圖片 有。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug:
                AnyView(L02Demo(style: { L02Bug.style($0) }).frame(height: 360))
            case .fixed:
                AnyView(
                    L02Demo(style: { L02Fixed.style($0) }).frame(height: 360))
            }
        },
        checkBug: { lines in
            guard let total = a02Number(in: lines, prefix: "100 張卡片，離屏渲染的觸發點：")
            else { return "演練沒有跑完" }
            if total < 100 {
                return
                    "離屏渲染的觸發點只有 \(Int(total)) 個：Bug 版要有陰影沒有 shadowPath、頭像 cornerRadius 加 masksToBounds、標籤 shouldRasterize"
            }
            guard
                lines.contains(where: { $0.contains("圓角加 masksToBounds 100") })
            else {
                return "頭像要設 layer.cornerRadius 加 layer.masksToBounds = true"
            }
            guard lines.contains(where: { $0.contains("陰影沒有 shadowPath 100") })
            else {
                return "卡片要有陰影（shadowOpacity、shadowRadius），而且不要設 shadowPath"
            }
            guard lines.contains(where: { $0.contains("shouldRasterize 100") })
            else { return "標籤要設 layer.shouldRasterize = true" }
            let blended = a02Number(in: lines, prefix: "背景半透明（混合）的卡片：") ?? 0
            return blended >= 100 ? nil : "卡片背景要是半透明的顏色（alpha 小於 1），才會被數成混合"
        },
        checkFixed: { lines in
            guard let total = a02Number(in: lines, prefix: "100 張卡片，離屏渲染的觸發點：")
            else { return "演練沒有跑完" }
            if total > 0 {
                return
                    "還有 \(Int(total)) 個離屏渲染的觸發點：\(lines.first { $0.hasPrefix("100 張卡片") } ?? "")"
            }
            if (a02Number(in: lines, prefix: "背景半透明（混合）的卡片：") ?? 1) > 0 {
                return
                    "卡片背景還是半透明的：改成不透明的實色（backgroundColor = .white、isOpaque = true）"
            }
            guard let first = lines.first(where: { $0.hasPrefix("第一張卡片：") })
            else { return "演練沒有跑完" }
            if !first.contains("圓角 12") {
                return "卡片的圓角要保留（cornerRadius = 12）：\(first)"
            }
            if first.contains("陰影 0.0、") {
                return "卡片的陰影要保留（shadowOpacity 大於 0），再加上 shadowPath：\(first)"
            }
            if !first.contains("shadowPath 有") {
                return "陰影要加上 shadowPath：\(first)"
            }
            if !first.contains("頭像圖片 有") {
                return "頭像圖片不能拿掉：預先把圓形裁在圖片上（card.avatar.image = …）：\(first)"
            }
            return nil
        }
    )
}
