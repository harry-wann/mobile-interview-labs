// L05：伺服器發的靜默推播被 APNs 拒絕，App 永遠沒有被喚醒（silent push
// 的標頭與 payload）
// 對應複習站：Platform03BackgroundPush 第 5 題「APNs 和 FCM
// 的訊息有哪幾種？優先權、collapse、TTL 是什麼？」
//
// 情境：伺服器想用靜默推播（不顯示通知，只喚醒 App 約 30 秒）叫 App
// 在背景重新整理資產。你要寫的是 buildSilentPush()：
//        組出送給 APNs 的標頭（headers）與內容（payload）。FakeAPNs 依 Apple
// 文件上的規則檢查：
//        apns-push-type 要和內容相符；push-type 是 background 時 apns-priority
// 一定要是 5；內容的 aps 裡要有 content-available: 1，
//        而且不能有 alert、sound、badge；整份內容不超過 4 KB。（真的 APNs
// 要用憑證或 token 連線，演練只檢查你組出來的東西。）
// Bug 版：複製價格提醒（alert 推播）的寫法：priority 10、帶 alert 和 sound，
// 再加一個 content-available。APNs 的規則不接受。
// 修正版：push-type 是 background、priority 是 5、aps 只有 content-available:
// 1，其他資料放在 aps 外面。
import Foundation
import LabKit

/// 要送給 APNs 的一個請求（兩個版本共用）
private struct PushRequest {
    var headers: [String: String]
    var payload: [String: Any]
}

/// 假的 APNs（兩個版本共用）：依文件上的規則檢查請求，回傳 nil 代表接受，
/// 否則是拒絕的原因
private enum FakeAPNs {
    static func rejection(for request: PushRequest) -> String? {
        guard let type = request.headers["apns-push-type"] else {
            return "MissingPushType：iOS 13 以上一定要有 apns-push-type"
        }
        let aps = request.payload["aps"] as? [String: Any] ?? [:]
        let size =
            (try? JSONSerialization.data(withJSONObject: request.payload).count)
            ?? 0
        if size > 4_096 { return "PayloadTooLarge：內容 \(size) bytes，上限是 4096" }
        switch type {
        case "background":
            if request.headers["apns-priority"] != "5" {
                return "BadPriority：background 的 apns-priority 必須是 5"
            }
            if aps["content-available"] as? Int != 1 {
                return "BadPayload：background 的 aps 要有 content-available: 1"
            }
            if aps["alert"] != nil || aps["sound"] != nil || aps["badge"] != nil
            {
                return "BadPayload：background 的 aps 不能有 alert、sound、badge"
            }
            return nil
        case "alert":
            return aps["alert"] == nil ? "BadPayload：alert 的 aps 要有 alert" : nil
        default:
            return "BadPushType：\(type)"
        }
    }
}

/// 演練流程（兩個版本共用）：組出請求，交給 FakeAPNs 檢查
private func scenario(_ log: LabLog, build: () -> PushRequest) {
    let request = build()
    log(
        "apns-push-type：\(request.headers["apns-push-type"] ?? "（沒有）")、apns-priority：\(request.headers["apns-priority"] ?? "（沒有）")"
    )
    if let reason = FakeAPNs.rejection(for: request) {
        log("APNs 回應：拒絕，\(reason)")
    } else {
        log("APNs 回應：接受，App 會被喚醒約 30 秒（系統仍可能節流）")
    }
}

// MARK: - Bug 版
private enum L05Bug {
    /// 組出「叫 App 在背景重新整理資產」的請求
    static func buildSilentPush() -> PushRequest {
        PushRequest(
            // 想要「立刻」送到，用了 10
            headers: ["apns-push-type": "background", "apns-priority": "10"],
            // 複製 alert 推播的內容
            payload: [
                "aps": [
                    "alert": "資產更新", "sound": "default", "content-available": 1,
                ]
            ]
        )
    }

    static func run(_ log: LabLog) async {
        scenario(log, build: buildSilentPush)
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func buildSilentPush() -> PushRequest {
        PushRequest(
            // background 一定是 5
            headers: ["apns-push-type": "background", "apns-priority": "5"],
            // aps 只有 content-available，自己的資料放外面
            payload: ["aps": ["content-available": 1], "refresh": "portfolio"]
        )
    }

    static func run(_ log: LabLog) async {
        scenario(log, build: buildSilentPush)
    }
}

// MARK: - 演練設定
extension Platform03BackgroundPush {
    static let l05 = Lab(
        id: "L05",
        title: "靜默推播被拒絕，App 沒有被喚醒",
        question: "第 5 題：APNs 和 FCM 的訊息有哪幾種？優先權、collapse、TTL 是什麼？",
        goal:
            "組出一個 APNs 接受的靜默推播：push-type background、priority 5、aps 只有 content-available: 1。",
        steps: [
            "選「Bug 版」按執行，看 FakeAPNs 給的拒絕原因。",
            "選「修正版」執行：被接受。",
            "想一想：靜默推播為什麼不能拿來做準時的價格提醒？（系統會依電量節流、強制關閉 App 後不會喚醒；需要準時要發 alert 推播）",
        ],
        expectBug: "APNs 回應：拒絕，BadPriority：background 的 apns-priority 必須是 5。",
        expectFixed: "APNs 回應：接受，App 會被喚醒約 30 秒（系統仍可能節流）。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("APNs 回應：") }) else {
                return "演練沒有跑完"
            }
            if !lines.contains("apns-push-type：background、apns-priority：10") {
                return
                    "Bug 版想發的是靜默推播：headers 要有 apns-push-type 是 background、apns-priority 是 10"
            }
            if lines.contains(where: { $0.hasPrefix("APNs 回應：接受") }) {
                return
                    "Bug 還沒重現：Bug 版要複製 alert 推播的寫法（priority 10、帶 alert 和 sound），APNs 才會拒絕"
            }
            return nil
        },
        checkFixed: { lines in
            guard
                let response = lines.first(where: { $0.hasPrefix("APNs 回應：") })
            else { return "演練沒有跑完" }
            return response.hasPrefix("APNs 回應：接受")
                ? nil : "\(response)：照 FakeAPNs 的規則修正 headers 和 payload"
        }
    )
}
