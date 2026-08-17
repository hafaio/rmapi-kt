package io.hafa.rmapikt

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val FLOATS_PER_POINT = 6
private const val BYTES_PER_POINT = FLOATS_PER_POINT * 4

/** a layer is at least a stroke count; a stroke is at least its fixed header plus a count */
private const val MIN_BYTES_PER_LAYER = 4
private const val MIN_BYTES_PER_STROKE = 20

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
