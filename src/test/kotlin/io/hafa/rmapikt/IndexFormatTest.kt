package io.hafa.rmapikt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun repeatedHash(char: Char) = FileHash(char.toString().repeat(64))

/**
 * The entry index is the one place where bytes are the contract: in schema 4 an index's own
 * hash is its address, so a stray character or a different sort order silently produces a
 * different document. These pin the exact serialisation and the two hashing rules.
 */
class IndexFormatTest {
    private val staging = stagingClient()

    private fun bodyOf(staged: StagedFile) = staged.bytes.toString(Charsets.UTF_8)

    private val fileEntries = listOf(
        RawEntry(RawEntryType.File, repeatedHash('b'), "doc.content", 0, 10),
        RawEntry(RawEntryType.File, repeatedHash('a'), "doc.metadata", 0, 20),
    )

    @Test
    fun `a schema 3 index lists a version line then one line per entry`() {
        val staged = staging.stageEntries("doc", fileEntries, SchemaVersion.V3)
        assertEquals(
            "3\n" +
                "${"b".repeat(64)}:0:doc.content:0:10\n" +
                "${"a".repeat(64)}:0:doc.metadata:0:20\n",
            bodyOf(staged),
            "entries are sorted by id, so .content precedes .metadata",
        )
    }

    @Test
    fun `a schema 4 index adds an info line and forces the file type`() {
        val nested = listOf(
            RawEntry(RawEntryType.Collection, repeatedHash('c'), "item", 4, 30),
        )
        val staged = staging.stageEntries("root", nested, SchemaVersion.V4)
        assertEquals(
            "4\n" +
                "0:.:1:30\n" +
                "${"c".repeat(64)}:0:item:4:30\n",
            bodyOf(staged),
            "the root index is named '.', and schema 4 writes every line as type 0",
        )
    }

    @Test
    fun `a schema 4 index names itself by id unless it is the root`() {
        val staged = staging.stageEntries("doc", fileEntries, SchemaVersion.V4)
        assertTrue(bodyOf(staged).startsWith("4\n0:doc:2:30\n"), bodyOf(staged))
    }

    @Test
    fun `a schema 4 index is addressed by the hash of its own bytes`() {
        val staged = staging.stageEntries("doc", fileEntries, SchemaVersion.V4)
        assertEquals(sha256Hex(staged.bytes), staged.entry.hash.hex)
        assertEquals(RawEntryType.File, staged.entry.type)
    }

    @Test
    fun `a schema 3 index is addressed by the hash of its sorted entry hashes`() {
        val staged = staging.stageEntries("doc", fileEntries, SchemaVersion.V3)
        // sorted by id: .content (bbb...) then .metadata (aaa...), so the hashes are
        // concatenated in that order, not in hash order
        val expected = sha256Hex(("b".repeat(64) + "a".repeat(64)).hexToBytes())
        assertEquals(expected, staged.entry.hash.hex)
        assertEquals(RawEntryType.Collection, staged.entry.type)
    }

    @Test
    fun `an index entry reports the recursive size and subfile count`() {
        val staged = staging.stageEntries("doc", fileEntries, SchemaVersion.V4)
        assertEquals(30, staged.entry.size)
        assertEquals(2, staged.entry.subfiles)
        assertEquals("doc.docSchema", staged.fileName)
    }

    @Test
    fun `serialising then parsing a schema 3 index returns the same entries`() {
        val staged = staging.stageEntries("doc", fileEntries, SchemaVersion.V3)
        val parsed = parseEntryIndex(bodyOf(staged))
        assertEquals(fileEntries.sortedBy { it.id }, parsed.entries)
        assertNull(parsed.id, "schema 3 indexes carry no id")
        assertNull(parsed.size)
    }

    @Test
    fun `serialising then parsing a schema 4 index returns the same entries and header`() {
        val staged = staging.stageEntries("doc", fileEntries, SchemaVersion.V4)
        val parsed = parseEntryIndex(bodyOf(staged))
        assertEquals(fileEntries.sortedBy { it.id }, parsed.entries)
        assertEquals("doc", parsed.id)
        assertEquals(30, parsed.size)
    }

    @Test
    fun `a schema 3 collection line keeps its type through a round trip`() {
        val collections = listOf(
            RawEntry(RawEntryType.Collection, repeatedHash('d'), "item", 3, 99),
        )
        val staged = staging.stageEntries("root", collections, SchemaVersion.V3)
        assertEquals("3\n${"d".repeat(64)}:80000000:item:3:99\n", bodyOf(staged))
        assertEquals(collections, parseEntryIndex(bodyOf(staged)).entries)
    }

    @Test
    fun `a schema 4 index whose count disagrees with its entries is rejected`() {
        // Go warns here; rmapi-js errors, and so do we -- a miscount means the index was
        // truncated, and trusting it would commit a document with files missing
        val error = assertFailsWith<ValidationException> {
            parseEntryIndex("4\n0:doc:5:30\n${"a".repeat(64)}:0:doc.metadata:0:20\n")
        }
        assertTrue("5" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a malformed index line is rejected`() {
        assertFailsWith<ValidationException> { parseEntryIndex("3\nnot:enough:fields\n") }
        assertFailsWith<ValidationException> {
            parseEntryIndex("3\n${"a".repeat(64)}:0:doc:notanumber:20\n")
        }
        assertFailsWith<ValidationException> {
            parseEntryIndex("3\n${"a".repeat(64)}:12345:doc:0:20\n")
        }
    }

    @Test
    fun `an unsupported schema version is rejected`() {
        assertFailsWith<ValidationException> { parseEntryIndex("5\n") }
        assertFailsWith<ValidationException> { parseEntryIndex("nonsense\n") }
    }

    @Test
    fun `a schema 4 index missing its info line is rejected`() {
        assertFailsWith<ValidationException> { parseEntryIndex("4\n") }
        assertFailsWith<ValidationException> {
            parseEntryIndex("4\n1:doc:0:0\n")
        }
    }

    @Test
    fun `an empty index parses to no entries`() {
        assertEquals(emptyList(), parseEntryIndex("3\n").entries)
        assertEquals(emptyList(), parseEntryIndex("4\n0:doc:0:0\n").entries)
    }

    @Test
    fun `staging a content or metadata file checks the name it was given`() {
        assertFailsWith<IllegalArgumentException> {
            staging.stageContent("doc.metadata", CollectionContent())
        }
        assertFailsWith<IllegalArgumentException> {
            staging.stageMetadata(
                "doc.content",
                Metadata("n", Parent.Root, false, EntryType.Collection, "0"),
            )
        }
    }

    @Test
    fun `a staged file is addressed by the hash of its bytes`() {
        val staged = staging.stageFile("doc.pdf", byteArrayOf(1, 2, 3))
        assertEquals(sha256Hex(byteArrayOf(1, 2, 3)), staged.entry.hash.hex)
        assertEquals(3, staged.entry.size)
        assertEquals(0, staged.entry.subfiles)
        assertEquals(RawEntryType.File, staged.entry.type)
    }
}
