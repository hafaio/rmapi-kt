package io.hafa.rmapikt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheTest {
    private fun text(value: String) = CacheEntry.Text(value)

    @Test
    fun `eviction is least recently used, and reading counts as use`() {
        // keys are 1 byte each here, so the bound is dominated by the values
        val cache = LruCache(maxChars = 10)
        cache["a"] = text("1")
        cache["b"] = text("long")
        assertEquals(text("1"), cache["a"])

        // rewriting a key replaces its size rather than adding to it, so nothing is evicted
        cache["b"] = text("longer")
        assertEquals(text("1"), cache["a"])

        // "a" was read most recently, so "b" is the one that goes
        cache["c"] = text("short")
        assertNull(cache["b"])
        assertEquals(text("1"), cache["a"])

        cache.remove("c")
        cache["d"] = text("short")
        assertNull(cache["c"])
        assertEquals(text("1"), cache["a"])

        cache.clear()
        assertEquals(0L, cache.charCount())
        assertNull(cache["a"])
    }

    @Test
    fun `a single entry larger than the bound is still cached`() {
        val cache = LruCache(maxChars = 4)
        cache["k"] = text("a value far longer than the bound")
        assertTrue(cache["k"] != null, "evicting the entry just written would make it uncacheable")
    }

    @Test
    fun `an existence marker costs only its key`() {
        val cache = LruCache(maxChars = 1000)
        cache["abc"] = CacheEntry.Exists
        assertEquals(3L, cache.charCount())
    }

    @Test
    fun `size counts characters of the key and the value`() {
        val cache = LruCache(maxChars = 1000)
        cache["key"] = text("value")
        assertEquals(3L + 5, cache.charCount())
    }

    @Test
    fun `a dump round trips through load`() {
        val cache = LruCache(maxChars = 1000)
        cache["hash1"] = text("""{"visibleName":"a"}""")
        cache["hash2"] = CacheEntry.Exists
        cache["hash3"] = text("more")

        val restored = LruCache.load(cache.dump(), maxChars = 1000)
        assertEquals(text("""{"visibleName":"a"}"""), restored["hash1"])
        assertEquals(CacheEntry.Exists, restored["hash2"])
        assertEquals(text("more"), restored["hash3"])
        assertEquals(cache.hashes(), restored.hashes())
    }

    @Test
    fun `a dump from an unrecognised version is refused rather than misread`() {
        val error = assertFailsWith<ValidationException> {
            LruCache.load("""{"version":99,"text":{},"exists":[]}""", maxChars = 1000)
        }
        assertTrue("99" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a corrupt dump is reported with the text that failed`() {
        val error = assertFailsWith<ValidationException> {
            LruCache.load("not a cache", maxChars = 1000)
        }
        assertEquals("not a cache", error.rawText)
    }

    @Test
    fun `loading into a smaller bound evicts rather than overflowing`() {
        val cache = LruCache(maxChars = 1000)
        for (index in 1..20) {
            cache["key$index"] = text("value$index")
        }
        val restored = LruCache.load(cache.dump(), maxChars = 40)
        assertTrue(restored.charCount() <= 40, "restored size was ${restored.charCount()}")
        assertTrue(restored.hashes().isNotEmpty())
    }
}
