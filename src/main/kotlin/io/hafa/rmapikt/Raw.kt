// The raw tier is the protocol surface: index parsing and serialisation, blob staging and
// upload, and the `.rm` page dispatch. Splitting it to satisfy a function count would put
// halves of one format in two files.
@file:Suppress("TooManyFunctions")

package io.hafa.rmapikt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64

private const val ENTRY_FIELD_COUNT = 5
private const val INFO_FIELD_COUNT = 4
internal const val SCHEMA_SUFFIX = ".docSchema"
internal const val CONTENT_SUFFIX = ".content"
internal const val METADATA_SUFFIX = ".metadata"
internal const val RM_SUFFIX = ".rm"

/**
 * Responses are read once and never written back, so an unrecognised field in one costs
 * nothing — unlike the stored files, where dropping a key on the way out would destroy it
 * (§D4). Being strict here would break the client the day reMarkable adds a field to a
 * response, in exchange for nothing.
 */
private val responseJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
private data class RootHashResponse(
    val hash: String,
    val generation: Long,
    val schemaVersion: Int,
)

@Serializable
private data class UpdatedRootResponse(val hash: String, val generation: Long)

@Serializable
private data class NativeSimpleEntry(val docID: String, val hash: String)

@Serializable
private data class RootWriteRequest(
    val hash: String,
    val generation: Long,
    val broadcast: Boolean,
)

@Serializable
private data class UploadMeta(@SerialName("file_name") val fileName: String)

/**
 * a file that has been hashed locally but not yet sent
 *
 * Hashing is pure and cheap; uploading is neither. Splitting them lets a caller build every
 * component of a document, learn all their hashes, and only then decide what to send — and
 * it keeps the entry that names the file inseparable from the bytes it describes.
 *
 * The split is also what makes losing a root-generation race survivable. Because staging
 * makes no request, a caller can stage everything once, then retry the commit as many times
 * as it needs, re-sending byte-identical blobs. A combined hash-and-upload would mint a
 * fresh set of orphans on every attempt.
 */
public class StagedFile internal constructor(
    /** the index entry describing this file */
    public val entry: RawEntry,
    internal val fileName: String,
    internal val bytes: ByteArray,
    internal val cacheText: String?,
)


/**
 * the low-level api, which works in hashes rather than documents
 *
 * reMarkable stores an immutable content-addressed filesystem: a file is named by the
 * sha-256 of its bytes, an item is an index listing its component files, and the whole
 * account is an index listing those indexes. Changing anything means writing new files and
 * then moving the root index to point at them.
 *
 * This is genuinely dangerous — a bad root write can orphan every document in the account —
 * so record [getRootHash] before writing, and prefer [RemarkableClient] unless you need
 * this level of control.
 */
public class RawRemarkableClient internal constructor(
    private val http: AuthedHttp,
    private val cache: LruCache,
    private val rawHost: String,
    private val uploadHost: String,
) {

    /**
     * the current root index hash, its generation, and the account's index format
     *
     * Pass the generation back to [putRootHash] so the cloud can tell whether anything else
     * moved the root in the meantime.
     */
    public suspend fun getRootHash(): RootInfo {
        val text = http.request("$rawHost/sync/v4/root", method = "GET")
            .use { it.body.string() }
        val root = decodeResponse(RootHashResponse.serializer(), text, "root hash")
        return RootInfo(
            hash = FileHash(root.hash),
            generation = root.generation,
            schemaVersion = SchemaVersion.ofWire(root.schemaVersion),
        )
    }

    /**
     * points the account at a new root index
     *
     * @throws GenerationException if [generation] is no longer current, meaning someone else
     *   wrote the root first and the change needs re-applying on top of theirs
     */
    public suspend fun putRootHash(
        hash: FileHash,
        generation: Long,
        broadcast: Boolean = true,
    ): RootUpdate {
        val body = encodeWire(
            RootWriteRequest.serializer(),
            RootWriteRequest(hash.hex, generation, broadcast),
        )
        val text = try {
            http.request(
                "$rawHost/sync/v3/root",
                method = "PUT",
                body = body.toRequestBody(),
            ).use { it.body.string() }
        } catch (_: PreconditionFailedException) {
            // only here is the generation that was rejected actually known
            throw GenerationException(generation)
        }
        val updated = decodeResponse(UpdatedRootResponse.serializer(), text, "updated root hash")
        return RootUpdate(FileHash(updated.hash), updated.generation)
    }

    /**
     * the raw bytes stored under [hash]
     *
     * [fileName] is the logical name, which the cloud validates against the hash.
     */
    public suspend fun getBlob(fileName: String, hash: FileHash): ByteArray {
        val cached = cache[hash.hex]
        return if (cached is CacheEntry.Text) {
            cached.text.toByteArray(Charsets.UTF_8)
        } else {
            val bytes = fetch(fileName, hash)
            if (cache[hash.hex] == null) {
                cache[hash.hex] = CacheEntry.Exists
            }
            bytes
        }
    }

    /** [getBlob] decoded as utf-8, and cached in full because text blobs are small */
    public suspend fun getText(fileName: String, hash: FileHash): String {
        val cached = cache[hash.hex]
        return if (cached is CacheEntry.Text) {
            cached.text
        } else {
            // two concurrent readers of one hash will both fetch and then agree; for
            // content-addressed data that is a wasted request, not a correctness problem
            val bytes = fetch(fileName, hash)
            val text = bytes.toString(Charsets.UTF_8)
            // Decoding bytes that are not valid utf-8 substitutes replacement characters,
            // and the original bytes cannot be recovered from the result. Caching such a
            // decode would let a later getBlob of the same hash hand back a mangled
            // re-encoding, so only a provably lossless decode is kept as text. Reading a
            // binary blob as text is a caller's mistake, but it must not corrupt the blob.
            cache[hash.hex] = if (text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                CacheEntry.Text(text)
            } else {
                CacheEntry.Exists
            }
            text
        }
    }

    /** parses [hash] as an entry index, in either schema 3 or schema 4 */
    public suspend fun getEntries(fileName: String, hash: FileHash): EntryIndex =
        parseEntryIndex(getText(fileName, hash))

    /** parses [hash] as a `.content` file */
    public suspend fun getContent(fileName: String, hash: FileHash): Content =
        decodeContent(getText(fileName, hash))

    /** parses [hash] as a `.metadata` file */
    public suspend fun getMetadata(fileName: String, hash: FileHash): Metadata =
        decodeWire(Metadata.serializer(), getText(fileName, hash), "metadata")

    /** hashes [bytes] locally, ready for [upload] */
    public fun stageFile(id: String, bytes: ByteArray): StagedFile = StagedFile(
        entry = RawEntry(
            type = RawEntryType.File,
            hash = FileHash(sha256Hex(bytes)),
            id = id,
            subfiles = 0,
            size = bytes.size.toLong(),
        ),
        fileName = id,
        bytes = bytes,
        cacheText = null,
    )

    /** hashes [text] locally; on a successful [upload] the text is also cached */
    public fun stageText(id: String, text: String): StagedFile {
        val staged = stageFile(id, text.toByteArray(Charsets.UTF_8))
        return StagedFile(staged.entry, staged.fileName, staged.bytes, cacheText = text)
    }

    /** @throws IllegalArgumentException if [id] does not end in `.content` */
    public fun stageContent(id: String, content: Content): StagedFile {
        require(id.endsWith(CONTENT_SUFFIX)) { "id '$id' did not end with '$CONTENT_SUFFIX'" }
        return stageText(id, encodeContent(content))
    }

    /**
     * hashes a `.rm` page locally, ready for [upload]
     *
     * Writing a page the device already has is free: the bytes are identical, so the hash
     * is too, and [upload] skips a blob the store already holds.
     *
     * @throws IllegalArgumentException if [id] does not end in `.rm`
     */
    public fun stageRm(id: String, page: RmFile): StagedFile {
        require(id.endsWith(RM_SUFFIX)) { "id '$id' did not end with '$RM_SUFFIX'" }
        return stageFile(id, serializeRmFile(page))
    }

    /** @throws IllegalArgumentException if [id] does not end in `.metadata` */
    public fun stageMetadata(id: String, metadata: Metadata): StagedFile {
        require(id.endsWith(METADATA_SUFFIX)) { "id '$id' did not end with '$METADATA_SUFFIX'" }
        return stageText(id, encodeWire(Metadata.serializer(), metadata))
    }

    /**
     * builds an entry index over [entries]
     *
     * The two schemas hash differently: schema 3 hashes the concatenated hashes of the
     * entries, schema 4 hashes the index file itself like any other blob.
     */
    public fun stageEntries(
        id: String,
        entries: List<RawEntry>,
        schemaVersion: SchemaVersion,
    ): StagedFile {
        if (id == ROOT_LIST && schemaVersion == SchemaVersion.V3) {
            System.err.println(
                "rmapi-kt: writing a schema 3 root index, which reMarkable rejects with a 400 " +
                    "\"Software must be updated\" error; write the root index with schema 4",
            )
        }
        val sorted = entries.sortedBy { it.id }
        val totalSize = sorted.sumOf { it.size }
        val body = serializeEntryIndex(id, sorted, totalSize, schemaVersion)

        val hash = when (schemaVersion) {
            // a schema 3 index is addressed by the hash of its entries' hashes, not of the
            // file that lists them
            SchemaVersion.V3 -> sha256Hex(
                sorted.fold(ByteArray(0)) { acc, entry -> acc + entry.hash.hex.hexToBytes() },
            )
            SchemaVersion.V4 -> sha256Hex(body)
        }

        return StagedFile(
            entry = RawEntry(
                type = if (schemaVersion == SchemaVersion.V4) {
                    RawEntryType.File
                } else {
                    RawEntryType.Collection
                },
                hash = FileHash(hash),
                id = id,
                subfiles = sorted.size,
                size = totalSize,
            ),
            fileName = "$id$SCHEMA_SUFFIX",
            bytes = body,
            cacheText = null,
        )
    }

    /** sends a staged file, skipping the request entirely if the hash is already known */
    public suspend fun upload(staged: StagedFile) {
        val hash = staged.entry.hash.hex
        if (cache[hash] == null) {
            val crc = Base64.encode(crc32cBytes(staged.bytes))
            http.request(
                "$rawHost/sync/v3/files/$hash",
                method = "PUT",
                body = staged.bytes.toRequestBody(),
                headers = mapOf("rm-filename" to staged.fileName, "x-goog-hash" to "crc32c=$crc"),
            ).close()
        }
        // an existence marker must never replace already-cached text: that would turn a
        // future read of this hash back into a network fetch for no reason
        val text = staged.cacheText
        if (text != null) {
            cache[hash] = CacheEntry.Text(text)
        } else if (cache[hash] == null) {
            cache[hash] = CacheEntry.Exists
        }
    }

    /**
     * hands a file to the server's ingestion endpoint, which builds the document itself
     *
     * This is the mechanism behind [RemarkableClient.uploadPdf] and friends, and is
     * unrelated to [upload], which sends one already-constructed component file.
     */
    public suspend fun uploadFile(
        visibleName: String,
        bytes: ByteArray,
        kind: UploadKind,
    ): ItemRef {
        val meta = Base64.encode(
            encodeWire(UploadMeta.serializer(), UploadMeta(visibleName)).toByteArray(Charsets.UTF_8),
        )
        val text = http.request(
            "$uploadHost/doc/v2/files",
            method = "POST",
            body = bytes.toRequestBody(),
            headers = mapOf(
                "Content-Type" to kind.mime,
                "rm-meta" to meta,
                "rm-source" to "RoR-Browser",
            ),
        ).use { it.body.string() }
        val entry = decodeResponse(NativeSimpleEntry.serializer(), text, "uploaded file")
        return ItemRef(ItemId(entry.docID), FileHash(entry.hash))
    }

    internal fun dumpCache(): String = cache.dump()

    internal fun clearCache() {
        cache.clear()
    }

    internal fun cachedHashes(): Set<String> = cache.hashes()

    internal fun forget(hash: String) {
        cache.remove(hash)
    }

    private suspend fun fetch(fileName: String, hash: FileHash): ByteArray =
        http.request(
            "$rawHost/sync/v3/files/${hash.hex}",
            method = "GET",
            headers = mapOf("rm-filename" to fileName),
        ).use { it.body.bytes() }
}

private fun <T> decodeResponse(
    serializer: kotlinx.serialization.KSerializer<T>,
    text: String,
    what: String,
): T = try {
    responseJson.decodeFromString(serializer, text)
} catch (error: kotlinx.serialization.SerializationException) {
    throw ValidationException("could not parse $what: ${error.message}", text, error)
}

/**
 * Renders an entry index exactly as reMarkable writes it.
 *
 * Every byte matters: in schema 4 the file's own hash is its address, so a stray space or a
 * different sort order produces a different document.
 */
private fun serializeEntryIndex(
    id: String,
    sorted: List<RawEntry>,
    totalSize: Long,
    schemaVersion: SchemaVersion,
): ByteArray {
    val out = StringBuilder()
    out.append(schemaVersion.wire).append('\n')
    if (schemaVersion == SchemaVersion.V4) {
        val name = if (id == ROOT_LIST) "." else id
        out.append("0:").append(name).append(':')
            .append(sorted.size).append(':').append(totalSize).append('\n')
    }
    for (entry in sorted) {
        // schema 4 has no nested indexes, so every line is written as a plain file
        val lineType = if (schemaVersion == SchemaVersion.V4) {
            RawEntryType.File.wire
        } else {
            entry.type.wire
        }
        out.append(entry.hash.hex).append(':')
            .append(lineType).append(':')
            .append(entry.id).append(':')
            .append(entry.subfiles).append(':')
            .append(entry.size).append('\n')
    }
    return out.toString().toByteArray(Charsets.UTF_8)
}

private fun malformed(text: String, message: String): Nothing =
    throw ValidationException(message, text)

private fun String.intOrFail(text: String, message: String): Int =
    toIntOrNull() ?: malformed(text, message)

private fun String.longOrFail(text: String, message: String): Long =
    toLongOrNull() ?: malformed(text, message)

private fun parseEntryLine(line: String): RawEntry {
    val fields = line.split(":")
    if (fields.size != ENTRY_FIELD_COUNT) {
        malformed(line, "index line '$line' was not formatted correctly")
    }
    return RawEntry(
        type = RawEntryType.ofWire(
            fields[1].intOrFail(line, "index line '$line' had a non-numeric type"),
        ),
        hash = FileHash(fields[0]),
        id = fields[2],
        subfiles = fields[3].intOrFail(line, "index line '$line' had a non-numeric subfile count"),
        size = fields[4].longOrFail(line, "index line '$line' had a non-numeric size"),
    )
}

internal fun parseEntryIndex(text: String): EntryIndex {
    val lines = text.removeSuffix("\n").split("\n")
    val version = SchemaVersion.ofWire(
        lines.first().intOrFail(text, "index version '${lines.first()}' was not a number"),
    )
    val rest = lines.drop(1)
    return when (version) {
        SchemaVersion.V3 -> EntryIndex(rest.filter { it.isNotEmpty() }.map(::parseEntryLine))
        SchemaVersion.V4 -> parseSchemaFourIndex(rest, text)
    }
}

private fun parseSchemaFourIndex(rest: List<String>, text: String): EntryIndex {
    val info = rest.firstOrNull()
        ?: malformed(text, "schema 4 index was missing its info line")
    val fields = info.split(":")
    if (fields.size != INFO_FIELD_COUNT || fields[0] != "0") {
        malformed(text, "schema 4 info line '$info' was not formatted correctly")
    }
    val entries = rest.drop(1).filter { it.isNotEmpty() }.map(::parseEntryLine)
    val declared = fields[2].intOrFail(text, "schema 4 info line '$info' had a non-numeric count")
    if (declared != entries.size) {
        malformed(text, "schema 4 index declared $declared entries but listed ${entries.size}")
    }
    return EntryIndex(
        entries = entries,
        id = fields[1],
        size = fields[3].longOrFail(text, "schema 4 info line '$info' had a non-numeric size"),
    )
}

/**
 * a parsed `.rm` page file
 *
 * Both variants expose [layers], so reading strokes does not depend on which firmware wrote
 * the page. They stay separate types because a version 6 file additionally carries
 * shared-editing structure that has no equivalent in the older format, and flattening the
 * two would mean pretending that structure doesn't exist.
 */
public sealed interface RmFile {
    /** the version recorded in the file's header */
    public val version: Int

    /** the page's layers, in drawing order */
    public val layers: List<RmLayer>

    /**
     * a version 3 or 5 file, written by firmware before 3.0
     *
     * The whole file is layers and strokes, so nothing else is carried.
     */
    public data class Lines(
        override val version: Int,
        override val layers: List<RmLayer>,
    ) : RmFile

    /**
     * a version 6 file, which is what current firmware writes
     *
     * [layers] holds the strokes and layer names decoded from [blocks]; [blocks] is the
     * whole file, including the text, glyph, and editing-history blocks this library does
     * not interpret.
     *
     * [layers] is a decoded *view* of [blocks], not a second copy of the page.
     * [serializeRmFile] writes [blocks], so editing [layers] and writing the result back
     * changes nothing — to alter a version 6 page you must edit its blocks.
     */
    public data class Scene(
        override val version: Int,
        override val layers: List<RmLayer>,
        /** every block in the file, in order */
        public val blocks: List<RmBlock>,
    ) : RmFile
}

/**
 * parses the header and body of a `.rm` page file
 *
 * The format is undocumented and reverse-engineered, so this is deliberately strict:
 * anything that doesn't match raises [ValidationException] rather than being guessed at,
 * because a wrong guess here yields strokes that look real and are not. The version 6
 * decoding was checked against pages a real device wrote; see `docs/design.md` D13.
 *
 * @throws ValidationException if the header is unrecognised, the version is unsupported, or
 *   the body is truncated or inconsistent
 */
public fun parseRmFile(bytes: ByteArray): RmFile = try {
    readRmFile(bytes)
} catch (error: BufferUnderflowException) {
    // reading past the end of a ByteBuffer raises this rather than returning short, and the
    // documented contract of this function is a ValidationException
    throw ValidationException("the .rm file ended in the middle of a value", null, error)
}

private fun readRmFile(bytes: ByteArray): RmFile {
    requireValid(bytes.size >= RM_HEADER_LENGTH, "") {
        "a .rm file is at least $RM_HEADER_LENGTH bytes, but this was ${bytes.size}"
    }
    val header = bytes.copyOf(RM_HEADER_LENGTH).toString(Charsets.US_ASCII)
    requireValid(header.startsWith(RM_HEADER_PREFIX), header) { "'$header' is not a .rm file header" }
    val version = header.substring(RM_HEADER_PREFIX.length).trim().toIntOrNull()
        ?: throw ValidationException("could not read a version from the header '$header'", header)

    val body = ByteBuffer.wrap(bytes, RM_HEADER_LENGTH, bytes.size - RM_HEADER_LENGTH)
        .slice()
        .order(ByteOrder.LITTLE_ENDIAN)
    return when (version) {
        3, 5 -> RmFile.Lines(version, readV5Layers(body, version, header))
        6 -> readBlocks(body, header).let { RmFile.Scene(version, decodeSceneLayers(it), it) }
        else -> throw ValidationException("`.rm` version $version is not supported", header)
    }
}

/**
 * writes a `.rm` page file back to bytes
 *
 * The inverse of [parseRmFile] for a page this library read: parsing and re-serialising an
 * untouched page yields the identical bytes, and therefore the identical hash. A version 3
 * or 5 page is written from its layers and strokes; a version 6 page is written from its
 * blocks, since [RmFile.Scene.layers] is a decoded view of those and re-encoding it would
 * discard everything the blocks carry that this library does not model.
 */
public fun serializeRmFile(page: RmFile): ByteArray = when (page) {
    is RmFile.Lines -> serializeRmV5(page)
    is RmFile.Scene -> serializeRmV6(page)
}
