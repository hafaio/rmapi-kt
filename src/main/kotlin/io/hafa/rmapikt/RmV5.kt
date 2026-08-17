package io.hafa.rmapikt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** the fixed part of every `.rm` header, before the version digit and padding */
internal const val RM_HEADER_PREFIX = "reMarkable .lines file, version="
internal const val RM_HEADER_LENGTH = 43
private const val FLOATS_PER_POINT = 6
private const val BYTES_PER_POINT = FLOATS_PER_POINT * 4

/** a layer is at least a stroke count; a stroke is at least its fixed header plus a count */
private const val MIN_BYTES_PER_LAYER = 4
private const val MIN_BYTES_PER_STROKE = 20

/**
 * one sampled point along a stroke
 *
 * Coordinates are in device pixels with the origin at the page's top left. The remaining
 * channels are what the digitiser reported at that sample, and reMarkable uses them to
 * vary how the stroke is drawn.
 */
public data class RmPoint(
    /** horizontal position in device pixels */
    public val x: Float,
    /** vertical position in device pixels */
    public val y: Float,
    /** how fast the pen was moving */
    public val speed: Float,
    /** the pen's direction of travel */
    public val direction: Float,
    /** the stroke's width at this point */
    public val width: Float,
    /** stylus pressure, nominally 0 to 1 */
    public val pressure: Float,
)

/**
 * the drawing tools reMarkable records
 *
 * Several tools appear twice because the v5 format renumbered them; both spellings are
 * listed so a raw value maps to the same concept regardless of which format wrote it.
 */
public enum class RmPen(
    /** the value stored in the file */
    public val raw: Int,
) {
    /** a paintbrush */
    Brush(0),

    /** a pencil, which responds to tilt */
    Pencil(1),

    /** a ballpoint pen */
    Ballpoint(2),

    /** a marker */
    Marker(3),

    /** a fineliner */
    Fineliner(4),

    /** a highlighter */
    Highlighter(5),

    /** the eraser */
    Eraser(6),

    /** a mechanical pencil */
    MechanicalPencil(7),

    /** the area eraser */
    EraseArea(8),

    /** a calligraphy pen */
    Calligraphy(21),

    /** a shader */
    Shader(23),

    /** a paintbrush, as renumbered by the v5 format */
    BrushV5(12),

    /** a mechanical pencil, as renumbered by the v5 format */
    MechanicalPencilV5(13),

    /** a pencil, as renumbered by the v5 format */
    PencilV5(14),

    /** a ballpoint pen, as renumbered by the v5 format */
    BallpointV5(15),

    /** a marker, as renumbered by the v5 format */
    MarkerV5(16),

    /** a fineliner, as renumbered by the v5 format */
    FinelinerV5(17),

    /** a highlighter, as renumbered by the v5 format */
    HighlighterV5(18);

    internal companion object {
        fun of(raw: Int, rawText: String? = null): RmPen = entries.firstOrNull { it.raw == raw }
            ?: throw ValidationException("unknown pen code $raw", rawText)
    }
}

/**
 * the stroke colours reMarkable records
 *
 * As with [RmPen] a colour can be spelled twice: the Paper Pro extended the palette and
 * gave green and yellow a second code. Which shade a name renders as is up to the device.
 *
 * [Highlight] marks a stroke rather than naming a shade. The colour it is drawn in lives in
 * a version 6 field this library keeps as raw block bytes but does not decode.
 */
public enum class RmColor(
    /** the value stored in the file */
    public val raw: Int,
) {
    /** black */
    Black(0),

    /** grey */
    Grey(1),

    /** white, which is how the device draws an erasure */
    White(2),

    /** yellow */
    Yellow(3),

    /** green */
    Green(4),

    /** pink */
    Pink(5),

    /** blue */
    Blue(6),

    /** red */
    Red(7),

    /** grey, as used where strokes overlap */
    GreyOverlap(8),

    /** a highlight rather than a shade */
    Highlight(9),

    /** green, as the Paper Pro palette spells it */
    GreenPaperPro(10),

    /** cyan */
    Cyan(11),

    /** magenta */
    Magenta(12),

    /** yellow, as the Paper Pro palette spells it */
    YellowPaperPro(13);

    internal companion object {
        fun of(raw: Int, rawText: String? = null): RmColor = entries.firstOrNull { it.raw == raw }
            ?: throw ValidationException("unknown colour code $raw", rawText)
    }
}

/** one continuous pen stroke */
public data class RmStroke(
    /** the tool it was drawn with */
    public val pen: RmPen,
    /** the colour it was drawn in */
    public val color: RmColor,
    /** the stroke's base width, before per-point variation */
    public val width: Float,
    /** the sampled points, in the order they were drawn */
    public val points: List<RmPoint>,
    /** a word the format reserves and no public description explains */
    public val reserved: Int = 0,
    /** a further such word, which a version 3 page does not have */
    public val reservedV5: Int = 0,
)

/** one layer of a page, drawn in order */
public data class RmLayer(
    /** the strokes on this layer */
    public val strokes: List<RmStroke>,
    /** the layer's name, which only version 6 files record */
    public val name: String? = null,
)

internal fun requireValid(condition: Boolean, header: String, message: () -> String) {
    if (!condition) {
        throw ValidationException(message(), header)
    }
}

internal fun readV5Layers(body: ByteBuffer, version: Int, header: String): List<RmLayer> {
    val layerCount = readCount(body, header, "layer count", MIN_BYTES_PER_LAYER)
    return List(layerCount) {
        val strokeCount = readCount(body, header, "stroke count", MIN_BYTES_PER_STROKE)
        RmLayer(List(strokeCount) { readStroke(body, version, header) })
    }
}

private fun readStroke(body: ByteBuffer, version: Int, header: String): RmStroke {
    // pen, colour, a reserved word, and the width; version 5 added one more word here
    val fixedWords = if (version == 5) 5 else 4
    requireValid(body.remaining() >= fixedWords * Int.SIZE_BYTES + Int.SIZE_BYTES, header) {
        "a stroke header ran past the end of the file"
    }
    val pen = RmPen.of(body.int, header)
    val color = RmColor.of(body.int, header)
    val reserved = body.int
    val width = body.float
    val reservedV5 = if (version == 5) body.int else 0

    val pointCount = readCount(body, header, "point count", BYTES_PER_POINT)
    val points = List(pointCount) {
        RmPoint(body.float, body.float, body.float, body.float, body.float, body.float)
    }
    return RmStroke(
        pen = pen,
        color = color,
        width = width,
        points = points,
        reserved = reserved,
        reservedV5 = reservedV5,
    )
}

/**
 * Reads a count and rejects one the remaining bytes could not possibly satisfy.
 *
 * The count is wire data, so it is bounded before it is ever used as an allocation size.
 * The arithmetic is deliberately in Long: `count * bytesEach` in Int wraps for large
 * counts, which would let an absurd count pass a check that looks like it bounds it.
 */
internal fun readCount(body: ByteBuffer, header: String, what: String, bytesEach: Int): Int {
    requireValid(body.remaining() >= Int.SIZE_BYTES, header) { "the file ended before its $what" }
    val count = body.int
    requireValid(count >= 0, header) { "$what was negative ($count)" }
    requireValid(count.toLong() * bytesEach <= body.remaining(), header) {
        "$what of $count needs more than the ${body.remaining()} bytes that remain"
    }
    return count
}

/**
 * Writes a version 3 or 5 page back to bytes.
 *
 * The inverse of the reader above, including the two reserved words, so a page read from
 * the device and written back unchanged produces the identical file — which for a
 * content-addressed store means the identical hash, and therefore no upload at all.
 */
internal fun serializeRmV5(file: RmFile.Lines): ByteArray {
    val strokeHeaderBytes = if (file.version == 5) 6 * Int.SIZE_BYTES else 5 * Int.SIZE_BYTES
    val size = RM_HEADER_LENGTH + Int.SIZE_BYTES + file.layers.sumOf { layer ->
        Int.SIZE_BYTES + layer.strokes.sumOf { strokeHeaderBytes + it.points.size * BYTES_PER_POINT }
    }

    val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    out.put("$RM_HEADER_PREFIX${file.version}".padEnd(RM_HEADER_LENGTH).toByteArray(Charsets.US_ASCII))
    out.putInt(file.layers.size)
    for (layer in file.layers) {
        out.putInt(layer.strokes.size)
        for (stroke in layer.strokes) {
            out.putInt(stroke.pen.raw)
            out.putInt(stroke.color.raw)
            out.putInt(stroke.reserved)
            out.putFloat(stroke.width)
            if (file.version == 5) {
                out.putInt(stroke.reservedV5)
            }
            out.putInt(stroke.points.size)
            for (point in stroke.points) {
                out.putFloat(point.x)
                out.putFloat(point.y)
                out.putFloat(point.speed)
                out.putFloat(point.direction)
                out.putFloat(point.width)
                out.putFloat(point.pressure)
            }
        }
    }
    return out.array()
}
