package io.hafa.rmapikt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.util.UUID

private const val CONNECT_CODE_LENGTH = 8

/** eight million characters, which holds a large account's indexes and metadata */
private const val DEFAULT_MAX_CACHE_CHARS = 8L * 1024 * 1024

/**
 * One client, shared by every default-constructed option set.
 *
 * OkHttp keeps a connection pool and a dispatcher per client and expects one to be shared;
 * a fresh client per `SessionOptions()` would leak both, and nothing ever closes them.
 */
private val sharedHttpClient: OkHttpClient by lazy { OkHttpClient() }

/**
 * the long-lived credential [register] returns
 *
 * Distinct from [SessionToken] so the two cannot be swapped: they look identical and the
 * mistake would otherwise surface as a confusing authentication failure at runtime.
 */
@JvmInline
public value class DeviceToken(
    /** the token as issued, which is what to persist */
    public val value: String,
)

/** the short-lived credential [auth] exchanges a [DeviceToken] for */
@JvmInline
public value class SessionToken(
    /** the token as issued */
    public val value: String,
)

/** the reMarkable services this library talks to */
public object Hosts {
    /** issues device and session tokens */
    public const val AUTH: String = "https://webapp-prod.cloud.remarkable.engineering"

    /** the content-addressed blob store and the root index */
    public const val RAW: String = "https://eu.tectonic.remarkable.com"

    /** the ingestion endpoint behind the `upload*` family */
    public const val UPLOAD: String = "https://internal.cloud.remarkable.com"
}

/**
 * how a client identifies itself when registering
 *
 * The cloud rejects a registration whose description it doesn't recognise, so this is an
 * enum rather than a free string.
 */
@Serializable
public enum class DeviceDescription {
    /** a windows desktop client */
    @SerialName("desktop-windows")
    DesktopWindows,

    /** a macos desktop client */
    @SerialName("desktop-macos")
    DesktopMacos,

    /** a linux desktop client */
    @SerialName("desktop-linux")
    DesktopLinux,

    /** an android client */
    @SerialName("mobile-android")
    MobileAndroid,

    /** an ios client */
    @SerialName("mobile-ios")
    MobileIos,

    /** a browser client */
    @SerialName("browser-chrome")
    BrowserChrome,

    /** a reMarkable device */
    @SerialName("remarkable")
    Remarkable,
}

/**
 * how a client behaves once it has a session
 *
 * Everything here has a working default; the hosts exist so the client can be pointed at
 * [rmfakecloud](https://github.com/ddvk/rmfakecloud), and that same knob is what lets the
 * test suite run against a local server without any test-only api.
 */
public data class SessionOptions(
    /** the token-issuing host */
    public val authHost: String = Hosts.AUTH,
    /** the blob and root-index host */
    public val rawHost: String = Hosts.RAW,
    /** the ingestion host */
    public val uploadHost: String = Hosts.UPLOAD,
    /** a previous [RemarkableClient.dumpCache], to start warm */
    public val cache: String? = null,
    /**
     * the largest the cache may grow, counted in characters of cached text
     *
     * A growth bound rather than a memory budget: it exists so a long-running process does
     * not accumulate cached blobs for as long as it lives, which matters most on the mobile
     * devices this library supports. Characters are close enough to bytes for the ascii
     * that entry indexes and json metadata are made of.
     */
    public val maxCacheChars: Long = DEFAULT_MAX_CACHE_CHARS,
    /**
     * how many times a root-mutating operation re-reads and re-applies after losing a race
     *
     * Set to 0 to surface [GenerationException] immediately and decide for yourself.
     */
    public val maxGenerationRetries: Int = 10,
    /** how many times a request is retried after a network failure, a 5xx, or a 429 */
    public val maxTransientRetries: Int = 3,
    /** timeouts, interceptors, proxies, and certificate pinning are configured here */
    public val httpClient: OkHttpClient = sharedHttpClient,
)

@Serializable
private data class RegisterRequest(
    val code: String,
    val deviceDesc: DeviceDescription,
    val deviceID: String,
)

/**
 * exchanges a one-time connect code for a long-lived device token
 *
 * Send the user to <https://my.remarkable.com/device/desktop/connect>, take the eight-letter
 * code they are shown, and pass it here. The returned token does not expire, so persist it;
 * the code is single-use and the user has to fetch a new one to register again.
 *
 * @param code the eight-letter code from the connect page
 * @param deviceDesc how this client identifies itself
 * @param uuid this device's stable id; randomly generated when null
 * @param authHost overridable for rmfakecloud
 * @throws ResponseException if the code was wrong, already used, or expired
 */
public suspend fun register(
    code: String,
    deviceDesc: DeviceDescription = DeviceDescription.BrowserChrome,
    uuid: String? = null,
    authHost: String = Hosts.AUTH,
    httpClient: OkHttpClient = sharedHttpClient,
): DeviceToken {
    require(code.length == CONNECT_CODE_LENGTH) {
        "connect code should be $CONNECT_CODE_LENGTH characters, but was ${code.length}"
    }
    val body = encodeWire(
        RegisterRequest.serializer(),
        RegisterRequest(code, deviceDesc, uuid ?: UUID.randomUUID().toString()),
    )
    val request = Request.Builder()
        .url("$authHost/token/json/2/device/new")
        .header("Authorization", "Bearer")
        .post(body.toRequestBody())
        .build()
    return httpClient.newCall(request).executeAsync().use { response ->
        if (response.isSuccessful) {
            DeviceToken(response.body.string())
        } else {
            throw ResponseException(
                response.code,
                response.message,
                response.body.string(),
                "couldn't register with reMarkable",
            )
        }
    }
}

/**
 * exchanges the long-lived device token for a short-lived session token
 *
 * Useful on its own only in stateless environments, where the session token is obtained
 * once and handed to [session] on each request; otherwise [remarkable] does both steps.
 *
 * @throws ResponseException if the device token is no longer accepted
 */
public suspend fun auth(
    deviceToken: DeviceToken,
    authHost: String = Hosts.AUTH,
    httpClient: OkHttpClient = sharedHttpClient,
): SessionToken {
    val request = Request.Builder()
        .url("$authHost/token/json/2/user/new")
        .header("Authorization", "Bearer ${deviceToken.value}")
        .post(ByteArray(0).toRequestBody())
        .build()
    return httpClient.newCall(request).executeAsync().use { response ->
        if (response.isSuccessful) {
            SessionToken(response.body.string())
        } else {
            throw ResponseException(
                response.code,
                response.message,
                response.body.string(),
                "couldn't fetch a session token",
            )
        }
    }
}

/**
 * builds a client from an existing session token, without touching the network
 *
 * A session token is short-lived. When requests start failing with an auth error, call
 * [auth] again and build a new client; there is no refresh, by design.
 *
 * An unreadable [SessionOptions.cache] is discarded rather than raised: a cache is a
 * performance artifact, and a client that refuses to start because a dump it wrote in an
 * older version can no longer be read would be worse than one that starts cold.
 */
public fun session(
    sessionToken: SessionToken,
    options: SessionOptions = SessionOptions(),
): RemarkableClient {
    val cache = options.cache
        ?.let { runCatching { LruCache.load(it, options.maxCacheChars) }.getOrNull() }
        ?: LruCache(options.maxCacheChars)
    val raw = RawRemarkableClient(
        http = AuthedHttp(options.httpClient, sessionToken.value, options.maxTransientRetries),
        cache = cache,
        rawHost = options.rawHost,
        uploadHost = options.uploadHost,
    )
    return RemarkableClient(raw, options.maxGenerationRetries)
}

/**
 * the usual entry point: exchanges a device token for a session and builds a client
 *
 * @param deviceToken the token [register] returned, which you persisted
 */
public suspend fun remarkable(
    deviceToken: DeviceToken,
    options: SessionOptions = SessionOptions(),
): RemarkableClient =
    session(auth(deviceToken, options.authHost, options.httpClient), options)
