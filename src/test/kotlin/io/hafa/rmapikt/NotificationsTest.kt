package io.hafa.rmapikt

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** a frame the account actually sent, kept verbatim */
private const val SYNC_COMPLETE = """
{"message":{"attributes":{"auth0UserID":"auth0|5f99caa969ad40006ef20957",
"event":"SyncComplete","eventType":"SyncEventNotification","eventVersion":"1",
"orgID":"","sourceDeviceID":"RM02A004250117J",
"userID":"auth0|5f99caa969ad40006ef20957"},"data":null,"messageid":"21279395402976696"}}
"""

/** the same, from a socket the push service also stamped with a tracing header */
private const val SYNC_COMPLETE_TRACED = """
{"message":{"attributes":{"auth0UserID":"auth0|5f99caa969ad40006ef20957",
"event":"SyncComplete","eventType":"SyncEventNotification","eventVersion":"1",
"googclient_traceparent":"00-61386430468a4dd2b552cf594c9ca8c8-8638993528d01010-00",
"orgID":"","sourceDeviceID":"RM02A004250117J",
"userID":"auth0|5f99caa969ad40006ef20957"},"data":null,"messageid":"21279799870539392"}}
"""

private fun withEvent(event: String) =
    SYNC_COMPLETE.replace("\"SyncComplete\"", "\"$event\"")

private fun without(key: String) = SYNC_COMPLETE.replace("\"$key\"", "\"${key}x\"")

class NotificationsTest {
    private val http = OkHttpClient()
    private val server = MockWebServer()

    /** what each socket the client opens should do, in the order the sockets are opened */
    private val scripts = CopyOnWriteArrayList<(WebSocket) -> Unit>()
    private val opened = AtomicInteger()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val script = scripts.getOrNull(opened.getAndIncrement())
                    ?: return MockResponse(code = 404)
                return MockResponse.Builder()
                    .webSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                script(webSocket)
                            }
                        },
                    )
                    .build()
            }
        }
        server.start()
    }

    @AfterTest
    fun shutdown() {
        server.close()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun client(maxTransientRetries: Int = 3) = session(
        SessionToken("session-token"),
        SessionOptions(
            rawHost = server.url("/").toString().removeSuffix("/"),
            maxTransientRetries = maxTransientRetries,
            httpClient = http,
        ),
    )

    /** Serves one socket per lambda, in order; a socket beyond the last is refused. */
    private fun script(vararg sockets: (WebSocket) -> Unit) {
        scripts.addAll(sockets.toList())
    }

    @Test
    fun `a frame the account sent reads as the event it is`() {
        val event = parseSyncEvent(SYNC_COMPLETE)!!
        assertEquals("SyncComplete", event.attributes.event)
        assertEquals(DeviceId("RM02A004250117J"), event.attributes.sourceDeviceID)
        assertEquals("auth0|5f99caa969ad40006ef20957", event.attributes.auth0UserID)
        assertEquals("auth0|5f99caa969ad40006ef20957", event.attributes.userID)
        assertEquals("SyncEventNotification", event.attributes.eventType)
        assertEquals("1", event.attributes.eventVersion)
        assertEquals("", event.attributes.orgID)
        assertEquals("21279395402976696", event.messageid)
    }

    @Test
    fun `an attribute of the push service's own is no reason to refuse a frame`() {
        assertEquals(
            DeviceId("RM02A004250117J"),
            parseSyncEvent(SYNC_COMPLETE_TRACED)!!.attributes.sourceDeviceID,
        )
    }

    @Test
    fun `what only reMarkable sends is absent rather than fatal`() {
        val sparse = """
            {"message":{"attributes":{"event":"SyncComplete",
            "sourceDeviceID":"RM02A004250117J","auth0UserID":"auth0|1"}}}
        """
        val event = parseSyncEvent(sparse)!!
        assertEquals(DeviceId("RM02A004250117J"), event.attributes.sourceDeviceID)
        assertNull(event.attributes.eventType)
        assertNull(event.attributes.eventVersion)
        assertNull(event.attributes.userID)
        assertNull(event.attributes.orgID)
        assertNull(event.messageid)
    }

    @Test
    fun `another kind of event on the same socket is passed over, not refused`() {
        assertNull(parseSyncEvent(withEvent("ScreenShareStarted")))
    }

    @Test
    fun `a frame missing what every sync carries is refused`() {
        for (key in listOf("event", "sourceDeviceID", "auth0UserID")) {
            assertFailsWith<ValidationException> { parseSyncEvent(without(key)) }
        }
    }

    @Test
    fun `an envelope shaped differently is refused with the text that failed`() {
        val failure = assertFailsWith<ValidationException> { parseSyncEvent("""{"message":{}}""") }
        assertEquals("""{"message":{}}""", failure.rawText)
    }

    @Test
    fun `a notification arrives as a value on the flow`() = runTest {
        script({ it.send(SYNC_COMPLETE) })
        val notifications = client().notifications().take(1).toList()
        assertEquals(DeviceId("RM02A004250117J"), notifications.single().attributes.sourceDeviceID)
    }

    @Test
    fun `the socket carries the session token`() = runTest {
        script({ it.send(SYNC_COMPLETE) })
        client().notifications().take(1).toList()
        val request = server.takeRequest()
        assertEquals("/notifications/ws/json/1", request.url.encodedPath)
        assertEquals("Bearer session-token", request.headers["Authorization"])
    }

    @Test
    fun `a socket the account hangs up on is replaced by another`() = runTest {
        script(
            { it.send(SYNC_COMPLETE); it.close(1000, "done") },
            { it.send(SYNC_COMPLETE) },
        )
        assertEquals(2, client().notifications().take(2).toList().size)
        assertEquals(2, opened.get())
    }

    @Test
    fun `a binary frame is refused rather than guessed at`() = runTest {
        script({ it.send(SYNC_COMPLETE.encodeUtf8()) })
        assertFailsWith<ValidationException> { client().notifications().take(1).toList() }
    }

    @Test
    fun `an event of another kind does not reach the flow at all`() = runTest {
        script({ it.send(withEvent("ScreenShareStarted")); it.send(SYNC_COMPLETE) })
        val notifications = client().notifications().take(1).toList()
        assertEquals(DeviceId("RM02A004250117J"), notifications.single().attributes.sourceDeviceID)
    }

    @Test
    fun `a refused token ends the flow, since reconnecting cannot fix one`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = 401)
        }
        val failure = assertFailsWith<ResponseException> { client().notifications().take(1).toList() }
        assertEquals(401, failure.status)
    }

    @Test
    fun `a socket that will not open at all gives up rather than retrying forever`() = runTest {
        server.close()
        assertFailsWith<IOException> { client(maxTransientRetries = 2).notifications().take(1).toList() }
    }
}
