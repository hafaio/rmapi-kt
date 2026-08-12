package io.hafa.rmapikt

/**
 * every failure this library raises on its own behalf
 *
 * Transport-level failures are deliberately not wrapped: an `IOException` from OkHttp
 * means the network is down, which is a different problem with a different handler than
 * the reMarkable API rejecting a request.
 */
public sealed class RemarkableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * the root moved underneath this client
 *
 * Someone else — another client, or the tablet itself — wrote the root index after this
 * client last read it. The cached root is invalidated, so re-reading and re-applying the
 * change will pick up the new state. Retry policy is deliberately left to the caller.
 */
public class GenerationException(
    /** the generation this client sent, which the server has moved past */
    public val staleGeneration: Long,
) : RemarkableException("root generation $staleGeneration was stale; refetch and retry")

/** the server answered outside 2xx, and it wasn't a generation conflict */
public class ResponseException(
    /** the HTTP status */
    public val status: Int,
    /** the HTTP status text */
    public val statusText: String,
    /** the response body, which usually explains the refusal */
    public val body: String,
    message: String,
) : RemarkableException(message)

/**
 * a value or payload didn't match what this library expects
 *
 * The API is reverse-engineered, so this can mean the cloud changed rather than that
 * anything is wrong. [rawText] carries the text that failed, so a caller who knows better
 * can parse it instead of being stuck.
 */
public class ValidationException(
    message: String,
    /** the raw text that failed validation, when there was one */
    public val rawText: String? = null,
    cause: Throwable? = null,
) : RemarkableException(message, cause)

/** the hash passed in isn't an entry of the current root index */
public class HashNotFoundException(
    /** the ref that was looked for */
    public val ref: ItemRef,
    /**
     * where the item is now, when it is still in the account under a newer hash
     *
     * Null means the item is gone; a hash means only this ref went stale, and re-reading is
     * enough to carry on.
     */
    public val currentHash: FileHash? = null,
) : RemarkableException(
    if (currentHash == null) {
        "'${ref.id.value}' is not in the root index"
    } else {
        "'${ref.id.value}' has moved on from '${ref.hash.hex}' to '${currentHash.hex}'"
    },
)

/** the item exists but has no such component, e.g. asking a pdf document for its epub */
public class ComponentNotFoundException(
    /** the item that was looked in */
    public val ref: ItemRef,
    /** the component that was missing */
    public val component: DocumentComponent,
) : RemarkableException("no ${component.suffix} file in item ${ref.id.value}")
