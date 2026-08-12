package io.hafa.rmapikt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private val HASH_REGEX = Regex("^[0-9a-f]{64}$")
private val UUID_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** the id of the root entry index, as it appears in the entry index itself */
internal const val ROOT_LIST = "root"

/** the logical file name of the root entry index */
internal const val ROOT_SCHEMA = "$ROOT_LIST.docSchema"

/** the wire value of [Parent.Root] */
internal const val ROOT_PARENT = ""

/** the wire value of [Parent.Trash] */
internal const val TRASH_PARENT = "trash"

/**
 * the sha-256 of a blob's contents, which is also its address in the cloud
 *
 * Every file and every entry index is stored under its hash, so a hash names both a
 * thing and the exact state of that thing.
 */
@JvmInline
public value class FileHash(
    /** the 64-character lower-case hex digest */
    public val hex: String,
) {
    init {
        require(HASH_REGEX.matches(hex)) { "'$hex' is not a 64-character lower-case hex hash" }
    }

    override fun toString(): String = hex

    /** builds a hash from the cloud's own bytes */
    public companion object {
        /**
         * Parses a hash the cloud sent.
         *
         * The constructor rejects a bad value as a caller's mistake; this reports it as a
         * payload that didn't match, which is what it means when the value came off the
         * wire rather than out of the caller's own code.
         *
         * @throws ValidationException if [hex] is not a hash
         */
        public fun ofWire(hex: String): FileHash = try {
            FileHash(hex)
        } catch (error: IllegalArgumentException) {
            throw ValidationException(error.message.orEmpty(), hex, error)
        }
    }
}

/**
 * the stable identity of an item, a uuid4
 *
 * Unlike a [FileHash], an id does not change when the item is edited.
 */
@JvmInline
public value class ItemId(
    /** the uuid4 */
    public val value: String,
) {
    init {
        require(UUID_REGEX.matches(value)) { "'$value' is not a uuid4" }
    }

    override fun toString(): String = value

    /** builds an id from the cloud's own bytes */
    public companion object {
        /**
         * Parses an id the cloud sent.
         *
         * See [FileHash.ofWire] for why this differs from the constructor.
         *
         * @throws ValidationException if [value] is not a uuid4
         */
        public fun ofWire(value: String): ItemId = try {
            ItemId(value)
        } catch (error: IllegalArgumentException) {
            throw ValidationException(error.message.orEmpty(), value, error)
        }
    }
}

/**
 * a reference to one item at one particular state
 *
 * Reads take a ref rather than a separate id and hash, so the two can't be transposed.
 */
public data class ItemRef(
    /** the item's stable id */
    public val id: ItemId,
    /** the hash of the item's current state */
    public val hash: FileHash,
)

/**
 * where an item lives
 *
 * The cloud encodes the root as an empty string and the trash as `"trash"`; this models
 * both as values so neither is a magic string at a call site.
 */
public sealed interface Parent {
    /** the default location for items */
    public data object Root : Parent

    /** the trash; items are moved here rather than hard deleted */
    public data object Trash : Parent

    /** a user-created folder */
    public data class Folder(
        /** the folder's id */
        public val id: ItemId,
    ) : Parent

    /** the value the cloud stores for this parent */
    public val wire: String
        get() = when (this) {
            Root -> ROOT_PARENT
            Trash -> TRASH_PARENT
            is Folder -> id.value
        }

    /** parses the cloud's representation of a parent */
    public companion object {
        /** @throws ValidationException if [wire] is neither special value nor a uuid4 */
        public fun ofWire(wire: String): Parent = when (wire) {
            ROOT_PARENT -> Root
            TRASH_PARENT -> Trash
            else -> Folder(ItemId.ofWire(wire))
        }
    }
}

/** the kinds of file reMarkable stores */
@Serializable
public enum class FileType {
    /** an epub */
    @SerialName("epub")
    Epub,

    /** a pdf */
    @SerialName("pdf")
    Pdf,

    /** a natively created notebook */
    @SerialName("notebook")
    Notebook,
}

/**
 * The file-name extension a document of this type is stored under.
 *
 * Spelled out rather than derived from the constant's name, so renaming a constant cannot
 * quietly change what gets written to the cloud; the `when` is exhaustive, so adding a
 * type is a compile error here rather than a wrong filename at runtime.
 */
internal val FileType.extension: String
    get() = when (this) {
        FileType.Epub -> "epub"
        FileType.Pdf -> "pdf"
        FileType.Notebook -> "notebook"
    }

/** which of an item's component files a read refers to */
public enum class DocumentComponent(
    /** the file-name suffix */
    public val suffix: String,
) {
    /** the `.content` file */
    Content(".content"),

    /** the `.metadata` file */
    Metadata(".metadata"),

    /** the `.pdf` file */
    Pdf(".pdf"),

    /** the `.epub` file */
    Epub(".epub"),

    /** the `.template` file, which only template items have */
    Template(".template"),
}

/** the entry-index format an account uses */
public enum class SchemaVersion(
    /** the version number written as the index's first line */
    public val wire: Int,
) {
    /** hashes an index as the sha-256 of its entries' concatenated hashes */
    V3(3),

    /** hashes an index as the sha-256 of the index file, like any other file */
    V4(4);

    internal companion object {
        fun ofWire(wire: Int): SchemaVersion = entries.firstOrNull { it.wire == wire }
            ?: throw ValidationException("schema version $wire is not supported", wire.toString())
    }
}

/** whether a raw entry points at a nested index or a plain file */
public enum class RawEntryType(
    /** the value written in the entry line */
    public val wire: Int,
) {
    /** a schema 3 collection of files */
    Collection(80000000),

    /** a plain file, and every entry in a schema 4 index */
    File(0);

    internal companion object {
        fun ofWire(wire: Int): RawEntryType = entries.firstOrNull { it.wire == wire }
            ?: throw ValidationException("entry type $wire is not supported", wire.toString())
    }
}

/**
 * one line of an entry index
 *
 * An index either lists the items in the account (the root index) or the component files
 * of a single item.
 */
public data class RawEntry(
    /** whether this points at a nested index or a file */
    public val type: RawEntryType,
    /** the hash of what this points at */
    public val hash: FileHash,
    /** the logical name, e.g. `<uuid>` for an item or `<uuid>.content` for a file */
    public val id: String,
    /** the number of entries beneath this one, or 0 for a file */
    public val subfiles: Int,
    /** the recursive size in bytes */
    public val size: Long,
)

/** a parsed entry index */
public data class EntryIndex(
    /** the entries the index lists */
    public val entries: List<RawEntry>,
    /** the index's own id; schema 4 only */
    public val id: String? = null,
    /** the index's recursive size; schema 4 only */
    public val size: Long? = null,
)

/** the root hash together with the state it belongs to */
public data class RootInfo(
    /** the hash of the root entry index */
    public val hash: FileHash,
    /** increments on every root write; a stale value is rejected */
    public val generation: Long,
    /** the index format this account uses */
    public val schemaVersion: SchemaVersion,
)

/** the result of successfully moving the root forward */
public data class RootUpdate(
    /** the new root hash */
    public val hash: FileHash,
    /** the new generation */
    public val generation: Long,
)

/** which ingestion endpoint an upload targets */
public enum class UploadKind(
    /** the content type the endpoint expects */
    public val mime: String,
) {
    /** a pdf document */
    Pdf("application/pdf"),

    /** an epub document */
    Epub("application/epub+zip"),

    /** a folder, which carries no body */
    Folder("folder"),
}

/** the `metadata.type` discriminator */
@Serializable
public enum class EntryType {
    /** a folder */
    @SerialName("CollectionType")
    Collection,

    /** a document */
    @SerialName("DocumentType")
    Document,

    /** a template */
    @SerialName("TemplateType")
    Template,
}

/**
 * tags, which the cloud stores in two shapes
 *
 * Older firmware wrote bare strings; newer firmware writes objects with timestamps.
 * Both are preserved as read so a write never silently rewrites the device's format.
 */
public sealed interface Tags {
    /** the tag names, however they were stored */
    public val names: List<String>

    /** tags with timestamps, as written by current firmware */
    public data class Structured(
        /** the tags */
        public val tags: List<Tag>,
    ) : Tags {
        override val names: List<String> get() = tags.map { it.name }
    }

    /** bare tag names, as written by older firmware */
    public data class Legacy(
        /** the tag names */
        override val names: List<String>,
    ) : Tags

    public companion object {
        /** no tags, which is what an absent `tags` key decodes to */
        public val Empty: Tags = Structured(emptyList())
    }
}

/** an item-level tag */
@Serializable
public data class Tag(
    /** the tag text */
    public val name: String,
    /** when the tag was added, in epoch milliseconds */
    public val timestamp: Long,
)

/** a tag attached to a single page */
@Serializable
public data class PageTag(
    /** the tag text */
    public val name: String,
    /** the page the tag is on */
    public val pageId: String,
    /** when the tag was added, in epoch milliseconds */
    public val timestamp: Long,
)
