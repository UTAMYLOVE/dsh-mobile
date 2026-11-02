package app.dshmobile.runtime

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Imitates dsh's launch-token login: `/?token=good` answers 303 + cookie, everything else needs that cookie (else 401). */
class FakeDshServer(
    private val healthStatus: Map<String, Int> = mapOf("/dshm/health/memory" to 200, "/dshm/health/notify" to 200),
    private val healthBody: String = """{"ok":true}""",
    private val tokenValid: () -> Boolean = { true },
) : AutoCloseable {
    val server = MockWebServer()
    val unauthenticated = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.path.orEmpty()
                if (path == "/?token=good") {
                    return if (tokenValid()) {
                        MockResponse().setResponseCode(303).addHeader("Location", "/")
                            .addHeader("Set-Cookie", "dsh-auth-x=signed; Path=/; HttpOnly; SameSite=Strict")
                    } else MockResponse().setResponseCode(403)
                }
                if (request.getHeader("Cookie")?.contains("dsh-auth-x=signed") != true) {
                    unauthenticated.add(path)
                    return MockResponse().setResponseCode(401)
                }
                return when {
                    path == "/" -> MockResponse().setResponseCode(200).setBody("<html></html>")
                    path in healthStatus -> MockResponse().setResponseCode(healthStatus.getValue(path)).setBody(healthBody)
                    path == "/echo" -> MockResponse().setResponseCode(200).setBody(request.body.readUtf8())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val launch: LaunchUrl get() = LaunchUrl("http://127.0.0.1:${server.port}/?token=good", server.port)

    override fun close() { server.shutdown() }
}
