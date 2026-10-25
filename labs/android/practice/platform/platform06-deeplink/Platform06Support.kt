// Platform06DeepLink 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
package tw.harry.labs.practice.platform06deeplink

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 收集 Router 收到的連結（演練裡的 Router 只記下「收到什麼」） */
internal class LinkCollector {
    private val storage = mutableListOf<String>()
    fun handle(url: String) = synchronized(storage) { storage += url; Unit }
    val urls: List<String> get() = synchronized(storage) { storage.toList() }
}

/**
 * 假的錢包。真實的錢包用橢圓曲線簽章（secp256k1），伺服器只需要錢包地址就能驗證；
 * 演練用 HMAC 代替簽章，重點是「簽的訊息裡有什麼」，不是簽章演算法。
 */
internal object Wallet {
    const val ADDRESS = "0x1234abcd5678ef90"
    private val key =
        SecretKeySpec("wallet-private-key".toByteArray(), "HmacSHA256")

    fun sign(message: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return mac.doFinal(message.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    }

    /** 伺服器端的驗證：簽章是不是這個錢包對這段訊息簽的 */
    fun verify(message: String, signature: String): Boolean =
        sign(message) == signature
}

internal object Siwe {
    /** 從訊息裡取出「欄位名稱: 值」那一行的值，例如 field("Nonce", message) */
    fun field(name: String, message: String): String? =
        message.lines().firstOrNull {
            it.startsWith("$name: ")
        }?.removePrefix("$name: ")
}
