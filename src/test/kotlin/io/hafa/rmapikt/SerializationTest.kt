package io.hafa.rmapikt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding is strict everywhere: a key this library doesn't model is an error rather than
 * something to ignore or carry along. That is what stops a read-modify-write from writing
 * a file back with the unrecognised part missing.
 */
class SerializationTest {
    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun roundTrip(text: String): JsonObject {
        val content = decodeWire(ContentSerializer, text, "content")
        return parse(encodeWire(ContentSerializer, content)).jsonObject
    }

    @Test
    fun `an unmodelled key is rejected rather than dropped`() {
        val error = assertFailsWith<ValidationException> {
            decodeWire(
                ContentSerializer,
                """{"coverPageNumber":-1,"documentMetadata":{},"extraMetadata":{},
                    "fileType":"pdf","fontName":"","lineHeight":-1,"orientation":"portrait",
                    "pageCount":3,"textAlignment":"justify","textScale":1,
                    "someFutureField":{"nested":[1,2]}}""",
                "content",
            )
        }
        assertTrue("someFutureField" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a known firmware quirk key is readable and survives a rewrite`() {
        // reMarkable writes `modifed` — its own misspelling of `modified` — into a minority
        // of documents. Rejecting it made listItems() throw on a real account; modelling it
        // would put a typo in the public api. It is carried instead.
        val original = """
            {"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},"fileType":"pdf",
             "fontName":"","lineHeight":-1,"orientation":"portrait","pageCount":1,
             "textAlignment":"","textScale":1,"modifed":true}
        """.trimIndent()

        val document = assertIs<DocumentContent>(decodeContent(original))
        assertEquals(FileType.Pdf, document.fileType)

        val rewritten = parse(
            encodeContent(document.copy(textScale = 2.0), contentQuirks(original)),
        ).jsonObject
        assertEquals(JsonPrimitive(true), rewritten["modifed"], "the quirk must be written back")
        assertEquals(JsonPrimitive(2.0), rewritten["textScale"], "the edit must still apply")
    }

    @Test
    fun `a quirk key is not invented for content that never had one`() {
        val original = """
            {"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},"fileType":"pdf",
             "fontName":"","lineHeight":-1,"orientation":"portrait","pageCount":1,
             "textAlignment":"","textScale":1}
        """.trimIndent()
        val encoded = parse(
            encodeContent(decodeContent(original), contentQuirks(original)),
        ).jsonObject
        assertTrue("modifed" !in encoded)
    }

    @Test
    fun `a key that is not a known quirk is still rejected`() {
        assertFailsWith<ValidationException> {
            decodeContent(
                """{"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},
                    "fileType":"pdf","fontName":"","lineHeight":-1,"orientation":"portrait",
                    "pageCount":1,"textAlignment":"","textScale":1,"someOtherKey":1}""",
            )
        }
    }

    @Test
    fun `an unmodelled key nested inside a wire type is rejected too`() {
        assertFailsWith<ValidationException> {
            decodeWire(
                ContentSerializer,
                """{"coverPageNumber":0,"documentMetadata":{"title":"t","unknownDocMeta":7},
                    "extraMetadata":{},"fileType":"epub","fontName":"","lineHeight":100,
                    "orientation":"portrait","pageCount":1,"textAlignment":"","textScale":1}""",
                "content",
            )
        }
        assertFailsWith<ValidationException> {
            decodeWire(
                ContentSerializer,
                """{"notACollectionKey":1}""",
                "content",
            )
        }
        // a template's payload is not a .content shape at all, so it is rejected here
        assertFailsWith<ValidationException> {
            decodeWire(
                ContentSerializer,
                """{"name":"n","author":"a","templateVersion":"1.0.0","items":[]}""",
                "content",
            )
        }
    }

    @Test
    fun `a fully modelled document round trips`() {
        val original = """
            {"coverPageNumber":0,"documentMetadata":{"title":"t","authors":["a"]},
             "extraMetadata":{"pen":"fineliner"},"fileType":"epub","fontName":"Noto Sans",
             "lineHeight":100,"orientation":"portrait","pageCount":1,"textAlignment":"",
             "textScale":1,"margins":125,"pages":["p1"],
             "cPages":{"lastOpened":{"timestamp":"1:1","value":"x"},
                       "original":{"timestamp":"1:1","value":-1},
                       "pages":[{"id":"p1","idx":{"timestamp":"1:1","value":"ba"}}],
                       "uuids":null}}
        """.trimIndent()
        val encoded = roundTrip(original)
        assertEquals(parse("""{"pen":"fineliner"}"""), encoded["extraMetadata"])
        assertEquals(parse("""["p1"]"""), encoded["pages"])
        assertEquals(JsonPrimitive(125), encoded["margins"])
    }

    @Test
    fun `a required nullable field keeps its explicit null`() {
        val original = """
            {"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},"fileType":"pdf",
             "fontName":"","lineHeight":-1,"orientation":"landscape","pageCount":1,
             "textAlignment":"left","textScale":1,
             "cPages":{"lastOpened":{"timestamp":"1:1","value":"x"},
                       "original":{"timestamp":"1:1","value":0},"pages":[],"uuids":null}}
        """.trimIndent()
        val cPages = roundTrip(original)["cPages"] as JsonObject
        assertTrue("uuids" in cPages, "uuids is required on the wire, so null must be written")
        assertEquals(JsonNull, cPages["uuids"])
    }

    @Test
    fun `an absent optional field stays absent`() {
        val original = """
            {"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},"fileType":"pdf",
             "fontName":"","lineHeight":-1,"orientation":"portrait","pageCount":1,
             "textAlignment":"","textScale":1}
        """.trimIndent()
        val encoded = roundTrip(original)
        assertTrue("pages" !in encoded, "a field the device never wrote must not appear")
        assertTrue("margins" !in encoded)
        assertTrue("tags" !in encoded, "an empty tag list is the default and must not be written")
    }

    @Test
    fun `legacy string tags round trip as strings`() {
        val original = """
            {"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},"fileType":"pdf",
             "fontName":"","lineHeight":-1,"orientation":"portrait","pageCount":1,
             "textAlignment":"","textScale":1,"tags":["alpha","beta"]}
        """.trimIndent()
        val document = assertIs<DocumentContent>(decodeWire(ContentSerializer, original, "content"))
        assertEquals(Tags.Legacy(listOf("alpha", "beta")), document.tags)
        assertEquals(listOf("alpha", "beta"), document.tags.names)
        assertEquals(
            parse("""["alpha","beta"]"""),
            parse(encodeWire(ContentSerializer, document)).jsonObject["tags"],
            "a legacy payload must not be silently upgraded to the structured form",
        )
    }

    @Test
    fun `structured tags round trip as objects`() {
        val original = """{"tags":[{"name":"alpha","timestamp":1700000000000}]}"""
        val collection =
            assertIs<CollectionContent>(decodeWire(ContentSerializer, original, "content"))
        assertEquals(listOf("alpha"), collection.tags.names)
        assertEquals(
            parse("""[{"name":"alpha","timestamp":1700000000000}]"""),
            parse(encodeWire(ContentSerializer, collection)).jsonObject["tags"],
        )
    }

    @Test
    fun `content is told apart by which keys are present`() {
        assertIs<CollectionContent>(decodeWire(ContentSerializer, "{}", "content"))
        assertIs<DocumentContent>(
            decodeWire(
                ContentSerializer,
                """{"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},
                    "fileType":"notebook","fontName":"","lineHeight":-1,
                    "orientation":"portrait","pageCount":1,"textAlignment":"","textScale":1}""",
                "content",
            ),
        )
    }

    @Test
    fun `every enum encodes the exact string the protocol uses`() {
        // spelled out rather than derived from the enum, so a renamed constant can't
        // quietly change the wire format and still pass
        val zoomModes = mapOf(
            "bestFit" to ZoomMode.BestFit,
            "customFit" to ZoomMode.CustomFit,
            "fitToHeight" to ZoomMode.FitToHeight,
            "fitToWidth" to ZoomMode.FitToWidth,
        )
        for ((wire, expected) in zoomModes) {
            val text = """
                {"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},
                 "fileType":"notebook","fontName":"","lineHeight":-1,"orientation":"landscape",
                 "pageCount":1,"textAlignment":"left","textScale":1,"zoomMode":"$wire"}
            """.trimIndent()
            val decoded = assertIs<DocumentContent>(decodeWire(ContentSerializer, text, "content"))
            assertEquals(expected, decoded.zoomMode)
            assertEquals(JsonPrimitive(wire), roundTrip(text)["zoomMode"])
        }

        val template = """
            {"name":"n","author":"a","iconData":"d","categories":[],
             "orientation":"portrait","templateVersion":"1.0.0","formatVersion":1,
             "supportedScreens":["rm2","rmPP"],"items":[]}
        """.trimIndent()
        val decoded = decodeWire(TemplateDefinition.serializer(), template, "template")
        assertEquals(
            listOf(SupportedScreen.Rm2, SupportedScreen.RmPaperPro),
            decoded.supportedScreens,
        )
    }

    @Test
    fun `metadata maps the special parents both ways`() {
        val cases = mapOf(
            "" to Parent.Root,
            "trash" to Parent.Trash,
            "0c1c8ec1-1cbd-4cd3-b0f7-3b0d6e9b5a4b" to
                Parent.Folder(ItemId("0c1c8ec1-1cbd-4cd3-b0f7-3b0d6e9b5a4b")),
        )
        for ((wire, expected) in cases) {
            val text = """
                {"visibleName":"n","parent":"$wire","pinned":false,"type":"DocumentType",
                 "lastModified":"1700000000000"}
            """.trimIndent()
            val metadata = decodeWire(Metadata.serializer(), text, "metadata")
            assertEquals(expected, metadata.parent)
            assertEquals(
                JsonPrimitive(wire),
                parse(encodeWire(Metadata.serializer(), metadata)).jsonObject["parent"],
            )
        }
    }

    @Test
    fun `metadata omits fields the device never wrote`() {
        val original = """
            {"visibleName":"n","parent":"","pinned":true,"type":"CollectionType",
             "lastModified":"1700000000000"}
        """.trimIndent()
        val metadata = decodeWire(Metadata.serializer(), original, "metadata")
        assertNull(metadata.lastOpened)
        val encoded = parse(encodeWire(Metadata.serializer(), metadata)).jsonObject
        assertTrue("lastOpened" !in encoded)
        assertTrue("version" !in encoded)
    }

    @Test
    fun `an unrecognised enum value reports the value it saw`() {
        val error = assertFailsWith<ValidationException> {
            decodeWire(
                Metadata.serializer(),
                """{"visibleName":"n","parent":"","pinned":false,"type":"MysteryType",
                    "lastModified":"0"}""",
                "metadata",
            )
        }
        assertTrue("MysteryType" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a malformed payload carries the raw text for the caller`() {
        val error = assertFailsWith<ValidationException> {
            decodeWire(ContentSerializer, "not json at all", "content")
        }
        assertEquals("not json at all", error.rawText)
    }

    @Test
    fun `the nested modifed key survives a decode and a rewrite`() {
        val json = """
            {"coverPageNumber":0,"documentMetadata":{},"extraMetadata":{},"fileType":"pdf",
             "fontName":"","lineHeight":-1,"orientation":"portrait","pageCount":1,
             "textAlignment":"","textScale":1,
             "cPages":{"lastOpened":{"timestamp":"1:1","value":"x"},
              "original":{"timestamp":"1:1","value":-1},"uuids":[],
              "pages":[{"id":"p1","idx":{"timestamp":"1:1","value":"ba"},
               "modifed":"1700000000000"}]}}
        """.trimIndent()
        val decoded = assertIs<DocumentContent>(decodeContent(json))
        assertEquals("1700000000000", decoded.cPages?.pages?.single()?.modifed)
        assertTrue("\"modifed\"" in encodeContent(decoded), "and goes back out unchanged")
    }
}
