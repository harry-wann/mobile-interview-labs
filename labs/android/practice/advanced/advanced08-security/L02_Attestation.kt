// L02：被 root 的手機通過了檢查，
// 換一筆出金卻也能沿用同一份證明（環境檢查要交給伺服器，證明要綁定這次請求）
// 對應複習站：Advanced08Security 第 5 題「怎麼確認『這是正版 App、
// 跑在正常的裝置上』？」
//
// 情境：App 要在出金前讓伺服器知道「這台裝置是不是安全的」。演練有三台裝置，
// 全部是假的物件：
//       ① 正常的手機；② 被 root 的手機，攻擊者用 hook 把 App 裡的 isRooted()
// 改成永遠回傳 false（Magisk 的 DenyList 也能達到類似的效果）；
//       ③ 攻擊者從正常手機的請求裡偷到一份證明，想拿去配另一筆金額更大的出金。
//       「平台」是假的 Google：它能看到裝置真正的狀態，
// 並用只有它和伺服器知道的金鑰，替證明簽章（對應 Play Integrity）。
//
// 你要寫：
//   ① Bug 版：App 自己偵測，把結果當成欄位（safe=true／false）送出，
// 伺服器照單全收。被 hook 的函式永遠說安全，欄位也能配任何一筆請求。
//   ② 修正版：向平台要證明（nonce 綁定這次請求的 SHA-256）；伺服器驗證簽章、
// 裝置狀態、nonce。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced08-security/L02_Attestation.kt
package tw.harry.labs.practice.advanced08security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

private class L02Request(val amount: Int, val to: String) {
    val canonical get() = "amount=$amount;to=$to"
    val hash get() = MessageDigest.getInstance(
        "SHA-256",
    ).digest(canonical.toByteArray()).hex()
}

private class L02Device(
    val rooted: Boolean, // 裝置真正的狀態
    // 攻擊者是否 hook 了 App 裡的偵測函式
    val hooked: Boolean,
) {
    /** App 自己的偵測函式。被 hook 之後永遠回傳 false */
    val appSaysRooted get() = if (hooked) false else rooted
}

/**
 * 假的平台（Play Integrity）：看得到裝置真正的狀態，替證明簽章（兩個版本共用）
 */
private object L02FakePlatform {
    private val key =
        SecretKeySpec(
            "platform-secret-only-platform-and-server-know".toByteArray(),
            "HmacSHA256",
        )

    private fun sign(text: String): String = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(text.toByteArray()).hex()
    }

    /** 簽發證明：trusted 是平台看到的真實狀態，nonce 是 App 要求綁定的值 */
    fun attest(device: L02Device, nonce: String): String {
        val body = "trusted=${if (device.rooted) 0 else 1};nonce=$nonce"
        return "$body;sig=${sign(body)}"
    }

    class Verdict(val trusted: Boolean, val nonce: String)

    /**
     * 伺服器向平台解碼證明（對應伺服器呼叫 Google 的 API）：簽章正確才回傳內容
     */
    fun decode(token: String): Verdict? {
        val index = token.indexOf(";sig=")
        if (index < 0) return null
        val body = token.substring(0, index)
        if (sign(body) != token.substring(index + ";sig=".length)) return null
        val fields = body.split(";").mapNotNull { part ->
            part.split("=", limit = 2).takeIf {
                it.size ==
                    2
            }?.let { it[0] to it[1] }
        }.toMap()
        return Verdict(fields["trusted"] == "1", fields["nonce"] ?: return null)
    }
}

/**
 * 演練流程（兩個版本共用）：makeEvidence 是 App 端附上的證據，serverAccepts
 * 是伺服器怎麼判斷
 */
private fun scenario(
    log: LabLog,
    makeEvidence: (L02Request, L02Device) -> String,
    serverAccepts: (L02Request, String) -> Boolean,
) {
    fun text(ok: Boolean) = if (ok) "受理" else "拒絕"
    val normal = L02Device(rooted = false, hooked = false)
    val compromised = L02Device(rooted = true, hooked = true)

    val small = L02Request(100_000, "0xAAAA")
    val evidenceOfNormal = makeEvidence(small, normal)
    log("正常的手機，出金 100000：${text(serverAccepts(small, evidenceOfNormal))}")
    log(
        "被 root 且 hook 了偵測函式的手機，出金 100000：${text(
            serverAccepts(small, makeEvidence(small, compromised)),
        )}",
    )

    // 攻擊者從正常手機的請求裡偷到證據，配上自己想要的另一筆請求
    val big = L02Request(9_000_000, "0xBBBB")
    log("拿正常手機的證明，配另一筆出金 9000000：${text(serverAccepts(big, evidenceOfNormal))}")
}

// MARK: - Bug 版
private object L02Bug {
    /** App 端附上的證據：自己偵測的結果 */
    fun makeEvidence(request: L02Request, device: L02Device): String {
        // TODO ①-1：回傳字串 "safe=" 加上 App 自己偵測的結果：device.
        // appSaysRooted 的相反（沒 root 就是 true）
        return ""
    }

    /** 伺服器的判斷 */
    fun serverAccepts(request: L02Request, evidence: String): Boolean {
        // TODO ①-2：伺服器直接信任 App 送來的欄位：evidence 等於 "safe=true"
        // 就受理
        return false
    }

    suspend fun run(log: LabLog) =
        scenario(log, ::makeEvidence, ::serverAccepts)
}

// MARK: - 修正版
private object L02Fixed {
    fun makeEvidence(request: L02Request, device: L02Device): String {
        // TODO ②-1：向平台要證明：L02FakePlatform.attest(device, nonce = …)，
        // nonce 用 request.hash（綁定這次請求的內容）
        return ""
    }

    fun serverAccepts(request: L02Request, evidence: String): Boolean {
        // TODO ②-2：用 L02FakePlatform.decode(evidence) 解碼；
        // 解不開（簽章不對）就拒絕。
        //           解得開時，要同時滿足：result.trusted 為 true，而且 result.
        // nonce 等於 request.hash
        return false
    }

    suspend fun run(log: LabLog) =
        scenario(log, ::makeEvidence, ::serverAccepts)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "root 的手機通過檢查，證明還能換一筆用",
    question = "第 5 題：怎麼確認「這是正版 App、跑在正常的裝置上」？",
    goal = "環境偵測的結果不能由 App 說了算：要拿平台簽章的證明給伺服器驗證，而且證明要綁定這次請求（nonce）。 先寫出信任 App 自己判斷的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced08-security/L02_Attestation.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看被 root 且 hook 的手機、以及被偷走的證明，伺服器各怎麼回應。",
        "想一想：App 裡的 isRooted() 被 hook 之後會說什麼？伺服器收到的 safe=true 是誰說的？",
        "寫 TODO ②，選「修正版」執行：證明由平台簽章，伺服器驗證簽章、裝置狀態，還要確認 nonce 等於這次請求的 SHA-256。",
        "進階：真正的做法是 Android 的 Play Integrity（nonce 或 request hash 綁定請求，伺服器呼叫 Google 解碼）與 iOS 的 App Attest（伺服器驗證憑證鏈與計數器）。兩邊都不要在 App 裡解析判定結果。",
    ),
    expectBug = "「被 root 且 hook 了偵測函式的手機，出金 100000：受理」，「拿正常手機的證明，配另一筆出金 9000000：受理」。",
    expectFixed = "正常的手機受理；被 root 的手機拒絕（平台簽的證明寫著 trusted=0，hook 改不了）；拿別人的證明配另一筆出金也拒絕（nonce 對不上）。",
    file = "advanced08-security/L02_Attestation.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "正常的手機，出金 100000：受理" !in lines -> "正常的手機應該被受理：App 附上 safe=true，伺服器接受"
            "被 root 且 hook 了偵測函式的手機，出金 100000：受理" !in lines -> "Bug 還沒重現：App 自己偵測、被 hook 的函式會回傳「沒有 root」，伺服器照單全收"
            "拿正常手機的證明，配另一筆出金 9000000：受理" !in lines -> "Bug 還沒重現：safe=true 這個欄位沒有綁定請求，換一筆出金也能用"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "正常的手機，出金 100000：受理" !in lines -> "正常的手機應該被受理：要向 L02FakePlatform.attest 要證明，nonce 用 request.hash"
            "被 root 且 hook 了偵測函式的手機，出金 100000：拒絕" !in lines -> "被 root 的手機應該被拒絕：平台簽的證明 trusted=0，伺服器要檢查 result.trusted"
            "拿正常手機的證明，配另一筆出金 9000000：拒絕" !in lines -> "換一筆出金應該被拒絕：伺服器要比對 result.nonce 是否等於這筆請求的 request.hash"
            else -> null
        }
    },
)
