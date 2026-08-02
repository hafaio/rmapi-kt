package io.hafa.rmapikt

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/** the body the cloud returns when the root generation it was given is stale */
private const val PRECONDITION_FAILED = "{\"message\":\"precondition failed\"}\n"

private const val TRANSIENT_BASE_MS = 200L
private const val BACKOFF_CAP_MS = 30_000L
private const val TOO_MANY_REQUESTS = 429
private const val FIRST_SERVER_ERROR = 500

/**
 * Methods OkHttp refuses to send without a body.
 *
 * `fetch` is happy to POST nothing, and several of the cloud's endpoints are bodyless
 * POSTs, so those get an explicit empty body rather than a null one.
 */
private val BODY_REQUIRED = setOf("POST", "PUT", "PATCH")

private val EMPTY_BODY = ByteArray(0).toRequestBody()

/**
 * Exponential backoff with full jitter.
 *
 * The jitter is the point: without it every client that failed against the same outage
 * retries in lockstep and reproduces the load that caused it.
 */
internal fun backoffMillis(attempt: Int, baseMillis: Long, random: Random = Random): Long {
    val capped = min(baseMillis shl attempt, BACKOFF_CAP_MS)
    return random.nextLong(capped + 1)
}

/**
 * The cloud rejected a root write because the generation sent with it was stale.
 *
 * This is internal because it carries no generation: only the caller that issued the write
 * knows which value it sent, so it is the one that can raise the public
 * [GenerationException]. Retrying the request here would only resend the same stale
 * generation, so the retry belongs around the whole read-modify-write instead.
 */
internal class PreconditionFailedException : Exception("root generation was stale")

/**
 * Issues authenticated requests, retrying the ones worth retrying.
 *
 * A network failure, a 5xx, or a 429 is transient and is retried with backoff. Anything
 * else is reported: a stale root generation as [PreconditionFailedException], every other
 * non-2xx as [ResponseException].
 */
internal class AuthedHttp(
    private val httpClient: OkHttpClient,
    private val sessionToken: String,
    private val maxTransientRetries: Int,
) {
    suspend fun request(
        url: String,
        method: String = "POST",
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $sessionToken")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, body ?: EMPTY_BODY.takeIf { method in BODY_REQUIRED })
            .build()

        var attempt = 0
        while (true) {
            val response = try {
                httpClient.newCall(request).executeAsync()
            } catch (error: IOException) {
                if (attempt < maxTransientRetries) {
                    delay(backoffMillis(attempt, TRANSIENT_BASE_MS))
                    attempt++
                    continue
                } else {
                    throw error
                }
            }

            if (response.isSuccessful) {
                return response
            }

            val message = response.use { it.body.string() }
            throwUnlessRetryable(response.code, response.message, message, attempt)
            delay(backoffMillis(attempt, TRANSIENT_BASE_MS))
            attempt++
        }
    }

    /** Returns only when [code] is worth another attempt; otherwise reports the failure. */
    private fun throwUnlessRetryable(code: Int, statusText: String, message: String, attempt: Int) {
        if (message == PRECONDITION_FAILED) {
            throw PreconditionFailedException()
        } else if (!isTransient(code) || attempt >= maxTransientRetries) {
            throw ResponseException(code, statusText, message, "failed reMarkable request: $message")
        }
    }

    private fun isTransient(code: Int): Boolean =
        code >= FIRST_SERVER_ERROR || code == TOO_MANY_REQUESTS
}
