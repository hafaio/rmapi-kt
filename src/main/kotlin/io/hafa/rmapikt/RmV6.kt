package io.hafa.rmapikt

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Field tags in a version 6 payload.
 *
 * Every field is introduced by a varint header of `index shl 4 or tagType`, where the index
 * identifies the field and the tag says how wide it is. Reading a field therefore never
 * needs to know the layout in advance, which is what lets an unrecognised field be skipped
 * instead of derailing the parse.
 */
private const val BLOCK_HEADER_LENGTH = 8

private const val TAG_BYTE4 = 0x4
private const val TAG_BYTE8 = 0x8
private const val TAG_LENGTH4 = 0xC
private const val TAG_ID = 0xF

private const val ITEM_TYPE_LINE = 3
private const val POINT_SIZE_V2 = 14
private const val POINT_SIZE_V1 = 24

/** scaling the device applies to the integer channels of a version 2 point */
private const val SPEED_SCALE = 4.0f
private const val WIDTH_SCALE = 4.0f
private const val DIRECTION_DEGREES = 360.0f
private const val BYTE_RANGE = 255.0f

internal const val BLOCK_TREE_NODE = 0x02
internal const val BLOCK_SCENE_LINE_ITEM = 0x05

/** the id of a value in the document's shared-editing history */
internal data class CrdtId(val part1: Int, val part2: Long)

/**
 * Reads the tagged encoding a version 6 payload is written in.
 *
 * Deliberately strict: a tag that isn't where the layout says it should be raises rather
 * than being skipped past, because a misread here yields strokes that look real.
 */
@Suppress("TooManyFunctions") // one small accessor per tag type, which is the point
private class TaggedReader(private val buffer: ByteBuffer) {
    fun byte(): Int = buffer.get().toInt() and 0xFF

    fun varuint(): Long {
        var shift = 0
        var value = 0L
        while (true) {
            val next = byte()
            value = value or ((next and 0x7F).toLong() shl shift)
            if (next and 0x80 == 0) {
                return value
            }
            shift += 7
        }
    }

    /** True when the next field carries [index]; used for fields that may be absent. */
    fun hasField(index: Int): Boolean {
        if (buffer.remaining() < 1) {
            return false
        }
        val mark = buffer.position()
        val header = varuint()
        // through Buffer, not ByteBuffer: the covariant override that returns ByteBuffer
        // arrived in jdk 9 and does not exist on the android floor this library targets
        (buffer as Buffer).position(mark)
        return (header shr 4).toInt() == index
    }

    private fun header(index: Int, tag: Int) {
        val value = varuint()
        val actualIndex = (value shr 4).toInt()
        val actualTag = (value and 0xF).toInt()
        if (actualIndex != index || actualTag != tag) {
            throw ValidationException(
                "expected field $index with tag $tag but found $actualIndex with tag $actualTag",
            )
        }
    }

    fun crdtId(index: Int): CrdtId {
        header(index, TAG_ID)
        return CrdtId(byte(), varuint())
    }

    fun int(index: Int): Int {
        header(index, TAG_BYTE4)
        return buffer.int
    }

    fun float(index: Int): Float {
        header(index, TAG_BYTE4)
        return buffer.float
    }

    fun double(index: Int): Double {
        header(index, TAG_BYTE8)
        return buffer.double
    }

    /** Returns the declared length of a nested block, which the caller then reads. */
    fun subblock(index: Int): Int {
        header(index, TAG_LENGTH4)
        return bounded(buffer.int.toLong(), "a subblock")
    }

    /**
     * Rejects a wire-declared length the remaining bytes cannot satisfy.
     *
     * Lengths and counts are read straight off the file, so they are bounded before being
     * used to size anything; without this a crafted or corrupt length reaches an allocation
     * as a negative size or an enormous one.
     */
    private fun bounded(length: Long, what: String): Int {
        if (length < 0 || length > buffer.remaining()) {
            throw ValidationException(
                "$what declared $length bytes, but ${buffer.remaining()} remain",
            )
        }
        return length.toInt()
    }

    fun string(): String {
        val length = varuint()
        // one byte says whether the text is ascii; both cases are read as utf-8, which
        // agrees with ascii on every byte ascii can represent
        byte()
        val bytes = ByteArray(bounded(length, "a string"))
        buffer.get(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    fun readPoint(pointSize: Int): RmPoint = if (pointSize == POINT_SIZE_V2) {
        val x = buffer.float
        val y = buffer.float
        val speed = (buffer.short.toInt() and 0xFFFF) / SPEED_SCALE
        val width = (buffer.short.toInt() and 0xFFFF) / WIDTH_SCALE
        val direction = byte() * DIRECTION_DEGREES / BYTE_RANGE
        val pressure = byte() / BYTE_RANGE
        RmPoint(x, y, speed, direction, width, pressure)
    } else {
        RmPoint(buffer.float, buffer.float, buffer.float, buffer.float, buffer.float, buffer.float)
    }
}

/** one decoded stroke together with the layer node it hangs from */
private data class ParentedStroke(val parent: CrdtId, val stroke: RmStroke)

/**
 * Decodes the blocks of a version 6 file into layers of strokes.
 *
 * Blocks this library doesn't model are left alone: a version 6 page also carries text,
 * glyphs, and shared-editing bookkeeping, and skipping what isn't a stroke is what lets the
 * strokes be read without pretending to understand the rest.
 */
internal fun decodeSceneLayers(blocks: List<RmBlock>): List<RmLayer> {
    val names = LinkedHashMap<CrdtId, String>()
    val strokes = mutableListOf<ParentedStroke>()

    for (block in blocks) {
        val reader = TaggedReader(
            ByteBuffer.wrap(block.payload).order(ByteOrder.LITTLE_ENDIAN),
        )
        when (block.type) {
            BLOCK_TREE_NODE -> readTreeNode(reader)?.let { (id, name) -> names[id] = name }
            BLOCK_SCENE_LINE_ITEM ->
                readLineItem(reader, block.currentVersion)?.let { strokes.add(it) }
            else -> Unit
        }
    }

    // a layer the file never named still holds strokes, so group by parent rather than by
    // the names, and fall back to an unnamed layer
    val grouped = strokes.groupBy({ it.parent }, { it.stroke })
    val ordered = LinkedHashSet<CrdtId>().apply {
        addAll(names.keys)
        addAll(grouped.keys)
    }
    return ordered.mapNotNull { id ->
        val layerStrokes = grouped[id] ?: return@mapNotNull null
        RmLayer(strokes = layerStrokes, name = names[id])
    }
}

private fun readTreeNode(reader: TaggedReader): Pair<CrdtId, String>? {
    val id = reader.crdtId(1)
    if (!reader.hasField(2)) {
        return null
    }
    reader.subblock(2)
    reader.crdtId(1)
    reader.subblock(2)
    return id to reader.string()
}

private fun readLineItem(reader: TaggedReader, blockVersion: Int): ParentedStroke? {
    val parent = reader.crdtId(1)
    reader.crdtId(2)
    reader.crdtId(3)
    reader.crdtId(4)
    reader.int(5)
    // an erased stroke is recorded as a tombstone: a deleted length and no value at all
    return if (!reader.hasField(6)) {
        null
    } else {
        reader.subblock(6)
        if (reader.byte() != ITEM_TYPE_LINE) {
            null
        } else {
            ParentedStroke(parent, readLine(reader, blockVersion))
        }
    }
}

private fun readLine(reader: TaggedReader, blockVersion: Int): RmStroke {
    val tool = reader.int(1)
    val color = reader.int(2)
    val thickness = reader.double(3)
    reader.float(4)

    val pointBytes = reader.subblock(5)
    val pointSize = if (blockVersion >= 2) POINT_SIZE_V2 else POINT_SIZE_V1
    if (pointBytes % pointSize != 0) {
        throw ValidationException(
            "a stroke's $pointBytes bytes of points are not a multiple of $pointSize",
        )
    }
    return RmStroke(
        penRaw = tool,
        colorRaw = color,
        width = thickness.toFloat(),
        points = List(pointBytes / pointSize) { reader.readPoint(pointSize) },
    )
}

/**
 * one block of a version 6 file
 *
 * Version 6 replaced the flat layer/stroke layout with a sequence of tagged blocks
 * describing a shared-editing document. Strokes and layer names are decoded from these into
 * [RmFile.Scene.layers]; the blocks are also kept intact, because a page carries text,
 * glyphs, and editing history that this library does not model and would otherwise discard.
 */
public data class RmBlock(
    /** which kind of block this is */
    public val type: Int,
    /** the oldest reader version that can understand this block */
    public val minVersion: Int,
    /** the version that wrote it */
    public val currentVersion: Int,
    /** the block's undecoded contents */
    public val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is RmBlock && type == other.type && minVersion == other.minVersion &&
                currentVersion == other.currentVersion && payload.contentEquals(other.payload)
            )

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + minVersion
        result = 31 * result + currentVersion
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

internal fun readBlocks(body: ByteBuffer, header: String): List<RmBlock> {
    val blocks = mutableListOf<RmBlock>()
    while (body.remaining() >= BLOCK_HEADER_LENGTH) {
        val length = body.int
        requireValid(length >= 0, header) { "a block declared a negative length ($length)" }
        // one byte the format reserves and no public description explains
        body.get()
        val minVersion = body.get().toInt() and 0xFF
        val currentVersion = body.get().toInt() and 0xFF
        val type = body.get().toInt() and 0xFF
        requireValid(body.remaining() >= length, header) {
            "a block declared $length bytes but only ${body.remaining()} remain"
        }
        val payload = ByteArray(length)
        body.get(payload)
        blocks.add(RmBlock(type, minVersion, currentVersion, payload))
    }
    requireValid(body.remaining() == 0, header) {
        "${body.remaining()} trailing bytes after the last block"
    }
    return blocks
}

/**
 * Writes a version 6 page back to bytes by re-framing its blocks.
 *
 * A version 6 page is written from [RmFile.Scene.blocks], never from its decoded
 * [RmFile.Scene.layers] — this library frames every block but interprets only some, so
 * re-encoding strokes would mean discarding the text, glyphs, and editing history it does
 * not model. An untouched page therefore round-trips to the identical bytes.
 */
internal fun serializeRmV6(file: RmFile.Scene): ByteArray {
    val size = RM_HEADER_LENGTH +
        file.blocks.sumOf { BLOCK_HEADER_LENGTH + it.payload.size }
    val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    out.put("$RM_HEADER_PREFIX${file.version}".padEnd(RM_HEADER_LENGTH).toByteArray(Charsets.US_ASCII))
    for (block in file.blocks) {
        out.putInt(block.payload.size)
        // the byte the format reserves, which every page the device wrote has as zero
        out.put(0)
        out.put(block.minVersion.toByte())
        out.put(block.currentVersion.toByte())
        out.put(block.type.toByte())
        out.put(block.payload)
    }
    return out.array()
}
