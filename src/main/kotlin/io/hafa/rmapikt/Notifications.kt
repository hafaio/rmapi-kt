package io.hafa.rmapikt

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How often the socket is pinged.
 *
 * Not a keepalive — the account ends a socket every couple of minutes whether or not
 * anything was said on it. It is the other direction that needs this: a socket with no read
 * timeout, which is what an idle-by-design connection has to be, would otherwise sit
 * forever on a network that has quietly gone away.
 */
private const val KEEPALIVE_SECONDS = 30L

private const val UNAUTHORIZED = 401

private const val EVENT_KEY = "event"

/** the only event this library models, of the several the socket carries */
private const val SYNC_COMPLETE = "SyncComplete"

/**
 * what a sync notification carries
 *
 * Only [sourceDeviceID] and [auth0UserID] are always there. reMarkable sends the rest and
 * rmfakecloud does not, so they are nullable rather than a reason to refuse a frame from a
 * server that is otherwise working. Spellings are the wire's, as everywhere else here.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
public data class SyncEventAttributes(
    /** the kind of event, `SyncComplete` for everything that reaches a caller */
    public val event: String,
    /**
     * the device that synced
     *
     * Tablets report their serial and api clients the id they registered with, so compare
     * it against [RemarkableClient.deviceId] to spot your own syncs.
     */
    public val sourceDeviceID: DeviceId,
    /** the account that synced, as an auth0 id */
    public val auth0UserID: String,
    /** the same value as [auth0UserID] in every event seen so far */
    public val userID: String? = null,
    /** the family the event belongs to */
    public val eventType: String? = null,
    /** the version of the event's format */
    public val eventVersion: String? = null,
    /** the organization of a managed account, otherwise empty */
    public val orgID: String? = null,
)

/**
 * one notification pushed over the sync socket
 *
 * A frame carries a little more than this — the push service's own tracing headers, and
 * whatever a later firmware adds — but nothing worth depending on, so the rest is left
 * unread rather than modelled.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
public data class SyncEvent(
    /** what happened */
    public val attributes: SyncEventAttributes,
    /** this notification's id, which rmfakecloud does not send */
    public val messageid: String? = null,
)

/**
 * Reads a frame, or returns null for one that is not a sync.
 *
 * Screen sharing and passcode events come over the same socket with attributes shaped
 * differently, so the kind is read off the tree before the frame is decoded and anything
 * else is passed over. Skipping is not leniency: those frames are not syncs failing to
 * parse, they are somebody else's mail. A frame that names no event at all is neither, and
 * raises.
 */
internal fun parseSyncEvent(text: String): SyncEvent? = try {
    val message = wireJson.parseToJsonElement(text).jsonObject["message"]?.jsonObject
        ?: throw ValidationException("a notification carried no message", text)
    val event = message["attributes"]?.jsonObject?.get(EVENT_KEY)?.jsonPrimitive?.content
        ?: throw ValidationException("a notification carried no event", text)
    if (event == SYNC_COMPLETE) {
        wireJson.decodeFromJsonElement(SyncEvent.serializer(), message)
    } else {
        null
    }
} catch (error: SerializationException) {
    throw ValidationException("could not parse notification: ${error.message}", text, error)
} catch (error: IllegalArgumentException) {
    throw ValidationException("could not parse notification: ${error.message}", text, error)
}

/**
 * The socket the account pushes sync notifications to, reopened as often as it takes.
 *
 * Held by [RemarkableClient] rather than reachable on its own, because a caller that has a
 * session token has a client, and the socket wants the same token, host, and OkHttp
 * configuration the requests use.
 */
internal class NotificationSocket(
    httpClient: OkHttpClient,
    private val sessionToken: String,
    private val rawHost: String,
    private val maxTransientRetries: Int,
) {
    // Derived from the caller's client rather than built fresh, so proxies, interceptors,
    // and certificate pinning still apply, but with two settings a socket cannot share: a
    // read timeout would kill a connection that is idle by design, and the pings are what
    // notice a network that went away. Lazy, so a client nobody listens on never builds it.
    private val sockets by lazy {
        httpClient.newBuilder()
            .pingInterval(KEEPALIVE_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun notifications(): Flow<SyncEvent> = flow {
        var failures = 0
        while (true) {
            // set from the socket's thread, read from this one
            val opened = AtomicBoolean()
            val ended = try {
                emitAll(connect { opened.set(true) })
                null
            } catch (unreachable: IOException) {
                unreachable
            }
            // Reconnecting answers every reason a socket that was working ends — the
            // account hangs up on one every couple of minutes regardless — but not one
            // that never opened at all: that is a wrong host, or a network that is gone,
            // and after enough of those in a row the caller is better off told.
            if (opened.get()) {
                failures = 0
            } else if (failures < maxTransientRetries) {
                failures++
            } else {
                throw ended ?: IOException("the notification socket would not open")
            }
            delay(backoffMillis(failures, TRANSIENT_BASE_MS))
        }
    }

    /**
     * One socket's worth of notifications, ending when that socket does.
     *
     * A refused token ends the whole stream instead: it is the one failure reconnecting
     * cannot fix, since this library never refreshes a session token on its own.
     */
    private fun connect(onOpen: () -> Unit): Flow<SyncEvent> = callbackFlow {
        val request = Request.Builder()
            .url("$rawHost/notifications/ws/json/1")
            .header("Authorization", "Bearer $sessionToken")
            .build()
        val socket = sockets.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        parseSyncEvent(text)?.let { trySend(it) }
                    } catch (unreadable: ValidationException) {
                        close(unreadable)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    close(
                        ValidationException(
                            "a notification arrived as ${bytes.size} binary bytes, not as text",
                            bytes.hex(),
                        ),
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(refusal(response) ?: t)
                }

                // the handshake is two frames: without this the account's goodbye is never
                // answered, the socket never finishes closing, and the flow waits forever
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }
            },
        )
        awaitClose { socket.cancel() }
    }

    private fun refusal(response: Response?): ResponseException? =
        response?.takeIf { it.code == UNAUTHORIZED }?.let {
            ResponseException(
                it.code,
                it.message,
                "",
                "the notification socket refused the session token",
            )
        }
}
