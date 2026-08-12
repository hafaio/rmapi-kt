package io.hafa.rmapikt

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/** the dump format this build writes, and the only one it accepts */
private const val CACHE_VERSION = 2

/** how a dumped body is encoded, so a later format change is legible rather than silent */
private const val CACHE_ENCODING = "base64"

/**
 * what the client knows about one hash
 *
 * Blobs are content-addressed, so a cached entry can never go stale and nothing needs a ttl.
 * The two cases split on size, not on kind: anything small enough is kept whole, and for a
 * blob too large to hold it is enough to remember the server has the hash so an upload can
 * be skipped.
 */
internal sealed interface CacheEntry {
    /** the full contents of a blob small enough to keep */
    class Body(val bytes: ByteArray) : CacheEntry {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Body && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** a large blob known to be on the server; skip re-uploading it, but still fetch to read */
    data object Exists : CacheEntry
}

@Serializable
private data class CacheDump(
    val version: Int,
    val encoding: String,
    val bodies: Map<String, String>,
    val exists: List<String>,
)

/**
 * A least-recently-used cache of blobs by hash, bounded by total size.
 *
 * Values are bytes rather than text. A cache that held decoded text could only admit blobs
 * that decode losslessly, which left the binary ones — `.rm` pages above all — fetching over
 * the network every time; bytes admit every kind, and a caller wanting text decodes on the
 * way out.
 *
 * Every operation is synchronized. The critical sections are pure map manipulation with no
 * suspension and no i/o, so a monitor is both cheaper and simpler than a suspending mutex.
 * Two concurrent readers of the same missing hash will both fetch it and then converge on
 * the same value, which is harmless for content-addressed data and much less machinery than
 * single-flighting.
 */
internal class LruCache(private val maxBytes: Long) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, CacheEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var currentBytes = 0L

    operator fun get(hash: String): CacheEntry? = synchronized(lock) { entries[hash] }

    operator fun set(hash: String, entry: CacheEntry): Unit = synchronized(lock) {
        entries.remove(hash)?.let { currentBytes -= sizeOf(hash, it) }
        entries[hash] = entry
        currentBytes += sizeOf(hash, entry)
        // never evict the entry just written, so a single oversized blob is still cached
        while (currentBytes > maxBytes && entries.size > 1) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            currentBytes -= sizeOf(eldest.key, eldest.value)
        }
    }

    fun remove(hash: String): Boolean = synchronized(lock) {
        val removed = entries.remove(hash) ?: return false
        currentBytes -= sizeOf(hash, removed)
        return true
    }

    fun clear(): Unit = synchronized(lock) {
        entries.clear()
        currentBytes = 0
    }

    fun hashes(): Set<String> = synchronized(lock) { entries.keys.toSet() }

    fun byteCount(): Long = synchronized(lock) { currentBytes }

    fun dump(): String = synchronized(lock) {
        val bodies = LinkedHashMap<String, String>()
        val exists = ArrayList<String>()
        for ((hash, entry) in entries) {
            when (entry) {
                is CacheEntry.Body -> bodies[hash] = Base64.encode(entry.bytes)
                CacheEntry.Exists -> exists.add(hash)
            }
        }
        return encodeWire(
            CacheDump.serializer(),
            CacheDump(CACHE_VERSION, CACHE_ENCODING, bodies, exists),
        )
    }

    private fun sizeOf(hash: String, entry: CacheEntry): Long = when (entry) {
        is CacheEntry.Body -> (hash.length + entry.bytes.size).toLong()
        CacheEntry.Exists -> hash.length.toLong()
    }

    companion object {
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f

        /**
         * Rebuilds a cache from a previous [dump].
         *
         * The version and encoding are checked rather than assumed, so a dump written by a
         * different build is refused outright instead of read as though nothing had changed.
         */
        fun load(dump: String, maxBytes: Long): LruCache {
            val parsed = decodeWire(CacheDump.serializer(), dump, "cache dump")
            if (parsed.version != CACHE_VERSION || parsed.encoding != CACHE_ENCODING) {
                throw ValidationException(
                    "cache dump is version ${parsed.version} in ${parsed.encoding}; " +
                        "expected $CACHE_VERSION in $CACHE_ENCODING",
                    dump,
                )
            }
            val cache = LruCache(maxBytes)
            for ((hash, body) in parsed.bodies) {
                cache[hash] = CacheEntry.Body(Base64.decode(body))
            }
            for (hash in parsed.exists) {
                cache[hash] = CacheEntry.Exists
            }
            return cache
        }
    }
}
