package tw.harry.labs.solution

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.harry.labs.solution.platform05networking.L01Bug
import tw.harry.labs.solution.platform05networking.L01Fixed
import tw.harry.labs.solution.platform05networking.L02Bug
import tw.harry.labs.solution.platform05networking.L02Fixed
import tw.harry.labs.solution.platform05networking.L02Tls
import tw.harry.labs.solution.platform05networking.L03Bug
import tw.harry.labs.solution.platform05networking.L03Fixed
import tw.harry.labs.solution.platform05networking.L04Bug
import tw.harry.labs.solution.platform05networking.L04Fixed
import tw.harry.labs.solution.platform05networking.l03Client

/**
 * Platform05Networking L01–L04：用 MockWebServer（本機假伺服器）驗證 OkHttp 的行為（解答：Bug 版要重現問題、修正版要正確）。
 *
 * 在 Android Studio 點測試旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。L05–L07 在 App 裡按「執行」。
 */
class Platform05NetworkingTest {
    private fun report(lab: String, variant: String, reason: String?, detail: String, failures: MutableList<String>) {
        if (reason == null) {
            println("[SELFTEST] Platform05Networking $lab $variant PASS　$detail")
        } else {
            println("[SELFTEST] Platform05Networking $lab $variant FAIL：$reason")
            failures += "$lab $variant：$reason"
        }
    }

    // ---------------------------------------------------------------- L01：逾時

    private class Download(val ok: Boolean, val ms: Long, val detail: String)

    private fun download(client: OkHttpClient): Download {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(12)).throttleBody(1, 200, TimeUnit.MILLISECONDS))   // 每 200 ms 送 1 個位元組
        server.start()
        val start = System.nanoTime()
        try {
            val size = client.newCall(Request.Builder().url(server.url("/statement")).build()).execute().use { it.body!!.string().length }
            return Download(true, (System.nanoTime() - start) / 1_000_000, "收到 $size 個位元組")
        } catch (e: IOException) {
            return Download(false, (System.nanoTime() - start) / 1_000_000, e.javaClass.simpleName)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun l01() {
        val failures = mutableListOf<String>()

        val bugClient = L01Bug.client()
        val b = download(bugClient)
        val bugReason = when {
            bugClient.readTimeoutMillis != 1_000 -> "Bug 版要設 readTimeout(1, TimeUnit.SECONDS)"
            bugClient.callTimeoutMillis != 0 -> "Bug 版不要設 callTimeout（預設不限制）"
            !b.ok -> "Bug 還沒重現：伺服器一直有資料送來，只設 readTimeout 時，下載應該不會逾時（結果 ${b.detail}）"
            b.ms < 2_000 -> "Bug 版應該等滿約 2200 ms 才完成（實際 ${b.ms} ms）"
            else -> null
        }
        report("L01", "Bug 版", bugReason, "沒有逾時：${b.detail}，花了 ${b.ms} ms", failures)

        val fixedClient = L01Fixed.client()
        val f = download(fixedClient)
        val fixedReason = when {
            fixedClient.callTimeoutMillis != 1_000 -> "修正版要加上 callTimeout(1, TimeUnit.SECONDS)"
            f.ok -> "下載應該在 1 秒左右逾時，但它完成了（花了 ${f.ms} ms）"
            f.ms >= 1_800 -> "逾時太晚了（${f.ms} ms）：callTimeout 應該設成 1 秒"
            else -> null
        }
        report("L01", "修正版", fixedReason, "逾時（${f.detail}），花了 ${f.ms} ms", failures)

        assertEquals(emptyList<String>(), failures)
    }

    // ---------------------------------------------------------------- L02、L03：憑證釘選

    /** 實驗用的憑證機構（代表手機信任的 CA），可以簽出 localhost 的憑證 */
    private class Pki {
        val root: HeldCertificate = HeldCertificate.Builder().certificateAuthority(0).commonName("Lab Root CA").build()

        /** 系統信任的 CA：只信任這個根 CA */
        val system: L02Tls = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
            .let { L02Tls(it.sslSocketFactory(), it.trustManager) }

        /** 簽出 localhost 的憑證；sameKeyAs 不是 null 時沿用那張憑證的金鑰（模擬「續期」：新憑證、同一把金鑰） */
        fun leaf(serial: Long, sameKeyAs: HeldCertificate? = null): HeldCertificate {
            val builder = HeldCertificate.Builder()
                .commonName("localhost").addSubjectAlternativeName("localhost")
                .serialNumber(serial).signedBy(root)
            if (sameKeyAs != null) builder.keyPair(sameKeyAs.keyPair) else builder.ecdsa256()
            return builder.build()
        }
    }

    /** 用 certificate 當伺服器的憑證，起一個 HTTPS 的 MockWebServer，再用 client 連一次 */
    private fun connect(pki: Pki, client: OkHttpClient, certificate: HeldCertificate): String? {
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(certificate, pki.root.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        val url = HttpUrl.Builder().scheme("https").host("localhost").port(server.port).build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
            return null                                     // 連得上
        } catch (e: IOException) {
            return e.javaClass.simpleName                   // 連不上：回傳例外名稱
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun l02() {
        val failures = mutableListOf<String>()
        val pki = Pki()
        val a1 = pki.leaf(serial = 1001)                              // 續期前的憑證
        val a2 = pki.leaf(serial = 1002, sameKeyAs = a1)              // 續期後：新的一張憑證，同一把金鑰
        val attacker = pki.leaf(serial = 3001)                        // 攻擊者：另一把金鑰，但也是同一個 CA 簽的

        val bugClient = L02Bug.client(a1.certificate, "localhost", pki.system)
        val bug = Triple(connect(pki, bugClient, a1), connect(pki, bugClient, a2), connect(pki, bugClient, attacker))
        val bugReason = when {
            bug.first != null -> "續期前的伺服器應該連得上：Bug 版只信任內附的憑證 a1（l02TrustOnly(bundled)），伺服器用的正是 a1"
            bug.second == null -> "Bug 還沒重現：只信任內附的那一張憑證，續期後換了新的一張，應該連不上"
            bug.third == null -> "攻擊者的憑證應該被擋下"
            else -> null
        }
        report("L02", "Bug 版", bugReason, "續期前連得上；續期後連不上（${bug.second}）；攻擊者連不上（${bug.third}）", failures)

        val fixedClient = L02Fixed.client(a1.certificate, "localhost", pki.system)
        val fixed = Triple(connect(pki, fixedClient, a1), connect(pki, fixedClient, a2), connect(pki, fixedClient, attacker))
        val fixedReason = when {
            fixed.first != null || fixed.second != null ->
                "續期前、續期後都要連得上：沿用系統信任的 CA（system），再用 CertificatePinner.pin(bundled) 釘公鑰（續期前連線結果 ${fixed.first}，續期後 ${fixed.second}）"
            fixed.third == null -> "攻擊者的憑證（另一把金鑰）應該被 CertificatePinner 擋下"
            else -> null
        }
        report("L02", "修正版", fixedReason, "續期前、續期後都連得上；攻擊者連不上（${fixed.third}）", failures)

        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun l03() {
        val failures = mutableListOf<String>()
        val pki = Pki()
        val keyA = pki.leaf(serial = 1001)                            // 目前用的金鑰 A
        val keyB = pki.leaf(serial = 2001)                            // 預先產生的備援金鑰 B
        val keyX = pki.leaf(serial = 3001)                            // 攻擊者的金鑰 X

        val bugPins = L03Bug.shippedPins(keyA.certificate, keyB.certificate)
        val bugClient = l03Client(bugPins, "localhost", pki.system)
        val bug = Triple(connect(pki, bugClient, keyA), connect(pki, bugClient, keyB), connect(pki, bugClient, keyX))
        val bugReason = when {
            bugPins.size != 1 -> "Bug 版只放目前用的金鑰 A 一個 pin（共 ${bugPins.size} 個）"
            bug.first != null -> "金鑰 A 的 pin 要放進清單，第 1 天才連得上"
            bug.second == null -> "Bug 還沒重現：清單裡沒有 B 的 pin，換金鑰後應該連不上"
            else -> null
        }
        report("L03", "Bug 版", bugReason, "內建 1 個 pin；金鑰 A 連得上；換成備援金鑰 B 連不上（${bug.second}）", failures)

        val fixedPins = L03Fixed.shippedPins(keyA.certificate, keyB.certificate)
        val fixedClient = l03Client(fixedPins, "localhost", pki.system)
        val fixed = Triple(connect(pki, fixedClient, keyA), connect(pki, fixedClient, keyB), connect(pki, fixedClient, keyX))
        val fixedReason = when {
            fixedPins.size != 2 -> "清單要放兩個 pin：目前的金鑰 A，以及備援金鑰 B（目前 ${fixedPins.size} 個）"
            fixed.first != null || fixed.second != null -> "金鑰 A、備援金鑰 B 的 pin 都要放進清單，換金鑰前後都要連得上"
            fixed.third == null -> "攻擊者的金鑰 X 不在清單裡，應該被擋下"
            else -> null
        }
        report("L03", "修正版", fixedReason, "內建 2 個 pin；金鑰 A、備援金鑰 B 都連得上；攻擊者連不上（${fixed.third}）", failures)

        assertEquals(emptyList<String>(), failures)
    }

    // ---------------------------------------------------------------- L04：401 只重送一次

    private class Outcome(val requests: Int, val refreshes: Int, val code: Int?, val error: String?)

    private fun fetchAssets(newClient: (refresh: () -> String) -> OkHttpClient): Outcome {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(401)   // 帳號被凍結：一律 401
        }
        server.start()
        val refreshes = AtomicInteger(0)
        val client = newClient { "token-${refreshes.incrementAndGet()}" }
        try {
            val code = client.newCall(Request.Builder().url(server.url("/assets")).build()).execute().use { it.code }
            return Outcome(server.requestCount, refreshes.get(), code, null)
        } catch (e: IOException) {
            return Outcome(server.requestCount, refreshes.get(), null, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun l04() {
        val failures = mutableListOf<String>()

        val b = fetchAssets { refresh -> OkHttpClient.Builder().authenticator(L04Bug.TokenAuthenticator(refresh)).build() }
        val bugReason = when {
            b.refreshes == 0 -> "Bug 版收到 401 要刷新 token（呼叫 refresh()）再回傳新請求"
            b.requests <= 20 -> "Bug 還沒重現：每次 401 都刷新、都回傳新請求，沒有檢查 priorResponse，應該一路打到 OkHttp 的上限（目前只打了 ${b.requests} 次）"
            else -> null
        }
        report("L04", "Bug 版", bugReason, "伺服器收到 ${b.requests} 次請求，刷新 ${b.refreshes} 次；${b.error}", failures)

        val f = fetchAssets { refresh -> OkHttpClient.Builder().authenticator(L04Fixed.TokenAuthenticator(refresh)).build() }
        val fixedReason = when {
            f.requests != 2 || f.refreshes != 1 ->
                "401 只能刷新 1 次、重送 1 次，/assets 共 2 次請求（目前 ${f.requests} 次請求、刷新 ${f.refreshes} 次）：先檢查 response.priorResponse != null 就回傳 null"
            f.code != 401 -> "重送後還是 401，要把 401 回傳給呼叫端（目前 ${f.code ?: f.error}）"
            else -> null
        }
        report("L04", "修正版", fixedReason, "伺服器收到 ${f.requests} 次請求，刷新 ${f.refreshes} 次；呼叫端拿到 HTTP ${f.code}", failures)

        assertEquals(emptyList<String>(), failures)
    }
}
