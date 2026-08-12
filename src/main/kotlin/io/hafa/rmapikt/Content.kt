@file:UseSerializers(
    ParentSerializer::class,
    TagsSerializer::class,
)

package io.hafa.rmapikt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

/** how a document or template is laid out on the page */
@Serializable
public enum class Orientation {
    /** taller than wide */
    @SerialName("portrait")
    Portrait,

    /** wider than tall */
    @SerialName("landscape")
    Landscape,
}

/** how text is aligned within the margins */
@Serializable
public enum class TextAlignment {
    /** the device's default, written as an empty string */
    @SerialName("")
    Unspecified,

    /** flush against both margins */
    @SerialName("justify")
    Justify,

    /** flush against the left margin only */
    @SerialName("left")
    Left,
}

/**
 * how a document is scaled to the screen
 *
 * Only [CustomFit] consults the `customZoom*` fields of [DocumentContent]; the rest
 * compute their own scale.
 */
@Serializable
public enum class ZoomMode {
    /** fit the whole page on screen */
    @SerialName("bestFit")
    BestFit,

    /** use the `customZoom*` fields */
    @SerialName("customFit")
    CustomFit,

    /** scale so the page height fills the screen */
    @SerialName("fitToHeight")
    FitToHeight,

    /** scale so the page width fills the screen */
    @SerialName("fitToWidth")
    FitToWidth,
}

/**
 * the adaptive contrast filter applied when rendering
 *
 * Omitting the field entirely is a third behaviour: reMarkable then applies the filter
 * only to areas it detects as text.
 */
@Serializable
public enum class BackgroundFilter {
    /** no filter, which suits images */
    @SerialName("off")
    Off,

    /** high contrast across the whole page */
    @SerialName("fullpage")
    FullPage,
}

/** a device screen a template declares support for */
@Serializable
public enum class SupportedScreen {
    /** reMarkable 2 */
    @SerialName("rm2")
    Rm2,

    /** reMarkable Paper Pro */
    @SerialName("rmPP")
    RmPaperPro,
}

/** bibliographic metadata about a document, stored inside its [DocumentContent] */
@Serializable
public data class DocumentMetadata(
    /** the authors */
    public val authors: List<String>? = null,
    /** the title, which is independent of the item's `visibleName` */
    public val title: String? = null,
    /** the publication date, an ISO date or a timestamp */
    public val publicationDate: String? = null,
    /** the publisher */
    public val publisher: String? = null,
)

/** speculative: a record of keyboard use */
@Serializable
public data class KeyboardMetadata(
    /** unknown */
    public val count: Int,
    /** unknown */
    public val timestamp: Double,
)

/**
 * speculative: a transform matrix, in the style of a css matrix transform
 *
 * Every cell is optional on the wire, so an absent cell is left null rather than
 * defaulted to the identity matrix — writing an identity back would not be faithful to
 * what the device stored.
 */
@Serializable
public data class Transform(
    /** row 1, column 1 */
    public val m11: Double? = null,
    /** row 1, column 2 */
    public val m12: Double? = null,
    /** row 1, column 3 */
    public val m13: Double? = null,
    /** row 2, column 1 */
    public val m21: Double? = null,
    /** row 2, column 2 */
    public val m22: Double? = null,
    /** row 2, column 3 */
    public val m23: Double? = null,
    /** row 3, column 1 */
    public val m31: Double? = null,
    /** row 3, column 2 */
    public val m32: Double? = null,
    /** row 3, column 3 */
    public val m33: Double? = null,
)

/**
 * a cPages value carrying a string
 *
 * The timestamp is not a clock reading but a pseudo-timestamp of the form `"1:1"`,
 * which is why it is typed as a string.
 */
@Serializable
public data class CPageStringValue(
    /** the pseudo-timestamp, e.g. `"1:1"` */
    public val timestamp: String,
    /** the stored value */
    public val value: String,
)

/** a cPages value carrying a number, with the same pseudo-timestamp as [CPageStringValue] */
@Serializable
public data class CPageNumberValue(
    /** the pseudo-timestamp, e.g. `"1:1"` */
    public val timestamp: String,
    /** the stored value */
    public val value: Double,
)

/** speculative: what the device records about one page */
@Serializable
public data class CPagePage(
    /** speculative: the page id */
    public val id: String,
    /** unknown: an ordering key; values look like `"aa"`, `"ab"`, `"ba"` */
    public val idx: CPageStringValue,
    /** unknown */
    public val redir: CPageNumberValue? = null,
    /** speculative: the page's template name */
    public val template: CPageStringValue? = null,
    /** unknown: the value is an ISO timestamp */
    public val scrollTime: CPageStringValue? = null,
    /** unknown */
    public val verticalScroll: CPageNumberValue? = null,
    /** unknown: a page the device treats as deleted */
    public val deleted: CPageNumberValue? = null,
    /**
     * speculative: when the page was last modified, epoch milliseconds as a string
     *
     * Spelled as the device spells it. Unlike its neighbours it is a bare string rather
     * than a timestamped value, so it is not a [CPageStringValue].
     */
    public val modifed: String? = null,
)

/** unknown: a pair the device stores alongside the page list */
@Serializable
public data class CPageUUID(
    /** unknown */
    public val first: String,
    /** unknown */
    public val second: Int,
)

/** unknown: the device's own page bookkeeping, stored inside [DocumentContent] */
@Serializable
public data class CPages(
    /** speculative: when the document was last opened */
    public val lastOpened: CPageStringValue,
    /** unknown */
    public val original: CPageNumberValue,
    /** speculative: one entry per page */
    public val pages: List<CPagePage>,
    /**
     * unknown
     *
     * Null is a value the device writes, distinct from the field being absent, so this
     * is a required field that happens to be nullable.
     */
    public val uuids: List<CPageUUID>?,
)

/**
 * how to render an item
 *
 * The variants are not tagged on the wire; they are told apart by which keys are present.
 * A template's `.content` is empty, so it has none of these — see [TemplateDefinition].
 */
public sealed interface Content

/** the `.content` file of a folder, which carries nothing but its tags */
@Serializable
public data class CollectionContent(
    /** the folder's tags; empty when it has none */
    public val tags: Tags = Tags.Empty,
) : Content

/**
 * the `.content` file of a document
 *
 * This describes how to *render* the document rather than what it is; item-level facts
 * like the name and parent live in [Metadata].
 */
@Serializable
public data class DocumentContent(
    /** which page to use as the thumbnail; -1 means the last visited page, 0 the first */
    public val coverPageNumber: Int,
    /** metadata about the author, publisher, and so on */
    public val documentMetadata: DocumentMetadata,
    /** largely a record of which pens were used and how they were configured */
    public val extraMetadata: Map<String, String>,
    /** the underlying file this document renders */
    public val fileType: FileType,
    /**
     * the font used for text rendering
     *
     * The device ships with "Noto Sans", "Noto Sans UI", "EB Garamond", "Noto Mono",
     * and "Noto Serif"; the empty string selects the default.
     */
    public val fontName: String,
    /** the line height; the device offers 100, 150, and 200, and -1 for its default */
    public val lineHeight: Int,
    /** the page orientation */
    public val orientation: Orientation,
    /** the number of pages */
    public val pageCount: Int,
    /** how text is aligned */
    public val textAlignment: TextAlignment,
    /** the font size; the device offers 0.7, 0.8, 1, 1.2, 1.5, and 2 */
    public val textScale: Double,
    /** the document's tags; empty when it has none */
    public val tags: Tags = Tags.Empty,
    /** the content format version, which is always 1 in practice */
    public val formatVersion: Int? = null,
    /** the margin in pixels; the device offers 50, 125, and 200, and used to default to 180 */
    public val margins: Int? = null,
    /** the id of every page in order, or null when the document has never been opened */
    public val pages: List<String>? = null,
    /** tags attached to individual pages */
    public val pageTags: List<PageTag>? = null,
    /** a mapping from page number to an index in [pages] */
    public val redirectionPageMap: List<Int>? = null,
    /** ostensibly the file size in bytes, though it disagrees with other measurements */
    public val sizeInBytes: String? = null,
    /** unknown: a second page count, whose relationship to [pageCount] is unclear */
    public val originalPageCount: Int? = null,
    /** the last page opened, counting from zero */
    public val lastOpenedPage: Int? = null,
    /** unknown */
    public val dummyDocument: Boolean? = null,
    /** how the document is scaled to the screen */
    public val zoomMode: ZoomMode? = null,
    /** the scale for a [ZoomMode.CustomFit] zoom */
    public val customZoomScale: Double? = null,
    /** the horizontal centre of a [ZoomMode.CustomFit] zoom, in device pixels from centre */
    public val customZoomCenterX: Double? = null,
    /** the vertical centre of a [ZoomMode.CustomFit] zoom, in device pixels from the top */
    public val customZoomCenterY: Double? = null,
    /** the rendered page width in device pixels */
    public val customZoomPageWidth: Double? = null,
    /** the rendered page height in device pixels, computed as `heightPt * dpi / 72` */
    public val customZoomPageHeight: Double? = null,
    /** the orientation the [ZoomMode.CustomFit] zoom was configured in */
    public val customZoomOrientation: Orientation? = null,
    /** the adaptive contrast filter; absent means "text areas only" */
    public val viewBackgroundFilter: BackgroundFilter? = null,
    /** speculative: a transform matrix */
    public val transform: Transform? = null,
    /** speculative: a record of keyboard use */
    public val keyboardMetadata: KeyboardMetadata? = null,
    /** speculative: the device's own page bookkeeping */
    public val cPages: CPages? = null,
) : Content

/**
 * a template's definition, which its empty `.content` does not carry
 *
 * The layout is an svg-like dsl in json whose values may be numbers *or* expressions over
 * [constants] — `"templateWidth - (offsetX * 2)"` is a real value — so the parts this
 * library does not interpret stay raw json rather than take a type they don't fit.
 *
 * Optionality follows what real templates carry, not what the field names suggest: some
 * omit [supportedScreens], others omit [labels].
 */
@Serializable
public data class TemplateDefinition(
    /** the template name */
    public val name: String,
    /** the template's author */
    public val author: String,
    /** a base64-encoded svg icon */
    public val iconData: String,
    /** the categories this template belongs to, e.g. "Planning" */
    public val categories: List<String>,
    /** the template's orientation */
    public val orientation: Orientation,
    /** the template's semantic version */
    public val templateVersion: String,
    /** the template definition itself, an svg-like dsl expressed in json */
    public val items: List<JsonObject>,
    /** the configuration format version */
    public val formatVersion: Int,
    /**
     * named values the expressions in [items] refer to
     *
     * Each entry is a single-key object whose value is a number or an expression string, so
     * it stays raw json rather than being typed as a number.
     */
    public val constants: List<JsonObject>? = null,
    /** labels attached to this template, e.g. "Meetings" */
    public val labels: List<String>? = null,
    /** the screens this template supports; absent on some templates */
    public val supportedScreens: List<SupportedScreen>? = null,
    /** an identifier some templates carry */
    public val id: String? = null,
)

/** the item itself, where [Content] describes how to render it */
@Serializable
public data class Metadata(
    /** the name shown on the device */
    public val visibleName: String,
    /** where the item lives */
    public val parent: Parent,
    /** whether the item is starred */
    public val pinned: Boolean,
    /** which kind of item this is */
    public val type: EntryType,
    /** the last modification time, epoch milliseconds as a string */
    public val lastModified: String,
    /** the last time the item was opened; folders don't have one */
    public val lastOpened: String? = null,
    /** the last page opened, counting from zero; folders don't have one */
    public val lastOpenedPage: Int? = null,
    /** the creation time, epoch milliseconds as a string */
    public val createdTime: String? = null,
    /** speculative: whether the item has actually been deleted, as opposed to trashed */
    public val deleted: Boolean? = null,
    /** speculative: whether the metadata has been modified */
    public val metadatamodified: Boolean? = null,
    /** speculative: whether the item has been modified */
    public val modified: Boolean? = null,
    /** unknown */
    public val synced: Boolean? = null,
    /** speculative: the metadata version, which every edit bumps */
    public val version: Int? = null,
    /** whether this is a newly installed template */
    public val new: Boolean? = null,
    /** where the item was installed from, e.g. "com.remarkable.methods" */
    public val source: String? = null,
)

/**
 * Reads one of the wire's stringified epoch timestamps as a number.
 *
 * [Metadata] keeps these as strings because it round-trips, and re-encoding a parsed number
 * could write back something the device did not send. These read them for a caller who only
 * wants to compare or display one.
 *
 * @throws ValidationException if the field is not an epoch timestamp
 */
public val Metadata.modifiedAt: Long get() = lastModified.asEpochMillis("lastModified")

/** when the item was last opened, in epoch milliseconds; see [modifiedAt] */
public val Metadata.openedAt: Long? get() = lastOpened?.asEpochMillis("lastOpened")

/** when the item was created, in epoch milliseconds; see [modifiedAt] */
public val Metadata.createdAt: Long? get() = createdTime?.asEpochMillis("createdTime")

internal fun String.asEpochMillis(field: String): Long = toLongOrNull()
    ?: throw ValidationException("$field was '$this', which is not an epoch timestamp", this)
