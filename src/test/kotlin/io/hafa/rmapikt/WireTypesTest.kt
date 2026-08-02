package io.hafa.rmapikt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The corners of the content schema that a simple document never exercises: the speculative
 * nested types, page tags, and the device tables.
 */
class WireTypesTest {
    private val richDocument = """
        {"coverPageNumber":2,"documentMetadata":{"authors":["a","b"],"title":"t",
          "publicationDate":"2024-01-01","publisher":"p"},
         "extraMetadata":{"LastPen":"Finelinerv2","LastTool":"Eraser"},
         "fileType":"notebook","fontName":"Noto Serif","lineHeight":150,
         "orientation":"landscape","pageCount":3,"textAlignment":"left","textScale":1.2,
         "formatVersion":1,"margins":200,"lastOpenedPage":2,"dummyDocument":false,
         "originalPageCount":3,"sizeInBytes":"4096","redirectionPageMap":[0,1,2],
         "pages":["p1","p2","p3"],
         "pageTags":[{"name":"important","pageId":"p2","timestamp":1700000000000}],
         "tags":[{"name":"work","timestamp":1700000000000}],
         "zoomMode":"customFit","customZoomScale":1.5,"customZoomCenterX":-10.0,
         "customZoomCenterY":20.5,"customZoomPageWidth":1404.0,"customZoomPageHeight":1872.0,
         "customZoomOrientation":"portrait","viewBackgroundFilter":"off",
         "transform":{"m11":1.0,"m22":1.0,"m33":1.0},
         "keyboardMetadata":{"count":7,"timestamp":1700000000000.0},
         "cPages":{"lastOpened":{"timestamp":"1:1","value":"p2"},
                   "original":{"timestamp":"1:1","value":-1.0},
                   "pages":[{"id":"p1","idx":{"timestamp":"1:1","value":"ba"},
                             "template":{"timestamp":"1:1","value":"Blank"},
                             "redir":{"timestamp":"1:1","value":0.0},
                             "scrollTime":{"timestamp":"1:1","value":"2024-01-01T00:00:00Z"},
                             "verticalScroll":{"timestamp":"1:1","value":12.5},
                             "deleted":{"timestamp":"1:1","value":0.0}}],
                   "uuids":[{"first":"abc","second":1}]}}
    """.trimIndent()

    @Test
    fun `a document using every modelled field round trips`() {
        val decoded = decodeWire(ContentSerializer, richDocument, "content")
        val document = assertIs<DocumentContent>(decoded)

        assertEquals(listOf("a", "b"), document.documentMetadata.authors)
        assertEquals("Finelinerv2", document.extraMetadata["LastPen"])
        assertEquals(listOf(0, 1, 2), document.redirectionPageMap)
        assertEquals(listOf("important"), document.pageTags?.map { it.name })
        assertEquals("p2", document.pageTags?.single()?.pageId)
        assertEquals(Transform(m11 = 1.0, m22 = 1.0, m33 = 1.0), document.transform)
        assertEquals(7, document.keyboardMetadata?.count)
        assertEquals(ZoomMode.CustomFit, document.zoomMode)
        assertEquals(BackgroundFilter.Off, document.viewBackgroundFilter)

        val cPages = requireNotNull(document.cPages)
        assertEquals("p2", cPages.lastOpened.value)
        assertEquals(listOf(CPageUUID("abc", 1)), cPages.uuids)
        val page = cPages.pages.single()
        assertEquals("ba", page.idx.value)
        assertEquals("Blank", page.template?.value)
        assertEquals(12.5, page.verticalScroll?.value)
        assertEquals("2024-01-01T00:00:00Z", page.scrollTime?.value)

        // re-encoding and re-decoding must land on exactly the same value
        val reencoded = encodeWire(ContentSerializer, document)
        assertEquals(document, decodeWire(ContentSerializer, reencoded, "content"))
    }

    @Test
    fun `an absent nested type stays absent through a round trip`() {
        val minimal = """
            {"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},"fileType":"pdf",
             "fontName":"","lineHeight":-1,"orientation":"portrait","pageCount":1,
             "textAlignment":"","textScale":1}
        """.trimIndent()
        val document = assertIs<DocumentContent>(decodeWire(ContentSerializer, minimal, "content"))
        assertNull(document.cPages)
        assertNull(document.transform)
        assertNull(document.keyboardMetadata)
        assertNull(document.pageTags)

        val encoded = Json.parseToJsonElement(encodeWire(ContentSerializer, document)).jsonObject
        assertTrue("cPages" !in encoded)
        assertTrue("transform" !in encoded)
        assertTrue("keyboardMetadata" !in encoded)
    }

    @Test
    fun `a template definition round trips, including expression constants`() {
        // constants mix numbers and expressions over other constants, so they stay raw json
        val template = """
            {"name":"grid","author":"reMarkable","iconData":"c3Zn","categories":["Planning"],
             "labels":["Project management"],"orientation":"portrait",
             "templateVersion":"1.2.3","formatVersion":1,"supportedScreens":["rm2","rmPP"],
             "constants":[{"offsetX":121},{"lineWidth":"templateWidth - (offsetX * 2)"}],
             "items":[{"type":"line","x":1},{"type":"line","x":2}],"id":"template-1"}
        """.trimIndent()
        val decoded = decodeWire(TemplateDefinition.serializer(), template, "template")
        assertEquals("grid", decoded.name)
        assertEquals(2, decoded.constants?.size)
        assertEquals(2, decoded.items.size)
        assertEquals("template-1", decoded.id)
        assertEquals(
            decoded,
            decodeWire(
                TemplateDefinition.serializer(),
                encodeWire(TemplateDefinition.serializer(), decoded),
                "t",
            ),
        )
    }

    @Test
    fun `a template without supportedScreens or labels still parses`() {
        // both are absent on real templates, despite the names suggesting otherwise
        val template = """
            {"name":"paper","author":"reMarkable","iconData":"","categories":["Paper"],
             "orientation":"portrait","templateVersion":"1.0.0","formatVersion":1,
             "constants":[{"offsetX":121}],"items":[]}
        """.trimIndent()
        val decoded = decodeWire(TemplateDefinition.serializer(), template, "template")
        assertNull(decoded.supportedScreens)
        assertNull(decoded.labels)
    }

    @Test
    fun `metadata round trips every optional field`() {
        val text = """
            {"visibleName":"n","parent":"trash","pinned":true,"type":"TemplateType",
             "lastModified":"1700000000000","lastOpened":"1700000000001","lastOpenedPage":4,
             "createdTime":"1699999999999","deleted":false,"metadatamodified":true,
             "modified":true,"synced":false,"version":3,"new":true,"source":"com.remarkable"}
        """.trimIndent()
        val metadata = decodeWire(Metadata.serializer(), text, "metadata")
        assertEquals(Parent.Trash, metadata.parent)
        assertEquals(EntryType.Template, metadata.type)
        assertEquals(4, metadata.lastOpenedPage)
        assertEquals(3, metadata.version)
        assertEquals("com.remarkable", metadata.source)
        assertEquals(
            metadata,
            decodeWire(Metadata.serializer(), encodeWire(Metadata.serializer(), metadata), "m"),
        )
    }

    @Test
    fun `every known device reports its screen`() {
        assertEquals(DeviceModel.entries.toSet(), deviceScreens.keys)
        val paperPro = deviceScreens.getValue(DeviceModel.RM02A)
        assertEquals("reMarkable Paper Pro", paperPro.name)
        assertEquals(1620, paperPro.width)
        assertEquals(2160, paperPro.height)
        assertEquals(229, paperPro.dpi)

        // every model is 3:4 except the Paper Pro Move, which is 9:16
        for ((model, screen) in deviceScreens) {
            val ratio = screen.width.toDouble() / screen.height
            val expected = if (model == DeviceModel.RM03A) 9.0 / 16 else 0.75
            assertTrue(kotlin.math.abs(ratio - expected) < 0.01, "$model had ratio $ratio")
        }
    }

    @Test
    fun `constructing a value class with a bad value is a caller error`() {
        // a literal the caller got wrong is a programming mistake, not a payload that
        // didn't match, so it must not masquerade as the cloud having changed
        assertFailsWith<IllegalArgumentException> { FileHash("abc") }
        assertFailsWith<IllegalArgumentException> { ItemId("not-a-uuid") }
        // upper case hex is not what the protocol writes
        assertFailsWith<IllegalArgumentException> { FileHash("A".repeat(64)) }
    }

    @Test
    fun `the same bad value off the wire is a validation failure carrying the text`() {
        val hash = assertFailsWith<ValidationException> { FileHash.ofWire("abc") }
        assertEquals("abc", hash.rawText)
        val id = assertFailsWith<ValidationException> { ItemId.ofWire("not-a-uuid") }
        assertEquals("not-a-uuid", id.rawText)
        assertFailsWith<ValidationException> { Parent.ofWire("not-a-uuid") }
    }

    @Test
    fun `a parent round trips through its wire value`() {
        val folder = Parent.Folder(ItemId("11111111-2222-3333-4444-555555555555"))
        assertEquals("", Parent.Root.wire)
        assertEquals("trash", Parent.Trash.wire)
        assertEquals("11111111-2222-3333-4444-555555555555", folder.wire)
        assertEquals(Parent.Root, Parent.ofWire(""))
        assertEquals(Parent.Trash, Parent.ofWire("trash"))
        assertEquals(folder, Parent.ofWire(folder.wire))
    }
}
