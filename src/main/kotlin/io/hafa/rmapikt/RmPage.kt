package io.hafa.rmapikt

/** the fixed part of every `.rm` header, before the version digit and padding */
internal const val RM_HEADER_PREFIX = "reMarkable .lines file, version="
internal const val RM_HEADER_LENGTH = 43

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
