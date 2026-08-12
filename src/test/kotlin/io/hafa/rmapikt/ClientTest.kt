package io.hafa.rmapikt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assertions are about what the client put on the wire, not about the state of a modelled
 * server — see [MockCloud] for why.
 */
class ClientTest {
    private val cloud = MockCloud()

    // its own client: these tests start and stop a server per test, and a connection
    // pooled to a closed one can be handed to whichever server next binds that port
    private val http = OkHttpClient()

    private fun client(
        maxGenerationRetries: Int = 10,
        server: MockCloud = cloud,
    ): RemarkableClient = session(
        SessionToken("session-token"),
        SessionOptions(
            rawHost = server.host,
            uploadHost = server.host,
            maxGenerationRetries = maxGenerationRetries,
            httpClient = http,
        ),
    )

    @AfterTest
    fun shutdown() {
        cloud.close()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
        // a server-side failure would otherwise surface as a timeout in an unrelated test
        cloud.assertNoServerFailures()
    }

    private fun documentMetadata(name: String, parent: Parent = Parent.Root) = Metadata(
        visibleName = name,
        parent = parent,
        pinned = false,
        type = EntryType.Document,
        lastModified = "1700000000000",
        lastOpened = "1700000000000",
    )

    private fun documentContent(fileType: FileType = FileType.Pdf) = DocumentContent(
        coverPageNumber = -1,
        documentMetadata = DocumentMetadata(),
        extraMetadata = emptyMap(),
        fileType = fileType,
        fontName = "",
        lineHeight = -1,
        orientation = Orientation.Portrait,
        pageCount = 1,
        textAlignment = TextAlignment.Justify,
        textScale = 1.0,
    )

    private fun uploadedNames(): Set<String> = cloud.received
        .filter { it.method == "PUT" && it.fileName != null }
        .map { it.fileName!!.substringAfterLast('.') }
        .toSet()

    // ---------------- listing ----------------

    @Test
    fun `listing an empty account returns nothing`() = runTest {
        assertEquals(emptyMap(), client().metadataByRef())
    }

    @Test
    fun `listing returns each item's metadata, keyed by ref`() = runTest {
        val document = cloud.seed(documentMetadata("a document"), documentContent())
        cloud.seed(
            Metadata("a folder", Parent.Root, true, EntryType.Collection, "1700000000001"),
            CollectionContent(tags = Tags.Structured(listOf(Tag("work", 1700000000000)))),
        )

        val listed = client().metadataByRef()
        assertEquals(2, listed.size)
        assertEquals("a document", listed.getValue(document).visibleName)
        assertEquals(Parent.Root, listed.getValue(document).parent)
        assertEquals(1700000000000, listed.getValue(document).modifiedAt)

        val folder = listed.values.single { it.type == EntryType.Collection }
        assertEquals("a folder", folder.visibleName)
        assertTrue(folder.pinned)
    }

    @Test
    fun `listing does not fetch content`() = runTest {
        cloud.seed(documentMetadata("a document"), documentContent())
        client().metadataByRef()
        assertEquals(
            emptyList(),
            cloud.requestsFor(CONTENT_SUFFIX),
            "a listing pays for metadata only",
        )
    }

    @Test
    fun `listRefs does not fetch any component files`() = runTest {
        cloud.seed(documentMetadata("a document"), documentContent())
        assertEquals(1, client().listRefs().size)
        assertEquals(emptyList(), cloud.requestsFor(METADATA_SUFFIX))
        assertEquals(emptyList(), cloud.requestsFor(CONTENT_SUFFIX))
    }

    @Test
    fun `the wire's stringified timestamps read as numbers`() = runTest {
        cloud.seed(documentMetadata("a document"), documentContent())
        val metadata = client().metadataByRef().values.single()
        assertEquals(1700000000000, metadata.modifiedAt)
        assertEquals(1700000000000, metadata.openedAt)
        assertNull(metadata.createdAt, "this item was seeded without a createdTime")
    }

    // ---------------- put ----------------

    @Test
    fun `putPdf uploads every component file and commits a new root`() = runTest {
        val pdf = "%PDF-1.4 fake".toByteArray()
        val ref = client().putPdf("my doc", pdf, PutOptions(starred = true))

        assertEquals(setOf("content", "metadata", "pagedata", "pdf", "docSchema"), uploadedNames())

        val metadata = cloud.uploadedMetadata()
        assertEquals("my doc", metadata.visibleName)
        assertEquals(Parent.Root, metadata.parent)
        assertTrue(metadata.pinned)
        assertEquals(EntryType.Document, metadata.type)

        val content = assertIs<DocumentContent>(cloud.uploadedContent())
        assertEquals(FileType.Pdf, content.fileType)
        assertEquals(1, content.pageCount)
        assertEquals(1, content.pages?.size, "the device needs a page list even before rendering")
        assertEquals(pdf.size.toString(), content.sizeInBytes)
        assertEquals(1, content.formatVersion)

        assertEquals(listOf(ref.hash.hex), cloud.rootEntries().map { it.hash.hex })
    }

    @Test
    fun `putPdf mints a well formed id and timestamps rather than fixed values`() = runTest {
        val ref = client().putPdf("doc", byteArrayOf(1))
        val metadata = cloud.uploadedMetadata()
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                .matches(ref.id.value),
            ref.id.value,
        )
        assertTrue(metadata.lastModified.toLong() > 0)
        assertEquals(metadata.lastModified, metadata.createdTime)
        assertEquals("0", metadata.lastOpened)
    }

    @Test
    fun `putEpub with no options writes the documented defaults`() = runTest {
        client().putEpub("plain", byteArrayOf(1))

        val content = assertIs<DocumentContent>(cloud.uploadedContent())
        assertEquals(FileType.Epub, content.fileType)
        assertEquals(-1, content.coverPageNumber)
        assertEquals("", content.fontName, "empty selects the device font")
        assertEquals(-1, content.lineHeight, "-1 selects the device line height")
        assertEquals(125, content.margins, "spelled out, so a changed default is visible here")
        assertEquals(Orientation.Portrait, content.orientation)
        assertEquals(TextAlignment.Justify, content.textAlignment)
        assertEquals(1.0, content.textScale)
        assertEquals(ZoomMode.BestFit, content.zoomMode)
        assertNull(content.viewBackgroundFilter)
        assertEquals(emptyMap(), content.extraMetadata)

        val metadata = cloud.uploadedMetadata()
        assertEquals(Parent.Root, metadata.parent)
        assertFalse(metadata.pinned, "a put document is not starred by default")
    }

    @Test
    fun `put options reach the content file`() = runTest {
        client().putEpub(
            "styled",
            byteArrayOf(1),
            PutOptions(
                lineHeight = 180,
                margins = 50,
                textScale = 1.5,
                fontName = "EB Garamond",
                textAlignment = TextAlignment.Left,
                orientation = Orientation.Landscape,
                zoom = Zoom.Custom(
                    scale = 2.0,
                    centerX = 0.0,
                    centerY = 0.0,
                    pageWidth = 1404.0,
                    pageHeight = 1872.0,
                    orientation = Orientation.Portrait,
                ),
                viewBackgroundFilter = BackgroundFilter.FullPage,
                tags = listOf("reading"),
                authors = listOf("an author"),
                title = "a title",
            ),
        )
        val content = assertIs<DocumentContent>(cloud.uploadedContent())
        assertEquals(FileType.Epub, content.fileType)
        assertEquals(180, content.lineHeight)
        assertEquals(50, content.margins)
        assertEquals(1.5, content.textScale)
        assertEquals("EB Garamond", content.fontName)
        assertEquals(TextAlignment.Left, content.textAlignment)
        assertEquals(Orientation.Landscape, content.orientation)
        assertEquals(ZoomMode.CustomFit, content.zoomMode)
        assertEquals(2.0, content.customZoomScale)
        assertEquals(1404.0, content.customZoomPageWidth)
        assertEquals(BackgroundFilter.FullPage, content.viewBackgroundFilter)
        assertEquals(listOf("reading"), content.tags.names)
        assertEquals(listOf("an author"), content.documentMetadata.authors)
        assertEquals("a title", content.documentMetadata.title)
    }

    @Test
    fun `putFolder uploads only content and metadata`() = runTest {
        client().putFolder("a folder")
        assertEquals(setOf("content", "metadata", "docSchema"), uploadedNames())
        assertEquals(EntryType.Collection, cloud.uploadedMetadata().type)
        assertIs<CollectionContent>(cloud.uploadedContent())
    }

    @Test
    fun `putting into a folder records the folder as the parent`() = runTest {
        val api = client()
        val folder = api.putFolder("parent")
        api.putPdf("child", byteArrayOf(1), PutOptions(parent = Parent.Folder(folder.id)))
        assertEquals(Parent.Folder(folder.id), cloud.uploadedMetadata().parent)
    }

    // ---------------- edits ----------------

    @Test
    fun `rename rewrites the name and bumps the metadata version`() = runTest {
        val api = client()
        val original = api.putPdf("before", byteArrayOf(1))
        val renamed = api.rename(original, "after")

        assertNotEquals(original.hash, renamed.hash, "an edit produces a new hash")
        assertEquals(original.id, renamed.id, "the id is stable across an edit")

        val metadata = cloud.uploadedMetadata()
        assertEquals("after", metadata.visibleName)
        assertEquals(1, metadata.version, "the device is told the change came from elsewhere")
        assertEquals(true, metadata.metadatamodified)
    }

    @Test
    fun `move and trash rewrite the parent`() = runTest {
        val api = client()
        val folder = api.putFolder("dest")
        val document = api.putPdf("doc", byteArrayOf(1))

        val moved = api.move(document, Parent.Folder(folder.id))
        assertEquals(Parent.Folder(folder.id), cloud.uploadedMetadata().parent)

        api.trash(moved)
        assertEquals(Parent.Trash, cloud.uploadedMetadata().parent)
    }

    @Test
    fun `star toggles the pinned flag`() = runTest {
        val api = client()
        val document = api.putPdf("doc", byteArrayOf(1))
        val starred = api.star(document, true)
        assertTrue(cloud.uploadedMetadata().pinned)
        api.star(starred, false)
        assertTrue(!cloud.uploadedMetadata().pinned)
    }

    @Test
    fun `setMetadata reaches a field the named edits do not`() = runTest {
        val api = client()
        val document = api.putPdf("doc", byteArrayOf(1))
        api.setMetadata(document, api.getMetadata(document).copy(lastOpenedPage = 7, source = "com.example"))

        val metadata = cloud.uploadedMetadata()
        assertEquals(7, metadata.lastOpenedPage)
        assertEquals("com.example", metadata.source)
        assertEquals("doc", metadata.visibleName, "the rest of the metadata is untouched")
    }

    @Test
    fun `setMetadata marks the change as coming from off the device`() = runTest {
        val api = client()
        val document = api.putPdf("doc", byteArrayOf(1))
        api.setMetadata(document, api.getMetadata(document).copy(version = 41, metadatamodified = false))

        val metadata = cloud.uploadedMetadata()
        assertEquals(42, metadata.version, "the returned version is bumped, not written as given")
        assertEquals(true, metadata.metadatamodified)
    }

    @Test
    fun `an edit returns a ref that can be used directly for the next call`() = runTest {
        val api = client()
        val first = api.putPdf("one", byteArrayOf(1))
        val renamed = api.rename(first, "two")
        val starred = api.star(renamed, true)
        val moved = api.move(starred, Parent.Trash)
        assertEquals(first.id, moved.id)
        assertEquals("two", api.getMetadata(moved).visibleName)
        assertEquals(Parent.Trash, api.getMetadata(moved).parent)
    }

    @Test
    fun `an edit refuses a ref whose id does not match its hash`() = runTest {
        val api = client()
        val target = api.putPdf("target", byteArrayOf(1))
        val other = api.putPdf("other", byteArrayOf(2))

        // the hash names a real item, but paired with a different item's id
        val mismatched = ItemRef(other.id, target.hash)
        assertFailsWith<HashNotFoundException> { api.rename(mismatched, "renamed") }
        assertEquals("target", api.getMetadata(target).visibleName, "the item is untouched")
    }

    @Test
    fun `bulkMove refuses a ref whose id does not match its hash`() = runTest {
        val api = client()
        val target = api.putPdf("target", byteArrayOf(1))
        val other = api.putPdf("other", byteArrayOf(2))

        val mismatched = ItemRef(other.id, target.hash)
        val result = api.bulkMove(listOf(mismatched), Parent.Trash)
        assertEquals(emptyMap(), result.moved)
        assertEquals(setOf(mismatched), result.notFound)
        assertEquals(Parent.Root, api.getMetadata(target).parent, "the item is untouched")
    }

    @Test
    fun `editing an unknown hash reports it rather than corrupting the root`() = runTest {
        val error = assertFailsWith<HashNotFoundException> {
            client().rename(
                ItemRef(ItemId("00000000-0000-4000-8000-000000000000"), FileHash("f".repeat(64))),
                "nope",
            )
        }
        assertEquals("f".repeat(64), error.hash.hex)
    }

    @Test
    fun `updateDocumentContent applies the caller's edit and leaves metadata alone`() = runTest {
        val api = client()
        val document = api.putPdf("doc", byteArrayOf(1))
        api.updateDocumentContent(document) { it.copy(textScale = 2.0, lineHeight = 200) }

        val content = assertIs<DocumentContent>(cloud.uploadedContent())
        assertEquals(2.0, content.textScale)
        assertEquals(200, content.lineHeight)
        assertEquals("doc", cloud.uploadedMetadata().visibleName, "metadata is untouched")
    }

    @Test
    fun `updating with the wrong type is refused`() = runTest {
        val api = client()
        val folder = api.putFolder("a folder")
        val error = assertFailsWith<ValidationException> {
            api.updateDocumentContent(folder) { it }
        }
        assertTrue("Collection" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `updateCollectionContent edits a folder's tags`() = runTest {
        val api = client()
        val folder = api.putFolder("a folder")
        api.updateCollectionContent(folder) {
            it.copy(tags = Tags.Structured(listOf(Tag("archive", 1700000000000))))
        }
        assertEquals(listOf("archive"), assertIs<CollectionContent>(cloud.uploadedContent()).tags.names)
    }

    @Test
    fun `content whose shape contradicts its metadata is reported, not a cast failure`() = runTest {
        // metadata says document, but the content has no fileType so it reads as a folder's
        val ref = cloud.seed(documentMetadata("odd"), CollectionContent())
        val error = assertFailsWith<ValidationException> {
            client().updateDocumentContent(ref) { it }
        }
        assertTrue("Collection" in error.message.orEmpty(), error.message.orEmpty())
    }

    // ---------------- bulk ----------------

    @Test
    fun `bulkMove rewrites many items in a single root write`() = runTest {
        val api = client()
        val folder = api.putFolder("dest")
        val first = api.putPdf("one", byteArrayOf(1))
        val second = api.putPdf("two", byteArrayOf(2))

        val before = cloud.generation
        val moved = api.bulkMove(listOf(first, second), Parent.Folder(folder.id))

        assertEquals(setOf(first, second), moved.moved.keys)
        assertEquals(emptySet(), moved.notFound)
        assertEquals(before + 1, cloud.generation, "one root write, not one per item")
        assertEquals(3, cloud.rootEntries().size, "the folder and both documents remain")
        for (newRef in moved.moved.values) {
            assertEquals(Parent.Folder(folder.id), api.getMetadata(newRef).parent)
        }
    }

    @Test
    fun `bulkTrash moves everything to the trash`() = runTest {
        val api = client()
        val first = api.putPdf("one", byteArrayOf(1))
        val second = api.putPdf("two", byteArrayOf(2))
        val moved = api.bulkTrash(listOf(first, second))
        for (newRef in moved.moved.values) {
            assertEquals(Parent.Trash, api.getMetadata(newRef).parent)
        }
    }

    @Test
    fun `bulkMove reports refs it could not find instead of dropping them`() = runTest {
        val api = client()
        val folder = api.putFolder("dest")
        val present = api.putPdf("one", byteArrayOf(1))
        val absent = ItemRef(
            ItemId("00000000-0000-4000-8000-000000000000"),
            FileHash("a".repeat(64)),
        )

        val result = api.bulkMove(listOf(present, absent), Parent.Folder(folder.id))
        assertEquals(setOf(present), result.moved.keys)
        assertEquals(setOf(absent), result.notFound, "a missing ref must not vanish silently")
    }

    // ---------------- generation conflicts ----------------

    @Test
    fun `a lost race is retried and then succeeds`() = runTest {
        val api = client(maxGenerationRetries = 3)
        val document = api.putPdf("doc", byteArrayOf(1))

        cloud.rejectNextRootWrite()
        api.rename(document, "renamed after a conflict")
        assertEquals("renamed after a conflict", cloud.uploadedMetadata().visibleName)
    }

    @Test
    fun `a lost race is surfaced once the retries are used up`() = runTest {
        val api = client(maxGenerationRetries = 0)
        val document = api.putPdf("doc", byteArrayOf(1))

        cloud.rejectNextRootWrite()
        assertFailsWith<GenerationException> { api.rename(document, "never lands") }
    }

    @Test
    fun `retrying a conflict does not re-upload blobs it already sent`() = runTest {
        val api = client(maxGenerationRetries = 3)
        val pdf = "some bytes".toByteArray()
        val document = api.putPdf("doc", pdf)
        val pdfHash = sha256Hex(pdf)
        assertEquals(1, cloud.uploads[pdfHash])

        cloud.rejectNextRootWrite()
        api.rename(document, "renamed")
        assertEquals(1, cloud.uploads[pdfHash], "the pdf is unchanged by a rename")
    }

    // ---------------- reads ----------------

    @Test
    fun `getPdf returns the stored bytes and a missing component is reported`() = runTest {
        val api = client()
        val pdf = "%PDF fake".toByteArray()
        val ref = api.putPdf("doc", pdf)
        assertContentEquals(pdf, api.getPdf(ref))

        val error = assertFailsWith<ComponentNotFoundException> { api.getEpub(ref) }
        assertEquals(DocumentComponent.Epub, error.component)
    }

    @Test
    fun `getContent and getMetadata read the item's own files`() = runTest {
        val api = client()
        val ref = api.putPdf("named", byteArrayOf(1))
        assertEquals("named", api.getMetadata(ref).visibleName)
        assertIs<DocumentContent>(api.getContent(ref))
    }

    @Test
    fun `the component files are reachable through the raw client`() = runTest {
        val api = client()
        val pdf = "%PDF".toByteArray()
        val ref = api.putPdf("doc", pdf)

        val entries = api.raw.getEntries("${ref.id.value}$SCHEMA_SUFFIX", ref.hash).entries
        assertEquals(
            setOf("content", "metadata", "pagedata", "pdf"),
            entries.map { it.id.substringAfterLast('.') }.toSet(),
        )
        val pdfEntry = entries.single { it.id.endsWith(".pdf") }
        assertContentEquals(pdf, api.raw.getBlob(pdfEntry.id, pdfEntry.hash))
    }

    @Test
    fun `getRawPages returns each page's bytes keyed by page id`() = runTest {
        val firstPage = byteArrayOf(0x72, 0x65, 0x4D, 0x61, 0x72, 0x6B)
        val secondPage = byteArrayOf(9, 9, 9)
        val ref = cloud.seed(
            documentMetadata("a notebook"),
            documentContent(FileType.Notebook).copy(pages = listOf("page-a", "page-b")),
            extraFiles = mapOf(
                "page-a.rm" to firstPage,
                "page-b.rm" to secondPage,
                // per-page json is not a stroke file
                "page-a-metadata.json" to "{}".toByteArray(),
            ),
        )
        val pages = client().getRawPages(ref)
        assertEquals(setOf("page-a", "page-b"), pages.keys)
        assertContentEquals(firstPage, pages.getValue("page-a"))
        assertContentEquals(secondPage, pages.getValue("page-b"))
    }

    @Test
    fun `getPages parses each page's strokes`() = runTest {
        val page = java.io.ByteArrayOutputStream().apply {
            val text = "reMarkable .lines file, version=3"
            write(text.toByteArray(Charsets.US_ASCII))
            repeat(43 - text.length) { write(' '.code) }
            fun int(value: Int) = write(
                java.nio.ByteBuffer.allocate(4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array(),
            )
            fun float(value: Float) = write(
                java.nio.ByteBuffer.allocate(4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(value).array(),
            )
            int(1); int(1)
            int(4); int(0); int(0); float(2f)
            int(1)
            float(3f); float(4f); float(0f); float(0f); float(0f); float(1f)
        }.toByteArray()

        val ref = cloud.seed(
            documentMetadata("a notebook"),
            documentContent(FileType.Notebook).copy(pages = listOf("page-a")),
            extraFiles = mapOf("page-a.rm" to page),
        )
        val parsed = client().parsedPages(ref)
        val stroke = assertIs<RmFile.Lines>(parsed.getValue("page-a"))
            .layers.single().strokes.single()
        assertEquals(RmPen.Fineliner, stroke.pen)
        assertEquals(3f, stroke.points.single().x)
    }

    @Test
    fun `a template is identified by its metadata and read from its template file`() = runTest {
        val id = java.util.UUID.randomUUID().toString()
        val definition = """
            {"name":"grid","author":"reMarkable","iconData":"c3Zn","categories":["Planning"],
             "orientation":"portrait","templateVersion":"1.0.0","formatVersion":1,
             "items":[{"type":"line"}],"constants":[{"offsetX":121},
             {"lineWidth":"templateWidth - (offsetX * 2)"}],"supportedScreens":["rm2"]}
        """.trimIndent()
        val ref = cloud.seed(
            Metadata(
                visibleName = "a template",
                parent = Parent.Root,
                pinned = false,
                type = EntryType.Template,
                lastModified = "1700000000000",
                source = "com.remarkable.methods",
                new = true,
            ),
            CollectionContent(),
            id = id,
            extraFiles = mapOf("$id.template" to definition.toByteArray()),
        )

        val api = client()
        val metadata = api.metadataByRef().values.single()
        assertEquals(EntryType.Template, metadata.type, "metadata is what says it is a template")
        assertEquals("com.remarkable.methods", metadata.source)

        val loaded = api.getTemplate(ref)
        assertEquals("grid", loaded.name)
        assertEquals("1.0.0", loaded.templateVersion)
        assertEquals(2, loaded.constants?.size)
    }

    // ---------------- archives ----------------

    @Test
    fun `a document survives an export and import round trip`() = runTest {
        val api = client()
        val pdf = "%PDF original".toByteArray()
        val original = api.putPdf("original", pdf)

        val restored = api.importArchive(api.exportArchive(original))

        assertNotEquals(original.id, restored.id, "a restore gets a fresh id by default")
        assertContentEquals(pdf, api.getPdf(restored))
        assertEquals("original", api.getMetadata(restored).visibleName)
        assertEquals(2, cloud.rootEntries().size)

        val paths = api.raw
            .getEntries("${restored.id.value}$SCHEMA_SUFFIX", restored.hash).entries.map { it.id }
        assertTrue(
            paths.all { it.startsWith(restored.id.value) },
            "every archived path is rewritten to the new id: $paths",
        )
    }

    @Test
    fun `importArchive can override the name, parent, and id`() = runTest {
        val api = client()
        val folder = api.putFolder("dest")
        val archive = api.exportArchive(api.putPdf("original", byteArrayOf(1)))

        val keepId = ItemId(java.util.UUID.randomUUID().toString())
        val restored = api.importArchive(
            archive,
            ImportOptions(
                parent = Parent.Folder(folder.id),
                visibleName = "renamed on restore",
                id = keepId,
            ),
        )
        assertEquals(keepId, restored.id)
        val metadata = api.getMetadata(restored)
        assertEquals("renamed on restore", metadata.visibleName)
        assertEquals(Parent.Folder(folder.id), metadata.parent)
    }

    @Test
    fun `an archive without metadata is refused`() = runTest {
        val empty = java.io.ByteArrayOutputStream().also {
            java.util.zip.ZipOutputStream(it).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("something.txt"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }.toByteArray()
        assertFailsWith<ValidationException> { client().importArchive(empty) }
    }

    // ---------------- upload family ----------------

    @Test
    fun `the upload family hands the file to the ingestion endpoint`() = runTest {
        val api = client()
        api.uploadPdf("a pdf", byteArrayOf(1))
        api.uploadEpub("an epub", byteArrayOf(2))
        api.uploadFolder("a folder")

        assertEquals(3, cloud.received.count { it.path == "/doc/v2/files" })
        assertEquals(
            0,
            cloud.received.count { it.path == "/sync/v3/root" },
            "ingestion does not touch the root index",
        )
    }

    // ---------------- root and cache ----------------

    @Test
    fun `refreshRoot picks up a change made by someone else`() = runTest {
        val api = client()
        assertEquals(emptyMap(), api.metadataByRef())

        cloud.seed(documentMetadata("added behind our back"), documentContent())
        assertEquals(emptyMap(), api.metadataByRef(), "the cached root is still the old one")

        api.refreshRoot()
        assertEquals(1, api.metadataByRef().size)
    }

    @Test
    fun `a text blob is served from cache on the second read`() = runTest {
        val api = client()
        val ref = api.putPdf("doc", byteArrayOf(1))
        api.getMetadata(ref)
        val before = cloud.received.count { it.method == "GET" }
        api.getMetadata(ref)
        assertEquals(before, cloud.received.count { it.method == "GET" }, "the second read is cached")
    }

    @Test
    fun `reading a binary blob as text does not corrupt a later read of its bytes`() = runTest {
        val binary = "reMarkable .lines file, version=6".toByteArray() +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x81.toByte(), 0xC0.toByte())
        val ref = cloud.seed(
            documentMetadata("a notebook"),
            documentContent(FileType.Notebook),
            extraFiles = mapOf("page-a.rm" to binary),
        )
        val raw = client().raw
        val page = raw.getEntries("${ref.id.value}$SCHEMA_SUFFIX", ref.hash).entries
            .single { it.id.endsWith(".rm") }

        val asText = raw.getText(page.id, page.hash)
        assertTrue(
            !asText.toByteArray(Charsets.UTF_8).contentEquals(binary),
            "the premise of this test is that decoding these bytes is lossy",
        )
        assertContentEquals(binary, raw.getBlob(page.id, page.hash), "a lossy read must not poison the blob")
        assertEquals(page.hash.hex, sha256Hex(raw.getBlob(page.id, page.hash)))
    }

    @Test
    fun `re-staging a text blob as a plain file does not discard the cached text`() = runTest {
        val raw = client().raw
        val text = """{"visibleName":"x"}"""
        val staged = raw.stageText("doc.metadata", text)
        raw.upload(staged)
        raw.upload(raw.stageFile("doc.metadata", text.toByteArray()))

        val before = cloud.received.size
        assertEquals(text, raw.getText("doc.metadata", staged.entry.hash))
        assertEquals(before, cloud.received.size, "the text should still be served from cache")
    }

    @Test
    fun `the cache round trips through a dump and back into a new session`() = runTest {
        val api = client()
        val ref = api.putPdf("doc", byteArrayOf(1))
        val dump = api.dumpCache()

        val warm = session(
            SessionToken("session-token"),
            SessionOptions(
                rawHost = cloud.host,
                uploadHost = cloud.host,
                cache = dump,
                httpClient = http,
            ),
        )
        val before = cloud.received.size
        assertEquals("doc", warm.getMetadata(ref).visibleName)
        assertTrue(
            cloud.received.size - before < 3,
            "a warm cache should avoid refetching the item's files",
        )
    }

    private fun cachedHashes(api: RemarkableClient): Set<String> {
        val dump = Json.parseToJsonElement(api.dumpCache()).jsonObject
        val text = (dump.getValue("text") as JsonObject).keys
        val exists = (dump.getValue("exists") as JsonArray).map { it.jsonPrimitive.content }
        return text + exists
    }

    @Test
    fun `clearCache empties the cache`() = runTest {
        val api = client()
        api.putPdf("doc", byteArrayOf(1))
        assertTrue(cachedHashes(api).isNotEmpty())
        api.clearCache()
        assertEquals(emptySet(), cachedHashes(api))
    }

    @Test
    fun `pruneCache drops the hashes the root can no longer reach`() = runTest {
        val api = client()
        val original = api.putPdf("doc", byteArrayOf(1))
        val superseded = original.hash.hex
        api.rename(original, "renamed")

        assertTrue(superseded in cachedHashes(api), "the old index should still be cached")
        api.pruneCache()
        assertTrue(
            superseded !in cachedHashes(api),
            "the superseded item index is unreachable and should have been dropped",
        )
        assertEquals("renamed", api.getMetadata(api.listRefs().single()).visibleName)
    }

    // ---------------- account shapes ----------------

    @Test
    fun `a schema 3 account gets schema 3 item indexes and a schema 4 root`() = runTest {
        val legacy = MockCloud(schemaVersion = SchemaVersion.V3)
        try {
            val api = client(server = legacy)
            val ref = api.putPdf("doc", byteArrayOf(1))

            val itemIndex = legacy.blob(ref.hash.hex)!!.toString(Charsets.UTF_8)
            assertTrue(itemIndex.startsWith("3\n"), "item index was: ${itemIndex.take(20)}")

            val rootIndex = legacy.blob(legacy.rootHash)!!.toString(Charsets.UTF_8)
            assertTrue(rootIndex.startsWith("4\n0:.:"), "root index was: ${rootIndex.take(20)}")

            // a schema 3 index is addressed by its entries' hashes, not by its own bytes
            val entries = parseEntryIndex(itemIndex).entries
            assertEquals(
                sha256Hex(entries.fold(ByteArray(0)) { acc, e -> acc + e.hash.hex.hexToBytes() }),
                ref.hash.hex,
            )

            assertEquals("doc", api.getMetadata(ref).visibleName)
            val renamed = api.rename(ref, "renamed")
            assertEquals("renamed", api.getMetadata(renamed).visibleName)
        } finally {
            legacy.close()
        }
    }

    @Test
    fun `every request carries the session token`() = runTest {
        val api = client()
        val ref = api.putPdf("doc", byteArrayOf(1))
        api.rename(ref, "renamed")
        api.uploadPdf("ingested", byteArrayOf(2))

        assertTrue(cloud.received.size > 5, "expected a spread of calls: ${cloud.received.size}")
        assertEquals(
            setOf<String?>("Bearer session-token"),
            cloud.authHeaders.toSet(),
            "every call, on every host, must be authenticated",
        )
    }
}

/**
 * The listing the client no longer provides.
 *
 * Both of these were once methods; each was one line over calls that remain, so they carry
 * their own composition here instead of in the public api.
 */
private suspend fun RemarkableClient.metadataByRef(): Map<ItemRef, Metadata> =
    listRefs().associateWith { getMetadata(it) }

private suspend fun RemarkableClient.parsedPages(ref: ItemRef): Map<String, RmFile> =
    getRawPages(ref).mapValues { (_, bytes) -> parseRmFile(bytes) }
