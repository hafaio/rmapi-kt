package io.hafa.rmapikt

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val GENERATION_BASE_MS = 25L
private const val PAGEDATA_SUFFIX = ".pagedata"
private const val DEFAULT_MARGINS = 125

/**
 * how a document is scaled to the screen
 *
 * A sealed type rather than an enum plus six loose numbers: the custom fit needs all of
 * them and the other modes need none, so pairing a mode with the wrong fields is not
 * representable instead of being silently ignored.
 */
public sealed interface Zoom {
    /** fit the whole page on screen */
    public data object BestFit : Zoom

    /** scale so the page height fills the screen */
    public data object FitToHeight : Zoom

    /** scale so the page width fills the screen */
    public data object FitToWidth : Zoom

    /** an explicitly positioned and scaled view */
    public data class Custom(
        /** the scale, which reMarkable generally keeps between 0.5 and 5 */
        public val scale: Double,
        /** the horizontal centre, in device pixels from the page's centre */
        public val centerX: Double,
        /** the vertical centre, in device pixels from the page's top */
        public val centerY: Double,
        /** the rendered page width in device pixels */
        public val pageWidth: Double,
        /** the rendered page height in device pixels, `heightPoints * dpi / 72` */
        public val pageHeight: Double,
        /** the orientation this view was configured in */
        public val orientation: Orientation,
    ) : Zoom
}

/** how a `put*` builds the document it commits */
public data class PutOptions(
    /** where the document lands */
    public val parent: Parent = Parent.Root,
    /** whether the document starts starred; the wire calls this `pinned` */
    public val starred: Boolean = false,
    /** the thumbnail page; -1 means the last visited */
    public val coverPageNumber: Int = -1,
    /** the authors recorded in the document's own metadata */
    public val authors: List<String>? = null,
    /** the title recorded in the document's own metadata, independent of its name */
    public val title: String? = null,
    /** the publication date */
    public val publicationDate: String? = null,
    /** the publisher */
    public val publisher: String? = null,
    /** pen settings and similar, passed through untouched */
    public val extraMetadata: Map<String, String> = emptyMap(),
    /** the render font; empty selects the device default */
    public val fontName: String = "",
    /** the line height; -1 selects the device default */
    public val lineHeight: Int = -1,
    /** the page margin in pixels */
    public val margins: Int = DEFAULT_MARGINS,
    /** the page orientation */
    public val orientation: Orientation = Orientation.Portrait,
    /** tags to apply, which are given the current timestamp */
    public val tags: List<String>? = null,
    /** how text is aligned */
    public val textAlignment: TextAlignment = TextAlignment.Justify,
    /** the font size */
    public val textScale: Double = 1.0,
    /** how the document is scaled to the screen */
    public val zoom: Zoom = Zoom.BestFit,
    /** the adaptive contrast filter */
    public val viewBackgroundFilter: BackgroundFilter? = null,
)

/** what to override when restoring an archive with [RemarkableClient.importArchive] */
public data class ImportOptions(
    /** where the restored document lands; keeps the archived parent when null */
    public val parent: Parent? = null,
    /** the restored document's name; keeps the archived name when null */
    public val visibleName: String? = null,
)

/**
 * what a bulk operation did
 *
 * The two halves are separate so a caller cannot mistake a partial result for a complete
 * one: a ref that was not in the root index is reported rather than quietly dropped.
 */
public data class BulkResult(
    /** each ref that moved, mapped to a ref at its new state */
    public val moved: Map<ItemRef, ItemRef>,
    /** the refs that were not in the root index, and so were left alone */
    public val notFound: Set<ItemRef>,
)

/**
 * the reMarkable cloud, in terms of documents rather than hashes
 *
 * Every mutating call is a read-modify-write against the account's root index, so two
 * clients editing at once will race. Losing that race is normal, and this client handles
 * it by re-reading and re-applying the change up to
 * [SessionOptions.maxGenerationRetries] times before giving up with a
 * [GenerationException].
 */
@Suppress("TooManyFunctions")
public class RemarkableClient internal constructor(
    private val rawClient: RawRemarkableClient,
    private val maxGenerationRetries: Int,
) {
    /** the low-level api, for operations this one doesn't cover */
    public val raw: RawRemarkableClient get() = rawClient

    private val rootLock = Any()

    @Volatile
    private var lastRoot: RootInfo? = null

    /**
     * re-reads the root index
     *
     * Only needed to observe someone else's change: this client's own writes keep its view
     * current, and a lost race is retried without help. A read that comes back older than
     * what is already cached is discarded, and the cached root returned instead.
     */
    public suspend fun refreshRoot(): RootInfo {
        val fetched = rawClient.getRootHash()
        return synchronized(rootLock) {
            val cached = lastRoot
            // a slow read can land after a newer write, and taking it would rewind the
            // cache to a root the account has already moved past
            if (cached == null || fetched.generation >= cached.generation) {
                lastRoot = fetched
                fetched
            } else {
                cached
            }
        }
    }

    /**
     * The cached root, fetched on first use.
     *
     * Two calls can return different roots, so a mutating operation must take one snapshot
     * and derive both the entries it merges and the generation it commits against from it.
     * Mixing two would commit one root's entries against another's generation, which the
     * server accepts, reverting whatever landed in between.
     */
    private suspend fun root(): RootInfo = lastRoot ?: refreshRoot()

    private suspend fun commitRoot(hash: FileHash, generation: Long) {
        try {
            val updated = rawClient.putRootHash(hash, generation)
            lastRoot = lastRoot?.copy(hash = updated.hash, generation = updated.generation)
        } catch (error: GenerationException) {
            // the cached view is now known to be behind, so the retry must re-read
            lastRoot = null
            throw error
        }
    }

    /**
     * Re-runs [operation] when it loses a race for the root index.
     *
     * A lost race means the entry list the new root was built from is stale, so everything
     * derived from it is rebuilt per attempt. Ids and timestamps are minted before the call
     * — minting inside would orphan a fresh set of blobs each time — and re-sending the
     * blobs themselves costs nothing, as the cache skips a hash the store already has.
     */
    private suspend fun <T> withGenerationRetry(operation: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return operation()
            } catch (error: GenerationException) {
                if (attempt >= maxGenerationRetries) {
                    throw error
                }
                delay(backoffMillis(attempt, GENERATION_BASE_MS))
                attempt++
            }
        }
    }

    private suspend fun uploadAll(staged: List<StagedFile>): Unit = coroutineScope {
        staged.map { async { rawClient.upload(it) } }.awaitAll()
    }

    /**
     * Adds a freshly built item to the root index, sending every blob in one wave.
     *
     * [files] are the item's component blobs and [docSchema] the index listing them.
     * Staging the new root index needs only [docSchema]'s hash, not its upload, so all of
     * them go out in one parallel batch together with the root index.
     */
    private suspend fun commitNewItem(files: List<StagedFile>, docSchema: StagedFile) =
        withGenerationRetry {
            val current = root()
            val entries = rawClient.getEntries(ROOT_SCHEMA, current.hash).entries
            // ids are minted UUIDs, so a collision means a bug up the stack; appending
            // anyway would commit a root holding two items under one id
            check(entries.none { it.id == docSchema.entry.id }) {
                "id '${docSchema.entry.id}' is already in the root index"
            }
            val rootIndex =
                rawClient.stageEntries(ROOT_LIST, entries + docSchema.entry, SchemaVersion.V4)
            uploadAll(files + docSchema + rootIndex)
            commitRoot(rootIndex.entry.hash, current.generation)
        }

    /**
     * Swaps [ref]'s root entry for [docSchema]'s, sending every blob in one wave.
     *
     * [files] are the component blobs the edit rewrote and [docSchema] the rebuilt index
     * listing them; the same one-batch reasoning as [commitNewItem] applies.
     *
     * Matching the entry on id and hash together: under schema 3 an index hashes its
     * entries' hashes and not their ids, so two items whose component blobs match hash
     * the same, and a hash-only lookup would edit whichever the root listed first.
     *
     * @throws HashNotFoundException if [ref] has since been written over
     */
    private suspend fun commitEdit(
        ref: ItemRef,
        files: List<StagedFile>,
        docSchema: StagedFile,
    ): ItemRef {
        withGenerationRetry {
            val attempt = root()
            val entries = rawClient.getEntries(ROOT_SCHEMA, attempt.hash).entries.toMutableList()
            val index = entries.indexOfFirst { it.id == ref.id.value && it.hash == ref.hash }
            if (index < 0) {
                throw HashNotFoundException(ref, entries.firstOrNull { it.id == ref.id.value }?.hash)
            }
            entries[index] = docSchema.entry
            val rootIndex = rawClient.stageEntries(ROOT_LIST, entries, SchemaVersion.V4)
            uploadAll(files + docSchema + rootIndex)
            commitRoot(rootIndex.entry.hash, attempt.generation)
        }
        return ItemRef(ref.id, docSchema.entry.hash)
    }

    /**
     * Starts an edit: refuses a ref the root does not list, and gives the schema version.
     *
     * A stale or fabricated ref fails as [HashNotFoundException] before any component is
     * fetched or staged, instead of surfacing as whatever fetching a hash the store never
     * held happens to throw. The merge in [commitEdit] checks again from the snapshot it
     * commits, so this is the early report, not the guard.
     */
    private suspend fun beginEdit(ref: ItemRef): SchemaVersion {
        val current = root()
        val entries = rawClient.getEntries(ROOT_SCHEMA, current.hash).entries
        if (entries.none { it.id == ref.id.value && it.hash == ref.hash }) {
            throw HashNotFoundException(ref, entries.firstOrNull { it.id == ref.id.value }?.hash)
        }
        return current.schemaVersion
    }

    /** every item's id and hash, without fetching any metadata */
    public suspend fun listRefs(): List<ItemRef> =
        rawClient.getEntries(ROOT_SCHEMA, root().hash).entries
            .map { ItemRef(ItemId.ofWire(it.id), it.hash) }

    private suspend fun componentEntries(ref: ItemRef): List<RawEntry> =
        rawClient.getEntries("${ref.id.value}$SCHEMA_SUFFIX", ref.hash).entries

    private suspend fun component(ref: ItemRef, which: DocumentComponent): RawEntry =
        componentEntries(ref).firstOrNull { it.id.endsWith(which.suffix) }
            ?: throw ComponentNotFoundException(ref, which)

    /** the item's `.content` file */
    public suspend fun getContent(ref: ItemRef): Content =
        component(ref, DocumentComponent.Content).let { rawClient.getContent(it.id, it.hash) }

    /** the item's `.metadata` file */
    public suspend fun getMetadata(ref: ItemRef): Metadata =
        component(ref, DocumentComponent.Metadata).let { rawClient.getMetadata(it.id, it.hash) }

    /**
     * A template's `.content` is empty, so its definition lives here instead.
     *
     * @throws ComponentNotFoundException if the item is not a template
     */
    public suspend fun getTemplate(ref: ItemRef): TemplateDefinition {
        requireKind(ref, EntryType.Template)
        return component(ref, DocumentComponent.Template).let {
            rawClient.getTemplate(it.id, it.hash)
        }
    }

    /** @throws ComponentNotFoundException if the item has no pdf */
    public suspend fun getPdf(ref: ItemRef): ByteArray =
        component(ref, DocumentComponent.Pdf).let { rawClient.getBlob(it.id, it.hash) }

    /** @throws ComponentNotFoundException if the item has no epub */
    public suspend fun getEpub(ref: ItemRef): ByteArray =
        component(ref, DocumentComponent.Epub).let { rawClient.getBlob(it.id, it.hash) }

    private suspend fun documentFiles(ref: ItemRef): Map<String, ByteArray> = coroutineScope {
        componentEntries(ref)
            .map { entry -> async { entry.id to rawClient.getBlob(entry.id, entry.hash) } }
            .awaitAll()
            .toMap()
    }

    /**
     * the pen strokes, keyed by page id; order them by [DocumentContent.pages]
     *
     * A reMarkable notebook is nothing else — it has no pdf or epub behind it.
     *
     * One unparseable page fails the whole call, which is what makes [setPages] safe: a
     * document that cannot be read in full is never one this api offers to write back. To
     * salvage the readable pages of one that fails, walk it through [raw].
     *
     * @throws ValidationException if any page is malformed
     */
    public suspend fun getPages(ref: ItemRef): Map<String, RmFile> = pagedFiles(ref, PagedFile.Rm)

    /**
     * one page, or null if nothing has been drawn on it yet
     *
     * Costs one blob, where [getPages] downloads the whole document and fails if any *other*
     * page is malformed.
     *
     * A page exists because [DocumentContent.pages] lists it; the device writes its `.rm`
     * only once something is drawn. So an untouched page is real and empty, not missing.
     *
     * @throws ValidationException if the document has no page [pageId]
     */
    public suspend fun getPage(ref: ItemRef, pageId: String): RmFile? =
        pagedFile(ref, pageId, PagedFile.Rm)

    /**
     * Rejects an item that is not the kind the caller asked for.
     *
     * From `.metadata`, which states the kind, rather than from the `.content`, which is told
     * apart by which keys it has — a template's is empty and reads as a folder's.
     */
    private suspend fun requireKind(ref: ItemRef, expected: EntryType) {
        val actual = getMetadata(ref).type
        if (actual != expected) {
            throw ValidationException(
                "expected a ${expected.name} at '${ref.hash.hex}' but found a ${actual.name}",
            )
        }
    }

    /**
     * The page ids the item's `.content` declares, or every page it has a file for.
     *
     * [DocumentContent.pages] is null until the device first opens the document; the
     * component list is the same truth one step further out.
     */
    private suspend fun declaredPages(ref: ItemRef): Set<String> {
        val declared = (getContent(ref) as? DocumentContent)?.pages
        return declared?.toSet() ?: pagedFileIds(ref)
    }

    private suspend fun pagedFileIds(ref: ItemRef): Set<String> =
        componentEntries(ref).mapNotNullTo(mutableSetOf()) {
            PagedFile.Rm.pageIdOf(ref.id.value, it.id)
        }

    /**
     * every component file of the item, zipped
     *
     * A transfer format for round-tripping through [importArchive], not the document itself
     * — for that use [getPdf]/[getEpub], or walk the item with [raw].
     */
    public suspend fun exportArchive(ref: ItemRef): ByteArray = zipArchive(documentFiles(ref))

    /**
     * restores an archive produced by [exportArchive]
     *
     * Always lands under a fresh id, so a folder's children still point at the original
     * folder rather than the restored one.
     *
     * Takes an archive, not a pdf — for that use [putPdf] or [uploadPdf].
     */
    public suspend fun importArchive(
        archive: ByteArray,
        options: ImportOptions = ImportOptions(),
    ): ItemRef {
        val files = readArchive(archive)
        val metadataPath = files.keys.firstOrNull { it.endsWith(METADATA_SUFFIX) }
            ?: throw ValidationException("archive did not contain a .metadata file")
        require(!metadataPath.dropLast(METADATA_SUFFIX.length).contains('/')) {
            "unexpected nested .metadata path '$metadataPath'"
        }
        val oldId = metadataPath.dropLast(METADATA_SUFFIX.length)
        // minted before the retry loop so a retry re-uploads identical blobs
        val newId = ItemId(UUID.randomUUID().toString())
        val lastModified = System.currentTimeMillis().toString()

        val schemaVersion = root().schemaVersion
        val staged = files.map { (path, bytes) ->
            require(path.startsWith(oldId)) {
                "archived file '$path' did not start with '$oldId'"
            }
            // every archived path is prefixed with the document's own id, so restoring under
            // a new id means rewriting that prefix on all of them
            val newPath = newId.value + path.removePrefix(oldId)
            if (path == metadataPath) {
                rawClient.stageMetadata(
                    newPath,
                    restoredMetadata(bytes, options, lastModified),
                )
            } else {
                rawClient.stageFile(newPath, bytes)
            }
        }
        val docSchema = rawClient.stageEntries(newId.value, staged.map { it.entry }, schemaVersion)
        commitNewItem(staged, docSchema)
        return ItemRef(newId, docSchema.entry.hash)
    }

    private fun restoredMetadata(
        archivedBytes: ByteArray,
        options: ImportOptions,
        lastModified: String,
    ): Metadata {
        val archived = decodeWire(
            Metadata.serializer(),
            archivedBytes.toString(Charsets.UTF_8),
            "archived metadata",
        )
        return archived.copy(
            parent = options.parent ?: archived.parent,
            visibleName = options.visibleName ?: archived.visibleName,
            lastModified = lastModified,
        )
    }

    /**
     * builds a pdf document locally and commits it through the sync protocol
     *
     * This constructs every component file itself, so it takes the full [PutOptions] and is
     * sensitive to the root generation. Its counterpart [uploadPdf] hands the file to the
     * server instead: fewer options, but the server does the work.
     */
    public suspend fun putPdf(
        visibleName: String,
        pdf: ByteArray,
        options: PutOptions = PutOptions(),
    ): ItemRef = putDocumentFile(visibleName, FileType.Pdf, pdf, options)

    /** the epub counterpart of [putPdf]; see it for how this differs from [uploadEpub] */
    public suspend fun putEpub(
        visibleName: String,
        epub: ByteArray,
        options: PutOptions = PutOptions(),
    ): ItemRef = putDocumentFile(visibleName, FileType.Epub, epub, options)

    private suspend fun putDocumentFile(
        visibleName: String,
        fileType: FileType,
        bytes: ByteArray,
        options: PutOptions,
    ): ItemRef {
        // every minted value is resolved here, before any retry, so re-running the commit
        // reuses the same blobs rather than orphaning a new set each time
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val metadata = newDocumentMetadata(visibleName, options, now)
        val content = newDocumentContent(fileType, bytes.size, options, now)

        val schemaVersion = root().schemaVersion
        val staged = listOf(
            rawClient.stageContent("$id$CONTENT_SUFFIX", content),
            rawClient.stageMetadata("$id$METADATA_SUFFIX", metadata),
            rawClient.stageFile("$id$PAGEDATA_SUFFIX", "\n".encodeToByteArray()),
            rawClient.stageFile("$id.${fileType.extension}", bytes),
        )
        val docSchema = rawClient.stageEntries(id, staged.map { it.entry }, schemaVersion)
        commitNewItem(staged, docSchema)
        return ItemRef(ItemId(id), docSchema.entry.hash)
    }

    private fun newDocumentMetadata(
        visibleName: String,
        options: PutOptions,
        now: Long,
    ): Metadata = Metadata(
        visibleName = visibleName,
        parent = options.parent,
        pinned = options.starred,
        type = EntryType.Document,
        lastModified = now.toString(),
        createdTime = now.toString(),
        lastOpened = "0",
        lastOpenedPage = 0,
    )

    private fun newDocumentContent(
        fileType: FileType,
        sizeInBytes: Int,
        options: PutOptions,
        now: Long,
    ): DocumentContent = DocumentContent(
        coverPageNumber = options.coverPageNumber,
        documentMetadata = DocumentMetadata(
            authors = options.authors,
            title = options.title,
            publicationDate = options.publicationDate,
            publisher = options.publisher,
        ),
        extraMetadata = options.extraMetadata,
        fileType = fileType,
        fontName = options.fontName,
        lineHeight = options.lineHeight,
        orientation = options.orientation,
        // the device wants a page count of 1 and a matching page list even though it has
        // not rendered the file yet; anything else produces a document it won't open
        pageCount = 1,
        originalPageCount = 1,
        pages = listOf(UUID.randomUUID().toString()),
        redirectionPageMap = listOf(0),
        pageTags = emptyList(),
        textAlignment = options.textAlignment,
        textScale = options.textScale,
        zoomMode = options.zoom.mode,
        customZoomScale = (options.zoom as? Zoom.Custom)?.scale,
        customZoomCenterX = (options.zoom as? Zoom.Custom)?.centerX,
        customZoomCenterY = (options.zoom as? Zoom.Custom)?.centerY,
        customZoomPageWidth = (options.zoom as? Zoom.Custom)?.pageWidth,
        customZoomPageHeight = (options.zoom as? Zoom.Custom)?.pageHeight,
        customZoomOrientation = (options.zoom as? Zoom.Custom)?.orientation,
        tags = options.tags
            ?.let { names -> Tags.Structured(names.map { Tag(it, now) }) }
            ?: Tags.Empty,
        formatVersion = 1,
        margins = options.margins,
        sizeInBytes = sizeInBytes.toString(),
        viewBackgroundFilter = options.viewBackgroundFilter,
    )

    /** creates a folder through the sync protocol; the counterpart of [uploadFolder] */
    public suspend fun putFolder(visibleName: String, parent: Parent = Parent.Root): ItemRef {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis().toString()
        val metadata = Metadata(
            visibleName = visibleName,
            parent = parent,
            pinned = false,
            type = EntryType.Collection,
            lastModified = now,
            createdTime = now,
        )
        val schemaVersion = root().schemaVersion
        val staged = listOf(
            rawClient.stageContent("$id$CONTENT_SUFFIX", CollectionContent()),
            rawClient.stageMetadata("$id$METADATA_SUFFIX", metadata),
        )
        val docSchema = rawClient.stageEntries(id, staged.map { it.entry }, schemaVersion)
        commitNewItem(staged, docSchema)
        return ItemRef(ItemId(id), docSchema.entry.hash)
    }

    /**
     * hands a pdf to the server's ingestion endpoint, which builds the document
     *
     * Robust and simple, but offers no control over how the document is rendered. Use
     * [putPdf] when you need [PutOptions].
     */
    public suspend fun uploadPdf(visibleName: String, pdf: ByteArray): ItemRef =
        rawClient.uploadFile(visibleName, pdf, UploadKind.Pdf)

    /** the epub counterpart of [uploadPdf]; see [putEpub] for the alternative */
    public suspend fun uploadEpub(visibleName: String, epub: ByteArray): ItemRef =
        rawClient.uploadFile(visibleName, epub, UploadKind.Epub)

    /** creates a folder through the ingestion endpoint; the counterpart of [putFolder] */
    public suspend fun uploadFolder(visibleName: String): ItemRef =
        rawClient.uploadFile(visibleName, ByteArray(0), UploadKind.Folder)

    /** @throws ValidationException if the item at [ref] is not a document */
    public suspend fun getDocumentContent(ref: ItemRef): DocumentContent {
        requireKind(ref, EntryType.Document)
        return getContent(ref).orFail(ref.hash)
    }

    /** @throws ValidationException if the item at [ref] is not a folder */
    public suspend fun getCollectionContent(ref: ItemRef): CollectionContent {
        requireKind(ref, EntryType.Collection)
        return getContent(ref).orFail(ref.hash)
    }

    /**
     * writes a document's `.content`
     *
     * Keys the wire carries but this api does not model are preserved from what is already
     * stored, so writing back a value that never saw them cannot drop them.
     *
     * @throws ValidationException if the item at [ref] is not a document
     */
    public suspend fun setDocumentContent(ref: ItemRef, content: DocumentContent): ItemRef =
        editContent(ref, EntryType.Document) { content }

    /** see [setDocumentContent]; @throws ValidationException if [ref] is not a folder */
    public suspend fun setCollectionContent(ref: ItemRef, content: CollectionContent): ItemRef =
        editContent(ref, EntryType.Collection) { content }

    private suspend fun editContent(
        ref: ItemRef,
        expected: EntryType,
        update: (Content) -> Content,
    ): ItemRef {
        val schemaVersion = beginEdit(ref)
        requireKind(ref, expected)

        val components = componentEntries(ref).toMutableList()
        val index = components.indexOfFirst { it.id.endsWith(CONTENT_SUFFIX) }
        if (index < 0) {
            throw ComponentNotFoundException(ref, DocumentComponent.Content)
        }
        val entry = components[index]
        // read the raw text rather than the decoded value: a firmware quirk key the api
        // deliberately doesn't model still has to be written back exactly as it arrived
        val original = rawClient.getText(entry.id, entry.hash)
        val staged = rawClient.stageFile(
            entry.id,
            encodeContent(update(decodeContent(original)), contentQuirks(original)).encodeToByteArray(),
        )
        components[index] = staged.entry
        val docSchema = rawClient.stageEntries(ref.id.value, components, schemaVersion)
        return commitEdit(ref, listOf(staged), docSchema)
    }

    /**
     * Rewrites one item's `.metadata`, returning the new file and the docSchema listing it.
     *
     * Bumping `version` and setting `metadatamodified` is what tells the device the change
     * came from somewhere other than itself.
     */
    private suspend fun stageMetadataEdit(
        item: ItemRef,
        schemaVersion: SchemaVersion,
        update: (Metadata) -> Metadata,
    ): Pair<StagedFile, StagedFile> {
        val components = componentEntries(item).toMutableList()
        val index = components.indexOfFirst { it.id.endsWith(METADATA_SUFFIX) }
        if (index < 0) {
            throw ComponentNotFoundException(item, DocumentComponent.Metadata)
        }
        val entry = components[index]
        val current = rawClient.getMetadata(entry.id, entry.hash)
        val updated = update(current).let {
            it.copy(version = (it.version ?: 0) + 1, metadatamodified = true)
        }
        val staged = rawClient.stageMetadata(entry.id, updated)
        components[index] = staged.entry
        return staged to rawClient.stageEntries(item.id.value, components, schemaVersion)
    }

    /**
     * writes a template's `.template`
     *
     * The counterpart of [getTemplate]. A template's `.content` is empty, so this is the
     * whole of what the device renders it from.
     *
     * @throws ValidationException if the item at [ref] is not a template
     */
    public suspend fun setTemplate(ref: ItemRef, definition: TemplateDefinition): ItemRef {
        val schemaVersion = beginEdit(ref)
        requireKind(ref, EntryType.Template)

        val components = componentEntries(ref).toMutableList()
        val index = components.indexOfFirst { it.id.endsWith(TEMPLATE_SUFFIX) }
        if (index < 0) {
            throw ComponentNotFoundException(ref, DocumentComponent.Template)
        }
        val staged = rawClient.stageTemplate(components[index].id, definition)
        components[index] = staged.entry
        val docSchema = rawClient.stageEntries(ref.id.value, components, schemaVersion)
        return commitEdit(ref, listOf(staged), docSchema)
    }

    /**
     * writes an item's `.metadata`
     *
     * The general form of [move], [rename] and [star], and the only way to reach the rest of
     * [Metadata] — `lastOpened`, `lastOpenedPage`, `deleted`, `source`, the flags.
     *
     * [Metadata.version] and [Metadata.metadatamodified] are overwritten regardless of what
     * [metadata] carries: they are how the device is told a change came from elsewhere.
     */
    public suspend fun setMetadata(ref: ItemRef, metadata: Metadata): ItemRef =
        editMetadata(ref) { metadata }

    private suspend fun editMetadata(
        ref: ItemRef,
        update: (Metadata) -> Metadata,
    ): ItemRef {
        val (metadataFile, docSchema) = stageMetadataEdit(ref, beginEdit(ref), update)
        return commitEdit(ref, listOf(metadataFile), docSchema)
    }

    /**
     * rewrites pages the document already has, leaving the rest alone
     *
     * Handing back a map from [getPages] uploads only what changed: an unedited page
     * serialises to the bytes it was read from, so it re-stages to a hash already cached.
     * A page that has never been drawn on gets its `.rm` created here.
     *
     * `lastModified` is not touched — call [setMetadata] too if the document should look
     * freshly edited.
     *
     * @throws ValidationException if [pages] names a page the document does not have. Adding
     * a page means listing it in [DocumentContent.pages], so that belongs to
     * [setDocumentContent].
     */
    public suspend fun setPages(ref: ItemRef, pages: Map<String, RmFile>): ItemRef =
        setPagedFiles(ref, pages, PagedFile.Rm)

    /** the single-page case of [setPages] */
    public suspend fun setPage(ref: ItemRef, pageId: String, page: RmFile): ItemRef =
        setPages(ref, mapOf(pageId to page))

    /**
     * every page's highlights, keyed by page id
     *
     * Only pages that carry highlights appear. A page's highlights are a list of lists
     * because reMarkable splits one highlighted passage into a fragment per line it spans.
     */
    public suspend fun getHighlights(ref: ItemRef): Map<String, List<List<Highlight>>> =
        pagedFiles(ref, PagedFile.Highlights)

    /** one page's highlights, or null if it has none; see [getHighlights] */
    public suspend fun getHighlights(ref: ItemRef, pageId: String): List<List<Highlight>>? =
        pagedFile(ref, pageId, PagedFile.Highlights)

    /**
     * writes the highlights of the pages named, leaving the rest alone
     *
     * A page with no highlights file yet gets one. Nothing checks the text against the
     * document: [Highlight.start] and [Highlight.length] index into the page's own text, and
     * this library does not extract that.
     */
    public suspend fun setHighlights(
        ref: ItemRef,
        highlights: Map<String, List<List<Highlight>>>,
    ): ItemRef = setPagedFiles(ref, highlights, PagedFile.Highlights)

    /** the single-page case of [setHighlights] */
    public suspend fun setHighlights(
        ref: ItemRef,
        pageId: String,
        highlights: List<List<Highlight>>,
    ): ItemRef = setHighlights(ref, mapOf(pageId to highlights))

    /**
     * the template name behind each page, in page order
     *
     * One entry per [DocumentContent.pages] entry, empty for a page with no template.
     * Returns an empty list for a document with no `.pagedata` at all.
     */
    public suspend fun getPagedata(ref: ItemRef): List<String> {
        val fileName = "${ref.id.value}$PAGEDATA_SUFFIX"
        val entry = componentEntries(ref).firstOrNull { it.id == fileName } ?: return emptyList()
        // exactly one trailing empty, from the terminating newline: dropping every trailing
        // empty would lose a real final page that has no template
        val lines = rawClient.getText(entry.id, entry.hash).split("\n")
        return if (lines.lastOrNull() == "") lines.dropLast(1) else lines
    }

    /**
     * writes the template name behind each page
     *
     * The device expects one entry per page and reads them positionally, so a list shorter
     * than [DocumentContent.pages] silently leaves later pages without a template. Nothing
     * here checks that, because the page list is in the `.content` and this call does not
     * read it.
     */
    public suspend fun setPagedata(ref: ItemRef, templates: List<String>): ItemRef {
        val schemaVersion = beginEdit(ref)
        requireKind(ref, EntryType.Document)
        val components = componentEntries(ref).toMutableList()
        val fileName = "${ref.id.value}$PAGEDATA_SUFFIX"
        val staged = rawClient.stageFile(fileName, templates.joinToString("") { "$it\n" }.encodeToByteArray())
        val index = components.indexOfFirst { it.id == fileName }
        if (index < 0) components.add(staged.entry) else components[index] = staged.entry
        val docSchema = rawClient.stageEntries(ref.id.value, components, schemaVersion)
        return commitEdit(ref, listOf(staged), docSchema)
    }

    /**
     * every page's layer metadata, keyed by page id
     *
     * Only pages that carry a metadata file appear; a page whose layers have never been
     * named has none. The layer order matches [RmFile.layers].
     */
    public suspend fun getPageMetadata(ref: ItemRef): Map<String, PageMetadata> =
        pagedFiles(ref, PagedFile.LayerMetadata)

    /** one page's layer metadata, or null if it has none; see [getPageMetadata] */
    public suspend fun getPageMetadata(ref: ItemRef, pageId: String): PageMetadata? =
        pagedFile(ref, pageId, PagedFile.LayerMetadata)

    /** writes the layer metadata of the pages named, leaving the rest alone */
    public suspend fun setPageMetadata(
        ref: ItemRef,
        metadata: Map<String, PageMetadata>,
    ): ItemRef = setPagedFiles(ref, metadata, PagedFile.LayerMetadata)

    /** the single-page case of [setPageMetadata] */
    public suspend fun setPageMetadata(
        ref: ItemRef,
        pageId: String,
        metadata: PageMetadata,
    ): ItemRef = setPageMetadata(ref, mapOf(pageId to metadata))

    /** every page that has this file, keyed by page id */
    private suspend fun <T> pagedFiles(ref: ItemRef, kind: PagedFile<T>): Map<String, T> =
        coroutineScope {
            componentEntries(ref)
                .mapNotNull { entry -> kind.pageIdOf(ref.id.value, entry.id)?.to(entry) }
                .map { (pageId, entry) ->
                    async { pageId to kind.read(rawClient, entry.id, entry.hash) }
                }
                .awaitAll()
                .toMap()
        }

    /** one page's file, or null when the page carries none */
    private suspend fun <T> pagedFile(ref: ItemRef, pageId: String, kind: PagedFile<T>): T? {
        if (pageId !in declaredPages(ref)) {
            noSuchPages(ref.id.value, listOf(pageId))
        }
        val fileName = kind.fileName(ref.id.value, pageId)
        val entry = componentEntries(ref).firstOrNull { it.id == fileName } ?: return null
        return kind.read(rawClient, entry.id, entry.hash)
    }

    private suspend fun <T> setPagedFiles(
        ref: ItemRef,
        values: Map<String, T>,
        kind: PagedFile<T>,
    ): ItemRef = if (values.isEmpty()) {
        ref
    } else {
        val schemaVersion = beginEdit(ref)
        val unknown = values.keys - declaredPages(ref)
        if (unknown.isNotEmpty()) {
            noSuchPages(ref.id.value, unknown)
        }

        // the device writes one of these only when there is something to write, so a
        // first write adds an entry where a later one replaces it
        val components = componentEntries(ref).toMutableList()
        val existing = components.withIndex()
            .mapNotNull { (index, entry) -> kind.pageIdOf(ref.id.value, entry.id)?.to(index) }
            .toMap()
        val staged = values.map { (pageId, value) ->
            kind.stage(rawClient, kind.fileName(ref.id.value, pageId), value)
                .also { file ->
                    val index = existing[pageId]
                    if (index == null) {
                        components.add(file.entry)
                    } else {
                        components[index] = file.entry
                    }
                }
        }
        val docSchema = rawClient.stageEntries(ref.id.value, components, schemaVersion)
        commitEdit(ref, staged, docSchema)
    }

    /** moves an item */
    public suspend fun move(ref: ItemRef, parent: Parent): ItemRef =
        editMetadata(ref) { it.copy(parent = parent) }

    /** moves an item to the trash; reMarkable has no hard delete */
    public suspend fun trash(ref: ItemRef): ItemRef = move(ref, Parent.Trash)

    /** renames an item */
    public suspend fun rename(ref: ItemRef, visibleName: String): ItemRef =
        editMetadata(ref) { it.copy(visibleName = visibleName) }

    /** stars or unstars an item; the wire calls this `pinned` */
    public suspend fun star(ref: ItemRef, starred: Boolean): ItemRef =
        editMetadata(ref) { it.copy(pinned = starred) }

    /**
     * moves many items in a single root write
     *
     * Racing another client is normal, so a ref no longer in the root index is reported in
     * [BulkResult.notFound] rather than failing the batch.
     */
    public suspend fun bulkMove(refs: Collection<ItemRef>, parent: Parent): BulkResult {
        val current = root()
        // keyed on both halves, per [commitEdit], and keying on the hash alone would also
        // silently drop a duplicate ref
        val wanted = refs.associateBy { it.id.value to it.hash }
        val toUpdate = rawClient.getEntries(ROOT_SCHEMA, current.hash).entries
            .filter { (it.id to it.hash) in wanted }
        if (toUpdate.isEmpty()) {
            return BulkResult(emptyMap(), refs.toSet())
        }

        val staged = coroutineScope {
            toUpdate.map { item ->
                async {
                    val (metadataFile, docSchema) = stageMetadataEdit(
                        ItemRef(ItemId.ofWire(item.id), item.hash),
                        current.schemaVersion,
                    ) { it.copy(parent = parent) }
                    Triple(item, metadataFile, docSchema)
                }
            }.awaitAll()
        }
        val files = staged.flatMap { (_, metadataFile, docSchema) ->
            listOf(metadataFile, docSchema)
        }
        val edits = staged.associate { (item, _, docSchema) ->
            (item.id to item.hash) to docSchema.entry
        }

        // an item another client wrote in the meantime is no longer at the hash these
        // rebuilt indexes were derived from, so it drops out of the merge and is reported
        // as not found rather than being written over
        val rewritten = withGenerationRetry {
            val attempt = root()
            val entries = rawClient.getEntries(ROOT_SCHEMA, attempt.hash).entries
            val applied = entries.mapNotNull { entry ->
                edits[entry.id to entry.hash]?.let { (entry.id to entry.hash) to it }
            }
            // the server accepts an unchanged root and still burns a generation
            if (applied.isEmpty()) {
                emptyList()
            } else {
                val rootIndex = rawClient.stageEntries(
                    ROOT_LIST,
                    entries.map { edits[it.id to it.hash] ?: it },
                    SchemaVersion.V4,
                )
                uploadAll(files + rootIndex)
                commitRoot(rootIndex.entry.hash, attempt.generation)
                applied
            }
        }

        val moved = rewritten.associate { (key, entry) ->
            val ref = wanted.getValue(key)
            ref to ItemRef(ref.id, entry.hash)
        }
        return BulkResult(moved = moved, notFound = (refs.toSet() - moved.keys))
    }

    /** trashes many items in one root write; see [bulkMove] */
    public suspend fun bulkTrash(refs: Collection<ItemRef>): BulkResult =
        bulkMove(refs, Parent.Trash)

    /** to hand back as [SessionOptions.cache] in a later session */
    public fun dumpCache(): String = rawClient.dumpCache()

    /** empties the cache */
    public fun clearCache(): Unit = rawClient.clearCache()

    /** drops every cached hash the root index can no longer reach */
    public suspend fun pruneCache() {
        val current = root()
        val unreachable = rawClient.cachedHashes().toMutableSet()
        unreachable.remove(current.hash.hex)

        var frontier = listOf(rawClient.getEntries(ROOT_SCHEMA, current.hash).entries)
        while (frontier.isNotEmpty()) {
            val reached = frontier.flatten()
            reached.forEach { unreachable.remove(it.hash.hex) }
            frontier = descend(reached.filter { it.subfiles > 0 })
        }
        unreachable.forEach(rawClient::forget)
    }

    private suspend fun descend(parents: List<RawEntry>): List<List<RawEntry>> = coroutineScope {
        parents
            .map { async { rawClient.getEntries("${it.id}$SCHEMA_SUFFIX", it.hash).entries } }
            .awaitAll()
    }
}

/** Reports page ids an item does not declare; shared so a read and a write agree. */
private fun noSuchPages(itemId: String, pageIds: Collection<String>): Nothing =
    throw ValidationException(
        "document '$itemId' has no page ${pageIds.sorted().joinToString()}",
    )

/**
 * Narrows decoded content to the variant the caller asked to edit.
 *
 * An item's `.metadata` says what kind of thing it is, but the `.content` file is told
 * apart by which keys it has, and the two can disagree: a document whose content is `{}`
 * decodes as a collection. Checking the metadata alone would leave an unchecked cast to
 * fail as a ClassCastException, where the api promises a ValidationException.
 */
private inline fun <reified T : Content> Content.orFail(hash: FileHash): T = this as? T
    ?: throw ValidationException(
        "the content at '${hash.hex}' is ${this::class.simpleName}, " +
            "not the ${T::class.simpleName} its metadata implies",
    )

/**
 * a file a document keeps once per page, named `<prefix><pageId><suffix>`
 *
 * Deriving both the name and the page id from one prefix and suffix is the point: two
 * independent functions would let the writer and the reader drift apart, and the `.rm` and
 * the layer metadata of a page differ only by suffix.
 *
 * A page exists because [DocumentContent.pages] lists it. Whether it has any given one of
 * these is separate: the device writes each only when there is something to write, so an
 * absent file means an empty page rather than a missing one.
 */
internal sealed class PagedFile<T>(private val suffix: String) {
    protected abstract fun prefix(docId: String): String

    // the raw tier owns every codec; these only say which of its accessors to use
    abstract suspend fun read(raw: RawRemarkableClient, fileName: String, hash: FileHash): T

    abstract fun stage(raw: RawRemarkableClient, id: String, value: T): StagedFile

    fun fileName(docId: String, pageId: String): String = "${prefix(docId)}$pageId$suffix"

    fun pageIdOf(docId: String, fileId: String): String? {
        val prefix = prefix(docId)
        return if (fileId.startsWith(prefix) && fileId.endsWith(suffix)) {
            fileId.removePrefix(prefix).removeSuffix(suffix)
        } else {
            null
        }
    }

    /** the pen strokes, `<docId>/<pageId>.rm` */
    object Rm : PagedFile<RmFile>(RM_SUFFIX) {
        override fun prefix(docId: String): String = "$docId/"

        override suspend fun read(raw: RawRemarkableClient, fileName: String, hash: FileHash) =
            raw.getRm(fileName, hash)

        override fun stage(raw: RawRemarkableClient, id: String, value: RmFile) =
            raw.stageRm(id, value)
    }

    /** text highlights, `<docId>.highlights/<pageId>.json` */
    object Highlights : PagedFile<List<List<Highlight>>>(".json") {
        override fun prefix(docId: String): String = "$docId$HIGHLIGHTS_SUFFIX/"

        override suspend fun read(raw: RawRemarkableClient, fileName: String, hash: FileHash) =
            raw.getHighlights(fileName, hash)

        override fun stage(raw: RawRemarkableClient, id: String, value: List<List<Highlight>>) =
            raw.stageHighlights(id, value)
    }

    /** layer names, `<docId>/<pageId>-metadata.json`, beside the page's own `.rm` */
    object LayerMetadata : PagedFile<PageMetadata>(PAGE_METADATA_SUFFIX) {
        override fun prefix(docId: String): String = "$docId/"

        override suspend fun read(raw: RawRemarkableClient, fileName: String, hash: FileHash) =
            raw.getPageMetadata(fileName, hash)

        override fun stage(raw: RawRemarkableClient, id: String, value: PageMetadata) =
            raw.stagePageMetadata(id, value)
    }
}

/**
 * how the document is scaled to the screen, read back from the flat wire fields
 *
 * Null when the `.content` has no `zoomMode`, or when it says `customFit` without carrying
 * all six numbers a [Zoom.Custom] needs — a state the wire can hold and this type cannot.
 */
public val DocumentContent.zoom: Zoom?
    get() = when (zoomMode) {
        null -> null
        ZoomMode.BestFit -> Zoom.BestFit
        ZoomMode.FitToHeight -> Zoom.FitToHeight
        ZoomMode.FitToWidth -> Zoom.FitToWidth
        ZoomMode.CustomFit -> Zoom.Custom(
            scale = customZoomScale ?: return null,
            centerX = customZoomCenterX ?: return null,
            centerY = customZoomCenterY ?: return null,
            pageWidth = customZoomPageWidth ?: return null,
            pageHeight = customZoomPageHeight ?: return null,
            orientation = customZoomOrientation ?: return null,
        )
    }

/** the same content scaled a different way, with every `customZoom*` field rewritten */
public fun DocumentContent.withZoom(zoom: Zoom): DocumentContent {
    val custom = zoom as? Zoom.Custom
    return copy(
        zoomMode = zoom.mode,
        customZoomScale = custom?.scale,
        customZoomCenterX = custom?.centerX,
        customZoomCenterY = custom?.centerY,
        customZoomPageWidth = custom?.pageWidth,
        customZoomPageHeight = custom?.pageHeight,
        customZoomOrientation = custom?.orientation,
    )
}

/** the wire's flat mode value for a [Zoom] */
internal val Zoom.mode: ZoomMode
    get() = when (this) {
        Zoom.BestFit -> ZoomMode.BestFit
        Zoom.FitToHeight -> ZoomMode.FitToHeight
        Zoom.FitToWidth -> ZoomMode.FitToWidth
        is Zoom.Custom -> ZoomMode.CustomFit
    }

private fun zipArchive(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun readArchive(archive: ByteArray): Map<String, ByteArray> {
    val files = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                files[entry.name] = zip.readBytes()
            }
            zip.closeEntry()
        }
    }
    return files
}
