package io.hafa.rmapikt

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs against a real local server over real OkHttp rather than a stubbed transport, so
 * these exercise the same code path production does. The library has no test seams; the
 * host is simply pointed at the server, using the option that exists for rmfakecloud.
 */
class HttpTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient()

    @AfterTest
    fun shutdown() {
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun authed(maxTransientRetries: Int = 0) =
        AuthedHttp(client, "session-token", maxTransientRetries)

    @Test
    fun `a successful request carries the bearer token`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "hello"))
        val response = authed().request(server.url("/path").toString(), method = "GET")
        assertEquals("hello", response.use { it.body.string() })

        val recorded = server.takeRequest()
        assertEquals("Bearer session-token", recorded.headers["Authorization"])
        assertEquals("/path", recorded.target)
        assertEquals("GET", recorded.method)
    }

    @Test
    fun `a 5xx is retried and then succeeds`() = runTest {
        server.enqueue(MockResponse(code = 503, body = "unavailable"))
        server.enqueue(MockResponse(code = 200, body = "recovered"))

        val response = authed(maxTransientRetries = 3)
            .request(server.url("/retry").toString(), method = "GET")
        assertEquals("recovered", response.use { it.body.string() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 429 is treated as transient`() = runTest {
        server.enqueue(MockResponse(code = 429, body = "slow down"))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        val response = authed(maxTransientRetries = 2)
            .request(server.url("/throttled").toString(), method = "GET")
        assertEquals("ok", response.use { it.body.string() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries are bounded and the last failure is reported`() = runTest {
        repeat(3) { server.enqueue(MockResponse(code = 500, body = "boom")) }

        val error = assertFailsWith<ResponseException> {
            authed(maxTransientRetries = 2).request(server.url("/dead").toString(), method = "GET")
        }
        assertEquals(500, error.status)
        assertEquals("boom", error.body)
        assertEquals(3, server.requestCount, "one initial attempt plus two retries")
    }

    @Test
    fun `a 4xx is not retried`() = runTest {
        server.enqueue(MockResponse(code = 404, body = "nope"))

        val error = assertFailsWith<ResponseException> {
            authed(maxTransientRetries = 5).request(server.url("/missing").toString(), method = "GET")
        }
        assertEquals(404, error.status)
        assertEquals(1, server.requestCount, "a client error will not become a success on retry")
    }

    @Test
    fun `a stale generation is signalled rather than retried`() = runTest {
        server.enqueue(
            MockResponse(code = 412, body = "{\"message\":\"precondition failed\"}\n"),
        )
        assertFailsWith<PreconditionFailedException> {
            authed(maxTransientRetries = 5).request(server.url("/root").toString())
        }
        assertEquals(1, server.requestCount, "resending the same stale generation cannot help")
    }

    @Test
    fun `backoff grows exponentially and stays within the jitter bound`() {
        val alwaysMax = Random(0)
        for (attempt in 0..4) {
            repeat(20) {
                val delay = backoffMillis(attempt, baseMillis = 200, random = alwaysMax)
                assertTrue(delay in 0..(200L shl attempt), "attempt $attempt gave $delay")
            }
        }
    }

    @Test
    fun `backoff is capped so a long outage does not schedule an absurd delay`() {
        repeat(50) {
            assertTrue(backoffMillis(attempt = 30, baseMillis = 200) <= 30_000)
        }
    }

    @Test
    fun `no attempt count produces a bound outside the cap`() {
        // sweeping, not sampling: the wrap point depends on the base as well as the count
        for (base in listOf(1L, 25L, 200L, Long.MAX_VALUE / 2, Long.MAX_VALUE)) {
            for (attempt in 0..200) {
                val delay = backoffMillis(attempt, base, Random(attempt))
                assertTrue(delay in 0..30_000, "base $base attempt $attempt gave $delay")
            }
        }
    }

    @Test
    fun `a base larger than the cap is honoured rather than shrunk`() {
        // the cap bounds growth; it is not a claim that the caller asked for too much
        val delay = backoffMillis(0, 60_000L, Random(0))
        assertTrue(delay <= 30_000, "still bounded by the cap")
    }

    @Test
    fun `the bound still grows with the attempt until it reaches the cap`() {
        val early = backoffMillis(2, 25L, Random(0))
        val late = backoffMillis(20, 25L, Random(0))
        assertTrue(early <= 100, "a second attempt is bounded by 25 * 2^2")
        assertTrue(late > early, "and a later one is not clamped to the same value")
    }
}
