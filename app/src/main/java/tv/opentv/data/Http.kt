// language: Kotlin, file: data/Http.kt, target: Android 14
// *Shared OkHttp client. DoH optional, timeouts tuned for flaky IPTV panels.*

package tv.opentv.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import tv.opentv.BuildConfig

object Http {

    /** Panels can be slow to hand back large channel lists; keep reads generous. */
    private const val CONNECT_TIMEOUT_S = 20L
    private const val READ_TIMEOUT_S = 45L
    private const val WRITE_TIMEOUT_S = 30L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .build()
    }

    /**
     * Some IPTV hosts are DNS-blocked while their origin stays reachable. Enabling
     * DNS-over-HTTPS sidesteps local resolver blocking without touching TLS.
     */
    fun withDnsOverHttps(): OkHttpClient {
        val dohUrl = "https://1.1.1.1/dns-query".toHttpUrl()
        val doh = okhttp3.dnsoverhttps.DnsOverHttps.Builder()
            .client(client)
            .url(dohUrl)
            .bootstrapDnsHosts(
                java.net.InetAddress.getByName("1.1.1.1"),
                java.net.InetAddress.getByName("1.0.0.1"),
            )
            .build()
        return client.newBuilder().dns(doh).build()
    }
}

private fun String.toHttpUrl(): okhttp3.HttpUrl =
    okhttp3.HttpUrl.parse(this) ?: error("Bad URL: $this")
