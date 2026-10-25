// L03：伺服器換金鑰那天，舊版 App 全部連不上（釘選至少要放兩個 pin）
// 對應複習站：Platform05Networking 第 3 題「伺服器的憑證要換的時候，
// 釘選怎麼輪替才不會讓使用者連不上？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在
// solution/platform/test/Platform05NetworkingTest.kt。
//
// 情境：App 3.19 上架時內建 pin。維運計畫在第 31 天，把伺服器換成預先產生、
// 一直離線保管的備援金鑰 B。
//        測試用 okhttp-tls 做出實驗用的根 CA，簽出金鑰 A、備援金鑰 B、
// 攻擊者金鑰 X 的憑證。
// Bug 版：pin 清單只放目前用的金鑰 A。伺服器換成金鑰 B 的那天，舊版 App 的 pin
// 全部對不上，所有人連不上，
//        唯一的解法是發新版、等使用者更新，這段時間不能下單。
// 修正版：pin 清單放兩個：目前的金鑰 A，以及備援金鑰 B。換金鑰之後 B 的 pin
// 對得上；攻擊者的金鑰 X 仍然被擋下。
package tw.harry.labs.solution.platform05networking

import java.security.cert.X509Certificate
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import tw.harry.labs.kit.Lab

/**
 * 用 pin 清單建立 client（共用）：system 是系統信任的 CA（測試裡是實驗用的根
 * CA）
 */
internal fun l03Client(
    pins: List<String>,
    host: String,
    system: L02Tls,
): OkHttpClient = OkHttpClient.Builder()
    .sslSocketFactory(system.socketFactory, system.trustManager)
    .certificatePinner(
        CertificatePinner.Builder().add(host, *pins.toTypedArray()).build(),
    )
    .build()

// MARK: - Bug 版
internal object L03Bug {
    /**
     * 發版時 App 內建的 pin；current：目前的金鑰 A，backup：預先產生的備援金鑰
     * B
     */
    fun shippedPins(
        current: X509Certificate,
        backup: X509Certificate,
    ): List<String> = // 只放目前正在用的金鑰 A
        listOf(CertificatePinner.pin(current))
}

// MARK: - 修正版
internal object L03Fixed {
    fun shippedPins(
        current: X509Certificate,
        backup: X509Certificate,
    ): List<String> = // 目前的金鑰 A，加上備援金鑰 B
        listOf(CertificatePinner.pin(current), CertificatePinner.pin(backup))
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "伺服器換金鑰那天，舊版 App 全部連不上",
    question = "第 3 題：伺服器的憑證要換的時候，釘選怎麼輪替才不會讓使用者連不上？",
    goal = "伺服器換成備援金鑰之後，已經上架的舊版 App 仍然連得上，攻擊者的金鑰仍然被擋下。pin 清單要放哪些金鑰？",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。終端機：cd labs/android && ./gradlew :solution-platform:testDebugUnitTest --tests '*Platform05NetworkingTest.l03' -i | grep SELFTEST（練習版把 solution 換成 practice）。",
        "看 Bug 版：「第 31 天，伺服器換成備援金鑰 B」連不連得上。",
        "想一想：這時候已經上架的 App 3.19 還能改嗎？要多久才能讓使用者更新？",
        "看修正版：換金鑰之後連得上、攻擊者仍然連不上。",
        "進階：Android 的 pin-set 還可以設 expiration，過期後釘選自動失效（fail-open），當作忘記更新時的保險；iOS 沒有這個機制。",
    ),
    expectBug = "App 內建 1 個 pin；第 1 天連得上；第 31 天換成備援金鑰 B 後丟出 SSLPeerUnverifiedException（連不上）。",
    expectFixed = "App 內建 2 個 pin；第 1 天、第 31 天都連得上；攻擊者的金鑰 X 丟出 SSLPeerUnverifiedException。",
    file = "platform05-networking/L03_SinglePin.kt",
)
