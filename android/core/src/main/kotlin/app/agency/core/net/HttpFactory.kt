package app.agency.core.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The only place an HTTP client is built (HttpClientOnlyInNetTest). Redirects are never followed (a key must not follow one to
 * another host), every request passes the [NetworkGate], and there is no logging interceptor of any kind.
 */
object HttpFactory {
    const val CONNECT_SECONDS = 15L
    const val READ_SECONDS = 60L
    const val CALL_SECONDS = 90L

    fun create(gate: NetworkGate): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(gate)
        .addNetworkInterceptor(gate)
        .build()
}
