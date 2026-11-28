package app.agency.core.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * The only place an HTTP client is built (HttpClientOnlyInNetTest). Redirects are never followed (a key must not follow one to
 * another host), every request passes the [NetworkGate] (after [RuntimeToIo]), and there is no logging interceptor of any kind.
 */
object HttpFactory {
    const val CONNECT_SECONDS = 15L
    const val READ_SECONDS = 240L
    const val CALL_SECONDS = 300L

    fun create(gate: NetworkGate): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(RuntimeToIo)
        .addInterceptor(gate)
        .addNetworkInterceptor(gate)
        .build()

    /**
     * First in the chain: a RuntimeException below it (a platform one from DNS or TLS, such as SecurityException) becomes an
     * IOException, so the caller sees Offline. OkHttp would otherwise rethrow it on its own thread and end the process. A
     * LinkageError (a TLS or DNS provider that fails to load: NoClassDefFoundError, ExceptionInInitializerError) is mapped the
     * same way, so a failing call is never a crash loop at start; every other Error (an OutOfMemoryError) still ends the process.
     */
    internal object RuntimeToIo : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("call failed", e)
        } catch (e: LinkageError) {
            throw IOException("call failed", e)
        }
    }
}
