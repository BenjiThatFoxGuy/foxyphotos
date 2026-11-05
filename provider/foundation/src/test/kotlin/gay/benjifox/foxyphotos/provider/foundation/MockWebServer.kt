package gay.benjifox.foxyphotos.provider.foundation

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class MockWebServer(
    private val responses: List<Int>,
) {
    val server = MockWebServer()
    val client = OkHttpClient()
    var requestCount = 0
        private set

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCount++
                val response = if (responses.isEmpty()) {
                    MockResponse().setResponseCode(200).setBody("not-found")
                } else {
                    responses.removeAt(0).let { code ->
                        MockResponse().setResponseCode(code).setBody("initial-response")
                    }
                }
                return response
            }
        }
        server.start()
    }

    fun url(path: String): String = "http://localhost:${server.port}$path"

    fun shutdown() {
        server.shutdown()
    }
}
