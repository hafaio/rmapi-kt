package io.hafa.rmapikt

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fixtures here are assembled byte by byte from the published description of the
 * format rather than produced by a writer in this library. A writer would only prove the
 * parser agrees with itself; laying the bytes out explicitly states what the file is
 * claimed to look like, so a wrong assumption shows up as a wrong constant.
 */
class RmParsingTest {
    private class RmWriter {
        private val out = ByteArrayOutputStream()

        fun header(version: Int) = apply {
            val text = "reMarkable .lines file, version=$version"
            out.write(text.toByteArray(Charsets.US_ASCII))
            repeat(43 - text.length) { out.write(' '.code) }
        }

        fun int(value: Int) = apply {
            out.write(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(),
            )
        }

        fun float(value: Float) = apply {
            out.write(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array(),
            )
        }

        fun byte(value: Int) = apply { out.write(value) }

        fun raw(bytes: ByteArray) = apply { out.write(bytes) }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun point(writer: RmWriter, x: Float, y: Float) = writer
        .float(x).float(y).float(1.5f).float(2.5f).float(3.5f).float(0.75f)

    @Test
    fun `a version 3 file decodes into layers, strokes, and points`() {
        val file = RmWriter().header(3)
            .int(2)                       // two layers
            .int(1)                       // layer 1: one stroke
            .int(4).int(0).int(0)         // fineliner, black, unused word
            .float(2.125f)                // width
            .int(2)                       // two points
        point(file, 10f, 20f)
        point(file, 11f, 21f)
        file.int(0)                       // layer 2: no strokes

        val parsed = parseRmFile(file.bytes())
        val lines = parsed as RmFile.Lines
        assertEquals(3, lines.version)
        assertEquals(2, lines.layers.size)

        val stroke = lines.layers[0].strokes.single()
        assertEquals(RmPen.Fineliner, stroke.pen)
        assertEquals(RmColor.Black, stroke.color)
        assertEquals(2.125f, stroke.width)
        assertEquals(
            listOf(
                RmPoint(10f, 20f, 1.5f, 2.5f, 3.5f, 0.75f),
                RmPoint(11f, 21f, 1.5f, 2.5f, 3.5f, 0.75f),
            ),
            stroke.points,
        )
        assertEquals(emptyList(), lines.layers[1].strokes)
    }

    @Test
    fun `a version 5 stroke header carries one extra word before the points`() {
        val file = RmWriter().header(5)
            .int(1)
            .int(1)
            .int(17).int(6).int(0)        // v5 fineliner, blue, unused word
            .float(1.0f)
            .int(0)                       // the extra v5 word
            .int(1)
        point(file, 5f, 6f)

        val lines = parseRmFile(file.bytes()) as RmFile.Lines
        assertEquals(5, lines.version)
        val stroke = lines.layers.single().strokes.single()
        assertEquals(RmPen.FinelinerV5, stroke.pen)
        assertEquals(RmColor.Blue, stroke.color)
        assertEquals(5f, stroke.points.single().x)
    }

    @Test
    fun `reading the v5 layout as v3 would misplace the points, so the version matters`() {
        // the same bytes as the v5 test, but claiming version 3: the extra word is then read
        // as the point count, which must not silently produce a plausible stroke
        val file = RmWriter().header(3)
            .int(1).int(1)
            .int(17).int(6).int(0)
            .float(1.0f)
            .int(0)
            .int(1)
        point(file, 5f, 6f)

        val lines = parseRmFile(file.bytes()) as RmFile.Lines
        // read as v3 the stroke has zero points, not the one point that is really there
        assertEquals(0, lines.layers.single().strokes.single().points.size)
    }

    @Test
    fun `an unrecognised tool is refused rather than read as a stroke`() {
        val file = RmWriter().header(3)
            .int(1).int(1)
            .int(9999).int(0).int(0)
            .float(1f)
            .int(0)

        val failure = assertFailsWith<ValidationException> { parseRmFile(file.bytes()) }
        assertEquals("unknown pen code 9999", failure.message)
    }

    @Test
    fun `an unrecognised colour is refused the same way`() {
        val file = RmWriter().header(3)
            .int(1).int(1)
            .int(4).int(4242).int(0)
            .float(1f)
            .int(0)

        val failure = assertFailsWith<ValidationException> { parseRmFile(file.bytes()) }
        assertEquals("unknown colour code 4242", failure.message)
    }

    @Test
    fun `the codes a sweep of a real account turned up have names`() {
        val file = RmWriter().header(3).int(1).int(5)
        for (color in 9..13) {
            file.int(23).int(color).int(0).float(1f).int(0)
        }

        val strokes = (parseRmFile(file.bytes()) as RmFile.Lines).layers.single().strokes
        assertEquals(List(5) { RmPen.Shader }, strokes.map { it.pen })
        assertEquals(
            listOf(
                RmColor.Highlight,
                RmColor.GreenPaperPro,
                RmColor.Cyan,
                RmColor.Magenta,
                RmColor.YellowPaperPro,
            ),
            strokes.map { it.color },
        )
    }

    @Test
    fun `a version 6 file is framed into blocks with their payloads intact`() {
        // block types this library does not interpret, so the framing is what is under test
        val firstPayload = byteArrayOf(1, 2, 3, 4, 5)
        val secondPayload = byteArrayOf(9)
        val file = RmWriter().header(6)
            .int(firstPayload.size).byte(0).byte(1).byte(2).byte(0x0A).raw(firstPayload)
            .int(secondPayload.size).byte(0).byte(1).byte(1).byte(0x09).raw(secondPayload)

        val blocks = (parseRmFile(file.bytes()) as RmFile.Scene).blocks
        assertEquals(2, blocks.size)
        assertEquals(0x0A, blocks[0].type)
        assertEquals(1, blocks[0].minVersion)
        assertEquals(2, blocks[0].currentVersion)
        assertContentEquals(firstPayload, blocks[0].payload)
        assertEquals(0x09, blocks[1].type)
        assertContentEquals(secondPayload, blocks[1].payload)
    }

    /**
     * Builds a version 6 payload in the tagged encoding: every field is introduced by a
     * varint of `index shl 4 or tagType`. The layouts below mirror pages a real device
     * wrote, which is what the decoder was checked against.
     */
    private class V6 {
        private val out = ByteArrayOutputStream()

        fun tag(index: Int, type: Int) = apply { out.write((index shl 4) or type) }

        fun varuint(value: Int) = apply {
            var rest = value
            while (rest >= 0x80) {
                out.write((rest and 0x7F) or 0x80)
                rest = rest ushr 7
            }
            out.write(rest)
        }

        fun crdt(index: Int, part1: Int, part2: Int) = tag(index, 0xF).apply {
            out.write(part1)
            varuint(part2)
        }

        fun int(index: Int, value: Int) = tag(index, 0x4).apply {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        fun float(index: Int, value: Float) = tag(index, 0x4).apply {
            out.write(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array(),
            )
        }

        fun double(index: Int, value: Double) = tag(index, 0x8).apply {
            out.write(
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array(),
            )
        }

        fun sub(index: Int, payload: ByteArray) = tag(index, 0xC).apply {
            out.write(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(payload.size).array(),
            )
            out.write(payload)
        }

        fun string(value: String) = apply {
            varuint(value.length)
            out.write(1)
            out.write(value.toByteArray(Charsets.UTF_8))
        }

        fun raw(bytes: ByteArray) = apply { out.write(bytes) }

        fun byte(value: Int) = apply { out.write(value) }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun v6Point(x: Float, y: Float, width: Int, pressure: Int): ByteArray =
        ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(x).putFloat(y)
            .putShort(0)                       // speed
            .putShort(width.toShort())
            .put(0)                            // direction
            .put(pressure.toByte())
            .array()

    private fun v6File(blocks: List<Pair<Int, ByteArray>>): ByteArray {
        val file = RmWriter().header(6)
        for ((type, payload) in blocks) {
            file.int(payload.size).byte(0).byte(2).byte(2).byte(type).raw(payload)
        }
        return file.bytes()
    }

    private val treeNodeBlock = 0x02 to V6()
        .crdt(1, 0, 11)
        .sub(2, V6().crdt(1, 0, 12).sub(2, V6().string("Layer 1").bytes()).bytes())
        .bytes()

    private fun lineBlock(tool: Int, color: Int, points: List<ByteArray>) = 0x05 to V6()
        .crdt(1, 0, 11)                       // parent: the layer above
        .crdt(2, 1, 14)                       // this item
        .crdt(3, 0, 0).crdt(4, 0, 0)          // left, right
        .int(5, 0)                            // nothing deleted
        .sub(
            6,
            V6().byte(3)                      // item type 3 = a line
                .int(1, tool).int(2, color)
                .double(3, 2.0)
                .float(4, 0f)
                .sub(5, points.fold(ByteArray(0)) { acc, point -> acc + point })
                .crdt(6, 0, 1)                // timestamp
                .bytes(),
        )
        .bytes()

    @Test
    fun `a version 6 page decodes into named layers of strokes`() {
        val file = v6File(
            listOf(
                treeNodeBlock,
                lineBlock(
                    tool = 17,
                    color = 0,
                    points = listOf(
                        v6Point(-472f, 1029f, width = 14, pressure = 5),
                        v6Point(-422f, 1029f, width = 14, pressure = 255),
                    ),
                ),
            ),
        )

        val scene = parseRmFile(file) as RmFile.Scene
        assertEquals(6, scene.version)
        assertEquals(2, scene.blocks.size, "the raw blocks are kept alongside the decoding")

        val layer = scene.layers.single()
        assertEquals("Layer 1", layer.name)
        val stroke = layer.strokes.single()
        assertEquals(RmPen.FinelinerV5, stroke.pen)
        assertEquals(RmColor.Black, stroke.color)
        assertEquals(2, stroke.points.size)

        // the integer channels of a v6 point are scaled into the same units the older
        // format stores directly
        val first = stroke.points.first()
        assertEquals(-472f, first.x)
        assertEquals(1029f, first.y)
        assertEquals(14 / 4f, first.width)
        assertEquals(5 / 255f, first.pressure)
        assertEquals(1f, stroke.points[1].pressure)
    }

    @Test
    fun `a deleted stroke carries no value and is left out`() {
        // a tombstone: a non-zero deleted length and no value subblock at all, which is
        // exactly what a real device writes for an erased stroke
        val tombstone = 0x05 to V6()
            .crdt(1, 0, 11).crdt(2, 1, 16).crdt(3, 0, 0).crdt(4, 0, 0)
            .int(5, 1)
            .bytes()

        val scene = parseRmFile(
            v6File(
                listOf(
                    treeNodeBlock,
                    tombstone,
                    lineBlock(17, 0, listOf(v6Point(1f, 2f, 8, 128))),
                ),
            ),
        ) as RmFile.Scene

        assertEquals(3, scene.blocks.size)
        assertEquals(1, scene.layers.single().strokes.size, "the erased stroke must not appear")
    }

    @Test
    fun `blocks that are not strokes or layers are kept but not interpreted`() {
        // author ids and page info: real block types this library does not model
        val authorIds = 0x09 to byteArrayOf(0)
        val pageInfo = 0x0A to V6().int(1, 1).int(2, 0).int(3, 0).int(4, 0).int(5, 0).bytes()

        val scene = parseRmFile(v6File(listOf(authorIds, pageInfo))) as RmFile.Scene
        assertEquals(2, scene.blocks.size)
        assertEquals(emptyList(), scene.layers, "no strokes, but the blocks survive")
    }

    @Test
    fun `a stroke whose points do not divide evenly is reported`() {
        val broken = 0x05 to V6()
            .crdt(1, 0, 11).crdt(2, 1, 14).crdt(3, 0, 0).crdt(4, 0, 0)
            .int(5, 0)
            .sub(
                6,
                V6().byte(3).int(1, 17).int(2, 0).double(3, 2.0).float(4, 0f)
                    .sub(5, ByteArray(13))    // not a whole number of 14-byte points
                    .crdt(6, 0, 1).bytes(),
            )
            .bytes()
        val error = assertFailsWith<ValidationException> {
            parseRmFile(v6File(listOf(treeNodeBlock, broken)))
        }
        assertTrue("13" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a truncated stroke is reported rather than half read`() {
        val file = RmWriter().header(3)
            .int(1).int(1)
            .int(4).int(0).int(0)
            .float(1f)
            .int(500)                     // claims 500 points and then stops
        val error = assertFailsWith<ValidationException> { parseRmFile(file.bytes()) }
        assertTrue("500" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a block that overruns the file is reported`() {
        val file = RmWriter().header(6)
            .int(100).byte(0).byte(1).byte(1).byte(0x05).raw(byteArrayOf(1, 2))
        assertFailsWith<ValidationException> { parseRmFile(file.bytes()) }
    }

    @Test
    fun `trailing bytes after the last block are reported`() {
        val file = RmWriter().header(6)
            .int(1).byte(0).byte(1).byte(1).byte(0x05).raw(byteArrayOf(7))
            .raw(byteArrayOf(1, 2, 3))    // not enough for another block header
        assertFailsWith<ValidationException> { parseRmFile(file.bytes()) }
    }

    @Test
    fun `a file that is not an rm file is reported`() {
        assertFailsWith<ValidationException> { parseRmFile("%PDF-1.4 not an rm file at all".toByteArray()) }
        assertFailsWith<ValidationException> { parseRmFile(ByteArray(0)) }
        assertFailsWith<ValidationException> { parseRmFile(ByteArray(43)) }
    }

    @Test
    fun `an unsupported version is refused rather than guessed at`() {
        val error = assertFailsWith<ValidationException> {
            parseRmFile(RmWriter().header(4).int(0).bytes())
        }
        assertTrue("4" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `an absurd count is refused instead of being allocated`() {
        // each of these would previously reach a List(count) preallocation. the point count
        // is the dangerous one: count * 6 * 4 overflows Int, so a naive bounds check passes
        assertFailsWith<ValidationException> {
            parseRmFile(RmWriter().header(3).int(Int.MAX_VALUE).bytes())
        }
        assertFailsWith<ValidationException> {
            parseRmFile(RmWriter().header(3).int(1).int(Int.MAX_VALUE).bytes())
        }
        val overflowingPointCount = RmWriter().header(3)
            .int(1).int(1)
            .int(4).int(0).int(0).float(1f)
            .int(100_000_000)             // 100M * 24 bytes overflows a 32-bit product
        assertFailsWith<ValidationException> { parseRmFile(overflowingPointCount.bytes()) }
    }

    @Test
    fun `an absurd version 6 length is refused instead of being allocated`() {
        // a layer name whose declared length is far past the end of the block
        val hugeString = 0x02 to V6()
            .crdt(1, 0, 11)
            .sub(2, V6().crdt(1, 0, 12).sub(2, V6().varuint(0x7FFFFFF).byte(1).bytes()).bytes())
            .bytes()
        assertFailsWith<ValidationException> { parseRmFile(v6File(listOf(hugeString))) }
    }

    @Test
    fun `a truncated value raises the documented exception rather than a buffer error`() {
        // the header and counts are consistent, but the points run out mid-value
        val truncated = RmWriter().header(3)
            .int(1).int(1)
            .int(4).int(0).int(0).float(1f)
            .int(1)
            .raw(ByteArray(10))           // a point needs 24 bytes
        val error = assertFailsWith<ValidationException> { parseRmFile(truncated.bytes()) }
        assertTrue(error.message.orEmpty().isNotEmpty())
    }

    @Test
    fun `a negative count is refused`() {
        assertFailsWith<ValidationException> {
            parseRmFile(RmWriter().header(3).int(-1).bytes())
        }
    }

    @Test
    fun `an empty drawing has no layers`() {
        val lines = parseRmFile(RmWriter().header(3).int(0).bytes()) as RmFile.Lines
        assertEquals(emptyList(), lines.layers)
    }

    @Test
    fun `a version 3 page written back is byte for byte the file that was read`() {
        val file = RmWriter().header(3)
            .int(2)
            .int(1)
            .int(4).int(0).int(0x5eed)    // a non-zero reserved word, so dropping it shows
            .float(2.125f)
            .int(2)
        point(file, 10f, 20f)
        point(file, 11f, 21f)
        file.int(0)
        val original = file.bytes()

        assertContentEquals(original, serializeRmFile(parseRmFile(original)))
    }

    @Test
    fun `a version 5 page keeps both reserved words through a round trip`() {
        val file = RmWriter().header(5)
            .int(1)
            .int(1)
            .int(17).int(6).int(0x1234)   // the v3 reserved word
            .float(1.0f)
            .int(0x5678)                  // the word v5 added
            .int(1)
        point(file, 5f, 6f)
        val original = file.bytes()

        val stroke = (parseRmFile(original) as RmFile.Lines).layers.single().strokes.single()
        assertEquals(0x1234, stroke.reserved)
        assertEquals(0x5678, stroke.reservedV5)
        assertContentEquals(original, serializeRmFile(parseRmFile(original)))
    }

    @Test
    fun `a page this library builds serialises and reads back the same`() {
        val page = RmFile.Lines(
            version = 5,
            layers = listOf(
                RmLayer(
                    listOf(
                        RmStroke(
                            pen = RmPen.FinelinerV5,
                            color = RmColor.Blue,
                            width = 2.0f,
                            points = listOf(RmPoint(1f, 2f, 3f, 4f, 5f, 6f)),
                        ),
                    ),
                ),
                RmLayer(emptyList()),
            ),
        )
        assertEquals(page, parseRmFile(serializeRmFile(page)))
    }

    @Test
    fun `a version 6 page keeps a non-zero reserved header byte`() {
        val bytes = java.io.ByteArrayOutputStream().apply {
            val text = "reMarkable .lines file, version=6"
            write(text.toByteArray(Charsets.US_ASCII))
            repeat(43 - text.length) { write(' '.code) }
            write(byteArrayOf(2, 0, 0, 0))  // payload length
            write(0x7F)                     // the reserved byte, deliberately not zero
            write(1)                        // min version
            write(1)                        // current version
            write(0x0B)                     // block type
            write(byteArrayOf(9, 9))        // payload
        }.toByteArray()

        val page = assertIs<RmFile.Scene>(parseRmFile(bytes))
        assertEquals(0x7F, page.blocks.single().reserved)
        assertContentEquals(bytes, serializeRmFile(page), "so an untouched page keeps its hash")
    }

    @Test
    fun `a version 6 page is written from its blocks, unchanged`() {
        val original = v6File(listOf(lineBlock(tool = 17, color = 6, points = listOf(v6Point(1f, 2f, 3, 4)))))
        val scene = parseRmFile(original) as RmFile.Scene

        assertContentEquals(original, serializeRmFile(scene))
        assertTrue(scene.layers.isNotEmpty(), "the blocks decoded, so this is a real round trip")
    }
}
