// L02：憑證自動續期那天，所有使用者都連不上（要釘公鑰，不要只信任那一張憑證）
// 對應複習站：Platform05Networking 第 2 題「什麼是憑證釘選（certificate pinning）？兩個平台怎麼設定？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在 solution/platform/test/Platform05NetworkingTest.kt。
//
// 情境：App 要確保只連到自己的伺服器。伺服器的憑證每 90 天自動續期：換成新的一張憑證，背後的金鑰沒換。
//        測試用 okhttp-tls 做出一個實驗用的根 CA（代表手機信任的 CA），簽出三張憑證：
//        目前的憑證、續期後的憑證（同一把金鑰）、攻擊者的憑證（另一把金鑰，但也是同一個 CA 簽的）。
// Bug 版：把 App 內附的那張憑證檔案當成唯一信任的憑證（像把 .crt 放進 res/raw、建一個只含它的 KeyStore）。
//        續期後伺服器換成新的一張憑證，不是 App 內附的那一張，所有使用者連不上。
// 修正版：沿用系統信任的 CA，再用 CertificatePinner 釘公鑰（SPKI 的 SHA-256）。續期只換憑證、沒換金鑰，pin 不變；
//        攻擊者的憑證金鑰不同，pin 對不上，仍然被擋下。
package tw.harry.labs.solution.platform05networking

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import tw.harry.labs.kit.Lab

/** 一組 SSL 設定：socket 工廠加上它對應的 TrustManager */
internal class L02Tls(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

/** 建一個「只信任這一張憑證」的 TLS 設定（共用的樣板程式，和 bug 無關） */
internal fun l02TrustOnly(certificate: X509Certificate): L02Tls {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null)
        setCertificateEntry("api", certificate)
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
    val trustManager = factory.trustManagers.single() as X509TrustManager
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return L02Tls(context.socketFactory, trustManager)
}

// MARK: - Bug 版
internal object L02Bug {
    /** bundled：App 內附的那張憑證（續期前的憑證檔案） */
    fun client(bundled: X509Certificate, host: String, system: L02Tls): OkHttpClient {
        val only = l02TrustOnly(bundled)                // 只信任內附的那一張憑證檔案
        return OkHttpClient.Builder()
            .sslSocketFactory(only.socketFactory, only.trustManager)
            .build()
    }
}

// MARK: - 修正版
internal object L02Fixed {
    /** system：系統信任的 CA（測試裡是實驗用的根 CA）；bundled：用來算 pin 的那張憑證 */
    fun client(bundled: X509Certificate, host: String, system: L02Tls): OkHttpClient =
        OkHttpClient.Builder()
            .sslSocketFactory(system.socketFactory, system.trustManager)        // 沿用系統信任的 CA
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(host, CertificatePinner.pin(bundled))                  // 釘公鑰（SPKI）的 SHA-256，不釘憑證檔案
                    .build()
            )
            .build()
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "憑證續期那天，所有使用者都連不上",
    question = "第 2 題：什麼是憑證釘選（certificate pinning）？兩個平台怎麼設定？",
    goal = "伺服器憑證續期（換憑證、不換金鑰）後，App 仍然連得上；攻擊者的憑證（另一把金鑰）仍然被擋下。信任的依據要放在哪裡？",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。終端機：cd labs/android && ./gradlew :solution-platform:testDebugUnitTest --tests '*Platform05NetworkingTest.l02' -i | grep SELFTEST（練習版把 solution 換成 practice）。",
        "看 Bug 版：「續期後的伺服器」連不連得上。",
        "想一想：續期只是換了一張憑證，伺服器背後的金鑰有沒有變？App 信任的是「那一張憑證檔案」，還是「那把金鑰」？",
        "看修正版：續期後連得上、攻擊者仍然連不上。",
        "進階：Android 的 pin 也可以寫在 Network Security Config 的 pin-set；OkHttp 的 CertificatePinner.pin(憑證) 算出來的就是 sha256/ 開頭的那個值。",
    ),
    expectBug = "續期前連得上；續期後丟出 SSLHandshakeException（連不上）；攻擊者的憑證也連不上。",
    expectFixed = "續期前、續期後都連得上；攻擊者的憑證丟出 SSLPeerUnverifiedException（Certificate pinning failure）。",
    file = "platform05-networking/L02_PinWholeCert.kt",
)
