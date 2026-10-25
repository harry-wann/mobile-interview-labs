// L02：被 root／越獄的手機通過了檢查，
// 換一筆出金卻也能沿用同一份證明（環境檢查要交給伺服器，證明要綁定這次請求）
// 對應複習站：Advanced08Security 第 5 題「怎麼確認『這是正版 App、
// 跑在正常的裝置上』？」
//
// 情境：App 要在出金前讓伺服器知道「這台裝置是不是安全的」。演練有三台裝置，
// 全部是假的物件：
//       ① 正常的手機；② 被越獄的手機，攻擊者用 hook 把 App 裡的 isJailbroken()
// 改成永遠回傳 false；
//       ③ 攻擊者從正常手機的請求裡偷到一份證明，想拿去配另一筆金額更大的出金。
//       「平台」是假的 Apple／Google：它能看到裝置真正的狀態，
// 並用只有它和伺服器知道的金鑰，替證明簽章（對應 App Attest、Play Integrity）。
// Bug 版：App 自己判斷，把結果當成一個欄位（safe=true／false）送給伺服器，
// 伺服器照單全收。
//        hook 過的函式永遠說「安全」；而 safe=true
// 這個欄位可以搭配任何一筆請求。
// 修正版：App 向平台要一份證明，證明裡有「裝置是否安全」與一個
// nonce（這次請求內容的 SHA-256），整份由平台簽章。
//        伺服器驗證簽章、裝置狀態、nonce 是否等於這次請求的雜湊。hook
// 騙不了平台；證明也不能換一筆請求使用。
import CryptoKit
import Foundation
import LabKit

private struct Request {
    let amount: Int
    let to: String
    var canonical: String { "amount=\(amount);to=\(to)" }
    var hash: String {
        SHA256.hash(data: Data(canonical.utf8)).map {
            String(format: "%02x", $0)
        }.joined()
    }
}

private struct Device {
    let jailbroken: Bool  // 裝置真正的狀態
    // 攻擊者是否 hook 了 App 裡的偵測函式
    let hooked: Bool

    /// App 自己的偵測函式。被 hook 之後永遠回傳 false
    var appSaysJailbroken: Bool { hooked ? false : jailbroken }
}

/// 假的平台（Apple App Attest／Google Play Integrity）：看得到裝置真正的狀態，
/// 替證明簽章（兩個版本共用）
private enum FakePlatform {
    private static let key = SymmetricKey(
        data: Data("platform-secret-only-platform-and-server-know".utf8))

    private static func sign(_ text: String) -> String {
        Data(HMAC<SHA256>.authenticationCode(for: Data(text.utf8), using: key))
            .map { String(format: "%02x", $0) }.joined()
    }

    /// 簽發證明：trusted 是平台看到的真實狀態，nonce 是 App 要求綁定的值
    static func attest(device: Device, nonce: String) -> String {
        let body = "trusted=\(device.jailbroken ? 0 : 1);nonce=\(nonce)"
        return "\(body);sig=\(sign(body))"
    }

    /// 伺服器向平台解碼證明（對應伺服器呼叫 Google 的 API）：簽章正確才回傳內容
    static func decode(_ token: String) -> (trusted: Bool, nonce: String)? {
        guard let range = token.range(of: ";sig=") else { return nil }
        let body = String(token[..<range.lowerBound])
        guard sign(body) == String(token[range.upperBound...]) else {
            return nil
        }
        let fields = Dictionary(
            uniqueKeysWithValues: body.split(separator: ";").compactMap {
                part -> (String, String)? in
                let kv = part.split(separator: "=", maxSplits: 1).map(
                    String.init)
                return kv.count == 2 ? (kv[0], kv[1]) : nil
            })
        guard let trusted = fields["trusted"], let nonce = fields["nonce"]
        else { return nil }
        return (trusted == "1", nonce)
    }
}

/// 演練流程（兩個版本共用）：makeEvidence 是 App 端附上的證據，serverAccepts
/// 是伺服器怎麼判斷
private func scenario(
    _ log: LabLog,
    makeEvidence: (Request, Device) -> String,
    serverAccepts: (Request, String) -> Bool
) {
    func text(_ ok: Bool) -> String { ok ? "受理" : "拒絕" }
    let normal = Device(jailbroken: false, hooked: false)
    let compromised = Device(jailbroken: true, hooked: true)

    let small = Request(amount: 100_000, to: "0xAAAA")
    let evidenceOfNormal = makeEvidence(small, normal)
    log("正常的手機，出金 100000：\(text(serverAccepts(small, evidenceOfNormal)))")
    log(
        "被越獄且 hook 了偵測函式的手機，出金 100000：\(text(serverAccepts(small, makeEvidence(small, compromised))))"
    )

    // 攻擊者從正常手機的請求裡偷到證據，配上自己想要的另一筆請求
    let big = Request(amount: 9_000_000, to: "0xBBBB")
    log("拿正常手機的證明，配另一筆出金 9000000：\(text(serverAccepts(big, evidenceOfNormal)))")
}

// MARK: - Bug 版
private enum L02Bug {
    /// App 端附上的證據：自己偵測的結果
    static func makeEvidence(_ request: Request, _ device: Device) -> String {
        // 信任 App 自己的偵測函式
        "safe=\(!device.appSaysJailbroken)"
    }

    /// 伺服器的判斷
    static func serverAccepts(_ request: Request, _ evidence: String) -> Bool {
        // 信任 App 送來的欄位
        evidence == "safe=true"
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeEvidence: makeEvidence, serverAccepts: serverAccepts)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func makeEvidence(_ request: Request, _ device: Device) -> String {
        // 向平台要證明，nonce 綁定這次請求的內容
        FakePlatform.attest(device: device, nonce: request.hash)
    }

    static func serverAccepts(_ request: Request, _ evidence: String) -> Bool {
        // 簽章不對：偽造的
        guard let result = FakePlatform.decode(evidence) else { return false }
        // 裝置安全，而且是為這筆請求簽的
        return result.trusted && result.nonce == request.hash
    }

    static func run(_ log: LabLog) async {
        scenario(log, makeEvidence: makeEvidence, serverAccepts: serverAccepts)
    }
}

// MARK: - 演練設定
extension Advanced08Security {
    static let l02 = Lab(
        id: "L02",
        title: "越獄的手機通過檢查，證明還能換一筆用",
        question: "第 5 題：怎麼確認「這是正版 App、跑在正常的裝置上」？",
        goal: "環境偵測的結果不能由 App 說了算：要拿平台簽章的證明給伺服器驗證，而且證明要綁定這次請求（nonce）。",
        steps: [
            "選「Bug 版」執行，看被越獄且 hook 的手機、以及被偷走的證明，伺服器各怎麼回應。",
            "想一想：App 裡的 isJailbroken() 被 hook 之後會說什麼？伺服器收到的 safe=true 是誰說的？",
            "選「修正版」執行：證明由平台簽章，伺服器驗證簽章、裝置狀態，還要確認 nonce 等於這次請求的 SHA-256。",
            "進階：真正的做法是 Android 的 Play Integrity（nonce 或 request hash 綁定請求，伺服器呼叫 Google 解碼）與 iOS 的 App Attest（伺服器驗證憑證鏈與計數器）。兩邊都不要在 App 裡解析判定結果。",
        ],
        expectBug:
            "「被越獄且 hook 了偵測函式的手機，出金 100000：受理」，「拿正常手機的證明，配另一筆出金 9000000：受理」。",
        expectFixed:
            "正常的手機受理；被越獄的手機拒絕（平台簽的證明寫著 trusted=0，hook 改不了）；拿別人的證明配另一筆出金也拒絕（nonce 對不上）。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("正常的手機，出金 100000：受理") else {
                return "正常的手機應該被受理：App 附上 safe=true，伺服器接受"
            }
            if !lines.contains("被越獄且 hook 了偵測函式的手機，出金 100000：受理") {
                return "Bug 還沒重現：App 自己偵測、被 hook 的函式會回傳「不是越獄」，伺服器照單全收"
            }
            if !lines.contains("拿正常手機的證明，配另一筆出金 9000000：受理") {
                return "Bug 還沒重現：safe=true 這個欄位沒有綁定請求，換一筆出金也能用"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("正常的手機，出金 100000：受理") else {
                return
                    "正常的手機應該被受理：要向 FakePlatform.attest 要證明，nonce 用 request.hash"
            }
            if !lines.contains("被越獄且 hook 了偵測函式的手機，出金 100000：拒絕") {
                return "被越獄的手機應該被拒絕：平台簽的證明 trusted=0，伺服器要檢查 result.trusted"
            }
            if !lines.contains("拿正常手機的證明，配另一筆出金 9000000：拒絕") {
                return "換一筆出金應該被拒絕：伺服器要比對 result.nonce 是否等於這筆請求的 request.hash"
            }
            return nil
        }
    )
}
