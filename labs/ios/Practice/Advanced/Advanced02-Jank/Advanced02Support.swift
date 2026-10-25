// Advanced02 共用的小工具。型別與函式名稱都以 A02 開頭：同一個 module
// 裡還有其他單元，不能重名。
import UIKit

/// 從 log 找出以 prefix 開頭的第一行，取出後面接的數字
func a02Number(in lines: [String], prefix: String) -> Double? {
    guard let line = lines.first(where: { $0.hasPrefix(prefix) }) else {
        return nil
    }
    let digits = line.dropFirst(prefix.count).prefix {
        $0.isNumber || $0 == "." || $0 == "-"
    }
    return Double(digits)
}

/// 數「會觸發離屏渲染（offscreen rendering）」的圖層設定，和半透明（混合）
/// 的背景。
/// 規則就是複習站第 4 題列的那幾種，等於 Instruments 與模擬器的 Color
/// Offscreen-Rendered／Color Blended Layers 在找的東西。
@MainActor
struct A02LayerAudit {
    /// 圓角加 masksToBounds，而且圖層上有圖片或子 view 要一起裁切
    var maskedCorners = 0
    /// 有陰影卻沒有 shadowPath：系統得逐像素算出陰影的形狀
    var shadowWithoutPath = 0
    /// 使用 layer.mask
    var masks = 0
    /// shouldRasterize = true
    var rasterized = 0
    /// 卡片背景是半透明：GPU 要把背後的顏色混進來
    var blended = 0

    var offscreenTotal: Int {
        maskedCorners + shadowWithoutPath + masks + rasterized
    }

    static func audit(_ cards: [UIView]) -> A02LayerAudit {
        var result = A02LayerAudit()
        for card in cards {
            if !card.isOpaque || (card.backgroundColor?.cgColor.alpha ?? 0) < 1
            {
                result.blended += 1
            }
            result.visit(card)
        }
        return result
    }

    private mutating func visit(_ view: UIView) {
        let layer = view.layer
        let hasContent =
            layer.contents != nil || (view as? UIImageView)?.image != nil
            || !view.subviews.isEmpty
        if layer.masksToBounds && layer.cornerRadius > 0 && hasContent {
            maskedCorners += 1
        }
        if layer.shadowOpacity > 0 && layer.shadowPath == nil {
            shadowWithoutPath += 1
        }
        if layer.mask != nil { masks += 1 }
        if layer.shouldRasterize { rasterized += 1 }
        for sub in view.subviews { visit(sub) }
    }
}
