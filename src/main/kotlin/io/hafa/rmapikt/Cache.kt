package io.hafa.rmapikt

import kotlinx.serialization.Serializable

/** the dump format this build writes, and the only one it accepts */
private const val CACHE_VERSION = 1

/**
 * what the client knows about one hash
 *
 * Blobs are content-addressed, so a cached entry can never go stale and nothing needs a
 * ttl. The two cases exist because the two kinds of blob want different treatment: text
 * blobs (entry indexes, `.content`, `.metadata`) are small enough to keep whole, while
 * pdfs and epubs are not, and for those it is enough to remember that the server already
 * has the hash so an upload can be skipped.
 */
internal sealed interface CacheEntry {
    /** the full contents of a small text blob */
    data class Text(val text: String) : CacheEntry

    /** a large blob known to be on the server; skip re-uploading it, but still fetch to read */
    data object Exists : CacheEntry
}

@Serializable
private data class CacheDump(
    val version: Int,
    val text: Map<String, String>,
    val exists: List<String>,
)

/**
 * A least-recently-used cache of blobs by hash, bounded by total character count.
 *
 * Characters rather than bytes: the bound exists to stop the cache growing without limit,
 * not to account for memory exactly, and `String.length` is free where any byte count has
 * to either encode the string or approximate it. For what this cache holds — entry
 * indexes and json metadata, almost entirely ascii — the two agree anyway.
 *
 * Every operation is synchronized. The critical sections are pure map manipulation with
 * no suspension and no i/o, so a monitor is both cheaper and simpler than a suspending
 * mutex. Two concurrent readers of the same missing hash will both fetch it and then
 * converge on the same value, which is harmless for content-addressed data and much less
 * machinery than single-flighting.
 */
internal class LruCache(private val maxChars: Long) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, CacheEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var currentChars = 0L

    operator fun get(hash: String): CacheEntry? = synchronized(lock) { entries[hash] }

    operator fun set(hash: String, entry: CacheEntry): Unit = synchronized(lock) {
        entries.remove(hash)?.let { currentChars -= sizeOf(hash, it) }
        entries[hash] = entry
        currentChars += sizeOf(hash, entry)
        // never evict the entry just written, so a single oversized blob is still cached
        while (currentChars > maxChars && entries.size > 1) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            currentChars -= sizeOf(eldest.key, eldest.value)
        }
    }

    fun remove(hash: String): Boolean = synchronized(lock) {
        val removed = entries.remove(hash) ?: return false
        currentChars -= sizeOf(hash, removed)
        return true
    }

    fun clear(): Unit = synchronized(lock) {
        entries.clear()
        currentChars = 0
    }

    fun hashes(): Set<String> = synchronized(lock) { entries.keys.toSet() }

    fun charCount(): Long = synchronized(lock) { currentChars }

    fun dump(): String = synchronized(lock) {
        val text = LinkedHashMap<String, String>()
        val exists = ArrayList<String>()
        for ((hash, entry) in entries) {
            when (entry) {
                is CacheEntry.Text -> text[hash] = entry.text
                CacheEntry.Exists -> exists.add(hash)
            }
        }
        return encodeWire(CacheDump.serializer(), CacheDump(CACHE_VERSION, text, exists))
    }

    private fun sizeOf(hash: String, entry: CacheEntry): Long = when (entry) {
        is CacheEntry.Text -> (hash.length + entry.text.length).toLong()
        CacheEntry.Exists -> hash.length.toLong()
    }

    companion object {
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f

        /**
         * Rebuilds a cache from a previous [dump].
         *
         * The version is checked rather than assumed so that a dump written by a future
         * build is refused outright instead of being read as though the format had not
         * changed.
         */
        fun load(dump: String, maxChars: Long): LruCache {
            val parsed = decodeWire(CacheDump.serializer(), dump, "cache dump")
            if (parsed.version != CACHE_VERSION) {
                throw ValidationException(
                    "cache dump version ${parsed.version} is not supported; expected $CACHE_VERSION",
                    dump,
                )
            }
            val cache = LruCache(maxChars)
            for ((hash, text) in parsed.text) {
                cache[hash] = CacheEntry.Text(text)
            }
            for (hash in parsed.exists) {
                cache[hash] = CacheEntry.Exists
            }
            return cache
        }
    }
}
