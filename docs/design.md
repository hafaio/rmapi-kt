# rmapi-kt — Design

Why this library is shaped the way it is.

rmapi-kt is a Kotlin/JVM client for the reMarkable cloud: an undocumented,
reverse-engineered, content-addressed sync protocol. §2 describes that protocol, because
almost every decision here follows from it. §3 is the decisions themselves.

For the API's signatures, read the generated documentation; this document explains the
choices behind them.

---

## 1. Summary

- **What**: `hafaio/rmapi-kt`, a single-module Kotlin/JVM **library** (no CLI), group
  `io.hafa`, package `io.hafa.rmapikt`, published via JitPack. Plain Kotlin/JVM that is
  deliberately **Android-safe (minSdk 21, no desugaring)** — no Android module, no AGP.
- **Feature set**: registration/auth/session; a high-level client (list, get,
  create/upload, update, move/rename/star/trash, bulk operations, cache management); a
  low-level raw client (root hash and generation, blob get/put, entry indexes, schema 3+4
  reads with schema-4 root writes, the ingestion upload endpoint); and `.rm` stroke
  parsing and writing (§D13).
- **Design posture**: make the protocol's hazards unrepresentable. A `suspend` API with
  structured concurrency; sealed `Entry` and `Content` hierarchies with a sealed `Tags`
  type for the two shapes the wire stores tags in; value classes for hashes and ids, and
  an `ItemRef` pairing them that is the currency of every call, in and out; a sealed
  `Parent` instead of `""`/`"trash"` magic strings; one explicit `refreshRoot()` rather
  than a `refresh` flag on every method; separate `put*` and `upload*` families because
  they are separate mechanisms; an error taxonomy built around what a caller branches on;
  a cache of bytes with a versioned dump; and strict decoding everywhere (§D4).
- **Dependencies**: `kotlinx-serialization-json`, `kotlinx-coroutines-core`, and
  **OkHttp**, used directly — the library's only injectable is an
  `OkHttpClient` in `SessionOptions` (timeouts/interceptors/proxies, the established
  JVM pattern; §D3). No transport abstraction, no test seams of any kind. SHA-256
  (`MessageDigest`), zip (`java.util.zip`), and UUIDs (`java.util.UUID`) come from
  the platform; CRC32C is a ~30-line internal table implementation
  (`java.util.zip.CRC32C` needs Android 34). The JS library's `crc-32`, `jszip`, and
  `uuid` dependencies disappear.
- **Build/CI**: Gradle Kotlin DSL, Kotlin on JVM 17, detekt (default rules plus a
  public-API KDoc gate), a Dokka javadoc jar, JitPack publishing, `build`/`cut`/`release`
  workflows, a 90% Kover line-coverage gate, and an animalsniffer check against Android
  API-21 signatures.
- **Verification**: unit tests against a local **MockWebServer**, reached through the
  `authHost`/`rawHost`/`uploadHost` options that exist for rmfakecloud anyway, so the
  suite needs *zero* test-only API surface. Index hashing and `.rm` parsing are
  additionally checked against data from a real account (§7).

---

## 2. The Protocol

The reMarkable cloud API is undocumented. Everything below was established by reverse
engineering and is corroborated by more than one independent implementation, but none of it
is guaranteed by the vendor — which is why §D4 and §D13 are as strict as they are.

**The store is immutable and content-addressed.** Every file lives under the SHA-256 of its
own bytes at `GET`/`PUT {rawHost}/sync/v3/files/{hash}`, with an `rm-filename` header naming
the logical file and `x-goog-hash: crc32c=<base64>` on uploads. Nothing is ever modified in
place; changing a file means writing a new one and re-pointing whatever referenced it.

**An item is an index, and the account is an index of indexes.** An entry index is a text
file of `hash:type:id:subfiles:size` lines. Schema 3 is just those lines behind a version
line; schema 4 adds a `0:<id>:<count>:<size>` header. A document's index lists its component
files (`.content`, `.metadata`, `.pagedata`, the pdf or epub, and the per-page `.rm` files);
the root index lists every item.

The two schemas hash differently, and this is the single most error-prone part of the
format: **schema 3 hashes an index as the SHA-256 of its entries' concatenated hashes**,
while **schema 4 hashes the index file's own bytes** like any other file. Newly written root
indexes must always be schema 4 — the cloud rejects a schema-3 root with a 400 "Software
must be updated" — while an item's index follows whatever schema the account uses.

**Writes are compare-and-swap on a generation.** The root hash and a monotonically
increasing generation live at `GET {rawHost}/sync/v4/root` and `PUT {rawHost}/sync/v3/root`.
A PUT carrying a stale generation fails with `{"message":"precondition failed"}`. That is
the entire concurrency-control story: any edit is a read-modify-write of the root, and two
clients editing at once means one of them loses and must re-apply. §D12 covers how.

**Auth is two tokens.** `POST {authHost}/token/json/2/device/new`, carrying an eight-letter
one-time code plus a device description and uuid, returns a long-lived device token as raw
text — not JSON. `POST {authHost}/token/json/2/user/new` with that token as a bearer returns
a short-lived session token, used as the bearer everywhere else. The default hosts are
`webapp-prod.cloud.remarkable.engineering` for auth, `eu.tectonic.remarkable.com` for blobs,
and `internal.cloud.remarkable.com` for ingestion.

**There is a second, simpler way to add a document.** `POST {uploadHost}/doc/v2/files` with
the file's mime type, an `rm-meta` header (base64 JSON naming the file), and
`rm-source: RoR-Browser` returns `{docID, hash}`. The server builds the item itself, so this
path never touches the root index or a generation. It is a genuinely different mechanism
from constructing the component files locally, which is why the API keeps both (§4).

**Dead ends**, recorded so they are not rediscovered: sync10 and the
`/sync/v2/signed-urls/*` endpoints are gone; `POST /sync/v2/sync-complete` is a no-op stub.
Notifying other devices is purely the `broadcast: true` flag on the root PUT.

**Provenance.** The wire understanding here draws on the open-source
[rmapi-js](https://github.com/erikbrinkman/rmapi-js) and Go
[rmapi](https://github.com/ddvk/rmapi) implementations, which agree on the endpoints, the
hashing rules, and the root-generation semantics. Where they differ, the differences are
cosmetic and both work against the real cloud: Go sends `rm-filename` on the root PUT and a
content type on blob PUTs where js sends neither, and Go sorts index entries by byte order
where js uses a locale compare — identical for the ids actually in play, which is why this
library sorts with plain `compareTo` and pins the result with golden-hash tests. Go's
independent index and hashing fixtures are used as a cross-check (§8).

---

## 3. Design Decisions

Each decision: what it is, why, and what was rejected.

### D1. Naming, group, module layout

**Decision**: repo `hafaio/rmapi-kt`, single Gradle module, group `io.hafa`, artifact
`rmapi-kt`, package `io.hafa.rmapikt`. Library only — no CLI module.

- Package name = artifact name = repo basename, so the JitPack coordinates
  (`com.github.hafaio:rmapi-kt`) and the import path agree with each other.
- Repo settings: squash-only merges,
  delete-branch-on-merge, issues on, wiki off; topics `remarkable`,
  `remarkable-tablet`, `kotlin`, `jvm`, `coroutines`, `api-client`, `android-library`;
  homepage → `https://jitpack.io/#hafaio/rmapi-kt`; description: "Kotlin/JVM client for
  the reMarkable cloud API. Android-compatible."
- *Rejected*: `core`/`cli` multi-module (out of scope), package `io.hafa.rmapi`
  (breaks the package = artifact convention).

### D2. Kotlin/JVM targets and the Android floor

**Decision**: current Kotlin (the exact version lives in `build.gradle.kts`, not here),
**JVM target 17** — the lowest current LTS, which keeps the widest set of consumers
available and is what Android's toolchain consumes without complaint — plus an
API-usage discipline keeping the library **Android-safe at minSdk 21 with no
desugaring**, enforced by animalsniffer against `gummy-bears` API-21 signatures in CI:

- JVM *bytecode* 17 is fine on Android (AGP 8+/D8 consume class-file 61); the
  constraint is *API* availability, so: JDK APIs must exist on API 21.
- Per-API audit:
  - `java.time` — **not used** (the wire wants epoch-millis strings; the internal
    clock is `nowMillis(): Long`, §D10). This alone would have forced API 26.
  - HTTP — OkHttp (§D3), whose 4.x line supports API 21; that sets the
    Android floor. `java.net.http.HttpClient` is Android 34+ and unusable.
  - `java.util.zip.CRC32C` — Android 34+; replaced by a ~30-line internal
    table-driven Castagnoli implementation (`Digest.kt`), pinned by RFC 3720 vectors
    and cross-checked against the JS `crc-32/crc32c` package's outputs.
  - `java.util.Base64` — Android 26+; use Kotlin stdlib `kotlin.io.encoding.Base64`
    (stable in 2.2) for `x-goog-hash` and `rm-meta`.
  - `java.util.HexFormat` — JDK 17-only, absent on Android; tiny internal
    `toHex`/`hexToBytes`.
  - Logging — none; the one warning (writing a schema-3 root) is `System.err.println`:
    logcat-visible, zero dependencies, zero API surface.
  - Safe and used freely: `MessageDigest`, `java.util.zip.Zip{Input,Output}Stream`
    (API 1), `java.util.UUID`, `java.util.concurrent`, `Charsets.UTF_8`.
  - No file I/O anywhere (cache dump is a `String`; token persistence is the
    caller's job) — no `java.nio.file` concerns.
  - kotlinx-coroutines and kotlinx-serialization both run at API 21.
- *Rejected*: JVM target 8/11 "for Android" (bytecode level isn't the constraint),
  JVM 21, Kotlin Multiplatform (large build cost; the JS side of the fence already
  exists), an AGP/Android module (plain JVM that happens to be Android-safe).

### D3. HTTP: OkHttp used directly, configurable via an `OkHttpClient` option

**Decision**: rmapi-kt uses **OkHttp** directly and internally — no transport
abstraction, no HTTP types of its own. The library's only HTTP-related public surface
is one option:

```kotlin
public data class SessionOptions(
    val rawHost: String = Hosts.RAW,
    val uploadHost: String = Hosts.UPLOAD,
    val cache: String? = null,                 // a previous dumpCache() (§D9)
    val maxCacheBytes: Long = …,               // total cache size
    val maxCachedBlobBytes: Int = …,           // the largest blob worth keeping whole
    val httpClient: OkHttpClient = OkHttpClient(),
)
```

- **Why an `OkHttpClient` parameter instead of a transport seam**: users don't want to
  reimplement HTTP — they want timeouts, interceptors, certificate pinning, or a
  proxy. Accepting an `OkHttpClient` gives them all of that with **zero new types**,
  and it is the established JVM pattern (Retrofit's `.client(...)`). An earlier draft
  of this design defined a four-type `HttpTransport` seam; on inspection its only
  real customer was the test suite, and MockWebServer serves tests better with no API
  surface at all (§7/§8) — so the seam was machinery without a production customer,
  and it's gone.
- **The honest cost**: OkHttp is now part of the public API contract
  (`SessionOptions.httpClient`), so changing engines later is a breaking change. This
  is acceptable: OkHttp is the JVM/Android standard
  with a decades-long track record, and this exact coupling is what Retrofit et al.
  have shipped successfully for years.
- **Why OkHttp**: the Android-standard, battle-tested HTTP client; its 4.x
  floor (API 21) *is* the library's Android floor (prefer the current stable 5.x if
  its floor still permits API 21 at implementation time — which also unlocks
  `mockwebserver3`, §6); one transitive dep (okio).
- **Coroutine adapter, now an implementation detail**: a ~15-line internal
  `suspendCancellableCoroutine` wrapper around `Call.enqueue` that cancels the call on
  coroutine cancellation. Callers never see it.
- **Whole-`ByteArray` bodies internally, deliberate**: the protocol is
  content-addressed — every upload must be fully materialized to SHA-256-hash it
  *before* the request is made, and every download is cached/verified against its
  hash — so nothing streams, by design, at reMarkable document sizes.
- **How tests fake the network with no seam**: the `authHost`/`rawHost`/`uploadHost`
  options already exist (for rmfakecloud); tests point them at a local MockWebServer, so
  testing needs nothing beyond options the library has anyway. This is the core reason the
  seams were unnecessary.
- *Rejected*: **a custom `HttpTransport` abstraction** (previous draft — test-only
  customer, see above), **`java.net.http.HttpClient`** (Android 34+ only), **Ktor
  client** (a framework layer — plugin system, config DSL, several artifacts — over
  the same engine class of work), **`HttpURLConnection` zero-dependency default**
  (viable but rejected: OkHttp is worth its weight — connection pooling,
  HTTP/2, TLS handling — "okhttp is great").

### D4. JSON: kotlinx-serialization, decoded strictly

**Decision**: `kotlinx-serialization-json` (house JSON library). Two sub-decisions:

**(a) Decoding is strict everywhere.** Updates are read-modify-write of
`.content`/`.metadata` files, so a key the library doesn't model must not be quietly
discarded on the way back out. There are two ways to guarantee that: carry unknown keys
through untouched, or refuse to decode a payload you don't fully understand. This library
takes the second — `ignoreUnknownKeys = false` on *every* wire type, with an unrecognised
key raising `ValidationException`.

*Rationale*: simplicity, chosen over resilience with the trade understood. Passthrough
costs an `extra: JsonObject` on a dozen types, a serializer that splits and re-merges
unknown keys, and — the deciding factor — a manual registry mapping each type to that
serializer, where a forgotten entry silently reverts that type to dropping unknown keys,
with no compile error and no failing test. Strict decoding removes that failure mode by
construction.

*A related modelling error, found the same way*: templates were modelled as a third
`.content` shape, discriminated by a `templateVersion` key. Real templates have an **empty**
`.content` and keep their definition in a separate `.template` file, so all three templates
in a real account were reported as folders. An item's kind now comes from `metadata.type`,
which states it outright, and the definition is its own type ([TemplateDefinition]) read
through `getTemplate`. Its fields were wrong too — `constants` values are numbers *or*
expressions over other constants (`"templateWidth - (offsetX * 2)"`), and
`supportedScreens` and `labels` are each absent on some real templates despite reading as
required.

*One exception, and how it was found*: a small set of **quirk keys** is stripped before
decoding and written back verbatim afterwards, so it neither reaches the public api nor
breaks the parse. Today that set is exactly `modifed` — the device's own misspelling of
`modified`, present on 28 of 592 documents in a real account. Strict decoding made
`listItems()` throw outright on that account, which is the failure mode below arriving
early: not from a future firmware update, but from data already on the server. Modelling
the key would put a firmware typo in the public surface where a caller would have to decide
what it meant; carrying it opaquely costs nothing and keeps the strict policy for every key
that is not a known quirk.

*Accepted risk*: reMarkable adds fields to this schema over time — `cPages`,
`keyboardMetadata`, `transform`, `viewBackgroundFilter`, the `customZoom*` family and
`originalPageCount` all arrived this way. When it happens, `listItems()` throws for
every user until a release propagates, because it reads every item's `.content` and
`.metadata`. The escape hatches are deliberate and documented: `ValidationException`
carries the raw text, and the low-level `raw` client returns unparsed blobs, so a
caller is never actually locked out of their data. Fixing it is a field addition here
plus a release.

**(b) The two tag shapes are one sealed field, not duplicated classes.** Older firmware
writes tags as bare strings and current firmware as objects with timestamps. That is a
difference in one *field*, so it is modelled as one:

```kotlin
public sealed interface Tags {
    public data class Structured(val tags: List<Tag>) : Tags
    public data class Legacy(val names: List<String>) : Tags   // pre-structured-tag firmware
    public val names: List<String>                              // uniform read access
}
```

so `Content` is exactly three types — `CollectionContent`, `DocumentContent`,
`CollectionContent` (a template's is empty and reads as one) — and a legacy payload
round-trips byte-faithfully (a `Legacy` value
re-encodes as strings; no silent upconversion). Discrimination becomes structural and
simple: `templateVersion` present → template; `fileType` present → document; else
collection — equivalent to zod's ordered strict-first union matching on all real
payloads, pinned by the ported tests.

- *Rejected*: Moshi/Jackson (non-house), raw-`JsonObject`-with-accessors (kills
  data-class `copy()` ergonomics), normalizing legacy tags to `Tag(name, 0)` (breaks
  round-trip fidelity — a write would silently rewrite the device's tag format),
  keeping the four-class union (JS-ism with a 2× cost on every `when`).

### D5. Async model: suspend functions, structured concurrency, stage/upload

**Decision**: every network-touching function is a `suspend fun`. Internals use
`coroutineScope { async { … } }` where work is genuinely parallel — entry resolution in
`listRefs`, component uploads in `putPdf` — **unbounded** (no semaphores, no knobs): the
cloud has not needed throttling in practice, and a limit nobody can tune is worse than
none. No dispatcher is
hard-coded; OkHttp's `enqueue` runs on its own executor and the suspend adapter just
resumes the caller, so the API is safe from any context, including Android's main
thread. Generation conflicts throw `GenerationException` and invalidate the client's
cached root — retry policy is the caller's beyond the two layers in §D12.

**Hashing and uploading are separate operations.** Hashing a file is pure, local, and
cheap; uploading is neither. Combining them would mean handing back a value together with
an already-running background job — a `Deferred` with no owner, which structured
concurrency exists to avoid. So the raw client splits them into **stage + upload**:
`stageFile(...)` is pure (hash computed locally; returns the entry plus the bytes to
send) and `suspend fun upload(staged)` performs the PUT. High-level ops stage
everything, use the entries immediately, and `awaitAll` uploads inside their own
`coroutineScope` — same concurrency, honest ownership, and no raw-API method needs a
`CoroutineScope` parameter.

The split also suits the generation retry in §D12, though it is not what makes it correct.
What prevents a retry from stranding blobs is minting ids and timestamps *before* the retry
loop — a fresh uuid per attempt would produce a fresh set of files nothing references, and
that is true whether or not hashing and uploading are one call. Because the store is
content-addressed, re-sending identical bytes yields the identical hash and orphans nothing.
What staging contributes is that re-running it is free and deterministic: `editItem` stages
inside the retry, so every attempt after the first re-derives hashes the cache already knows
and uploads nothing. This is why `stageFile`/`stageText`/`stageContent` are named for what
they do rather than for the `put*` they correspond to on the wire: they do not put anything.

*Known gap*: nothing requires a staged file to be uploaded before an index references it.
`stageEntries` accepts bare `RawEntry` values, so committing a root that points at bytes
never sent is expressible, if not currently done. Making the index builders take
`StagedFile` would close it.

No blocking/Java facade in v1 — Java callers bridge with
`kotlinx.coroutines.future.future {}` (README shows it). *Rejected*: `Flow` variants
(rejected), callback/`CompletableFuture` surface, exposing hot `Deferred`s.

### D6. Type modeling

The wire is all strings. The API's job is to stop the caller confusing one string for
another, and to make the states the protocol allows the only ones expressible.

- **`FileHash` and `ItemId` are value classes**, validated on construction. They are the two
  strings that must never be swapped, and they are the same shape to the naked eye. Both
  cost nothing at runtime.
- **`ItemRef(id, hash)` is the currency of the whole API.** An item has a stable id and a
  hash that changes on every write, and almost every operation needs both. Passing them as
  two arguments is the single most likely caller bug in a client for this protocol, so they
  travel as one type — in *and* out, since an edit returns a new ref (§4).
- **There is no listing type.** A listing returns the wire's own `Metadata`, which already
  carries the name, parent, kind, and timestamps. A dedicated entry type was tried and
  removed: everything it exposed came from `Metadata` except `tags` and `fileType`, and
  fetching a `.content` per item to obtain two fields made a listing cost 3N requests
  instead of 2N. It also had to reconcile metadata against content, and that join was the
  sole cause of a real bug — templates reported as folders. `Metadata.type` is an enum, so
  `when` over it is exhaustive without a parallel hierarchy.
- **`sealed interface Parent { Root, Trash, Folder(id) }`.** The wire encodes the root as
  `""` and the trash as `"trash"`; leaving those as magic strings at call sites would make
  "move to the root" and "move to a folder named nothing" the same expression. The mapping
  lives in exactly one serializer.
- **Enums for every closed wire vocabulary**: `SchemaVersion`, `FileType`, `Orientation`,
  `TextAlignment`, `ZoomMode`, `BackgroundFilter`, `UploadKind`, `EntryType`,
  `DocumentComponent`. `TextAlignment`'s device default is the empty string on the wire,
  which is exactly the kind of value that should not be typed as `String`.
- **Every wire type is an immutable data class**, updated with `copy()`. `ByteArray` never
  sits inside one, because its equality is identity.
- **Timestamps convert at the boundary.** `Metadata` keeps the wire's stringified epoch
  millis because it round-trips; `Entry` is built for the caller and never written back, so
  it exposes `Long`. Doing this once, where the two meet, keeps `entry.lastModified > cutoff`
  compiling without pushing a date library into the API.

### D7. Surface principles

Five rules the public API follows, which between them explain most of its shape.

**One name, one mechanism.** `put*` and `upload*` do genuinely different things — `put*`
builds every component file locally and commits it through the sync protocol, with full
options and a generation to lose; `upload*` hands the file to the server's ingestion
endpoint, which is robust but offers no control. They are separate families rather than one
function with a flag, and each one's KDoc states the difference and points at its
counterpart, because the names alone cannot carry it.

**Say the thing, don't flag it.** Rather than a `refresh: Boolean` on every read, there is
one `refreshRoot()`. A boolean threaded through a dozen signatures describes a single
concept — whether to trust the cached root — and naming that concept once is cheaper than
repeating it everywhere, especially since the answer is almost always "no".

**Return something usable.** An edit returns an `ItemRef`, not a bare hash, so the result of
one edit is the input to the next. A bulk edit returns a `BulkResult` with both what moved
and what it could not find, so a caller who ignores the return value cannot silently receive
a partial no-op.

**No wrappers that carry nothing.** A single-field result type earns its place only when the
field needs a name the type doesn't already give it. `FileHash` already says what it is.

**A method must add something a caller cannot.** The test is whether removing it would push
the caller down to `raw`. `uploadPdf` passes — without it there is no way to name the right
upload kind except through the low-level api. `listMetadata()` failed it: it was one line over
calls that remain, `listRefs().associateWith(::getMetadata)`, so it was a second name for
something the api already said. It was also quietly all-or-nothing, throwing for the whole
account if a single item failed to parse, which the composed form leaves the caller free to
handle per item.

**Each tier has one currency, and bytes are not the high tier's.** `getPages` was removed
under the rule above and then restored, because the rule was being read against the wrong
sibling. It looked like one line over `getRawPages(ref).mapValues(::parseRmFile)` — but
`getRawPages` was itself the mistake: a method on the *typed* client handing back undecoded
bytes. `RemarkableClient` returns `Metadata`, `Content`, `TemplateDefinition`; a `.rm` file
has a decoded form too, so returning bytes made pages the one component a caller had to
finish decoding themselves. Removing `getRawPages` leaves `getPages` one line over nothing:
it is the only page accessor on the tier, and it knows the `<id>/<pageid>.rm` layout, which
is exactly what the tier is for. `getPdf` and `getEpub` still return bytes and are not
exceptions — a pdf has no decoded form in this library, so bytes *are* its type.

The all-or-nothing objection then answers itself. `getPages` does throw for the whole document
when one page is unreadable, and that is what makes `setPages` safe: a document that cannot
be read in full is never one this api will offer to write back. The escape hatch is the tier
whose currency bytes actually are — walk the entries with `raw` and `getBlob` the pages that
do parse.

`getPage` is the exception that proves the rule: it is *not* one line over `getPages`, because
`getPages(ref).getValue(id)` downloads and parses every page to return one, and throws if any
other page is malformed. Fetching a single blob is different work, not a shorter spelling.
`setPage` genuinely is one line over `setPages`, and is kept anyway — once `getPage`
exists for a real reason, a missing `setPage` becomes its own puzzle, and a caller who
found one will look for the other. Pair symmetry is worth one delegating line.

`raw.getRm` and `raw.stageRm` are each one line over `parseRmFile`/`serializeRmFile` and a
blob call, and both are kept: the raw tier pairs a `get*` with a `stage*` for every file kind
it names, and a half-pair is a worse signal than a short method — it reads as though writing
a page were supported and reading one were not.

`trash`/`bulkTrash` are one line over `move`/`bulkMove` too, and are kept anyway: the trash
is not a folder. It is the only delete this protocol has, and naming it as a verb is what
tells a caller that deleting is a move — and therefore reversible — rather than leaving them
to discover that `Parent.Trash` is where deletion lives. `dumpCache`/`clearCache` exist once,
on `RemarkableClient`, because the cache is configured once, on `SessionOptions`.

**Every write takes a value.** `setMetadata`, `setDocumentContent`, `setPages` and their
singular forms are all `set(ref, value)`; none takes a lambda over the stored value. A
`(T) -> T` parameter reads as a partial update and saves the caller one fetch, but it puts
their code inside this library's control flow to buy nothing they cannot do themselves, and
it does not generalise: a page write never needs the old value, so half the api would take a
lambda and half would not. A read, a `copy`, and a write is three ordinary steps a caller can
see, interrupt, or skip. Preserving unmodelled `.content` keys does not need the lambda
either — those come from the stored text, which the library reads regardless.

The same exemption covers `rename` and `star` beside `setMetadata`: each names a wire
field a caller would otherwise have to know is called something else — `visibleName`, and
`pinned` for a flag the device draws as a star. `move` earns its place by taking a `Parent`
rather than the raw string the field holds. The general form is public because the named
cases cannot cover the rest of `Metadata`, not because the named cases were a mistake; what
the rule forbids is a second name for the *same* thing, not a shorter name for a common one.

### D8. Error taxonomy — designed around what a caller branches on

What does a caller of this library actually branch on? (1) *"my view of the root was
stale — refetch and redo"*; (2) *"the server refused or broke — can I retry?"*;
(3) *"the payload didn't match the reverse-engineered schema — give me the raw text"*;
(4) *"the thing I referenced isn't there"*. The taxonomy models exactly those, sealed
so `when` is exhaustive:

```kotlin
public sealed class RemarkableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** the root moved under you: refresh (automatic on next call) and re-apply your change */
public class GenerationException(
    /** the generation this client sent, now stale */
    public val staleGeneration: Long,
) : RemarkableException("root generation $staleGeneration was stale; refetch and retry")

/** the server answered outside 2xx (and it wasn't a generation conflict) */
public class ResponseException(
    public val status: Int,
    public val statusText: String,
    public val body: String,
    message: String,
) : RemarkableException(message)

/** a payload didn't match the reverse-engineered schema; [rawText] is the escape hatch */
public class ValidationException(
    message: String,
    /** the raw text that failed validation, for callers who want to parse it themselves */
    public val rawText: String? = null,
    cause: Throwable? = null,
) : RemarkableException(message, cause)

/** the hash you passed isn't an entry of the current root index */
public class HashNotFoundException(public val hash: FileHash) :
    RemarkableException("'${hash.hex}' not found in the root index")

/** the item exists but lacks the requested component (e.g. no .epub on a pdf doc) */
public class ComponentNotFoundException(
    public val ref: ItemRef,
    public val component: DocumentComponent,   // Content | Metadata | Pdf | Epub | Template
) : RemarkableException("no $component in item ${ref.id.value}")
```

Design choices and their reasons:

- **`GenerationException` carries `staleGeneration`** — a retry/debug loop wants to
  know what it sent; the server doesn't report the new generation on conflict, so
  nothing more is pretended.
- **`ResponseException` does not opine on retryability**: callers have `status` and can
  draw their own line. This is no longer the whole story — the library *does* now retry
  internally (§D12), but only where it can tell that retrying is safe and useful; a
  `ResponseException` is by construction what survived that, so it stays a plain report.
- **`ValidationException` carries `rawText`** — the payload that failed. Against a
  reverse-engineered format, "this didn't parse" is not a dead end if the caller can still
  see what arrived, so the escape hatch is built into the exception rather than left as
  advice. All schema failures land here (wrapping
  `ZodError`s — one type to catch instead of two.
- **`ComponentNotFoundException`** types what JS throws as anonymous
  `Error("couldn't find pdf for hash …")` — a genuinely branchable condition ("this
  doc has no epub"), with an enum instead of a message to string-match.
- **What is *not* wrapped**: transport-level I/O failures surface as the transport's
  own `IOException`s, untranslated. Wrapping them would launder "your network is down"
  into "the reMarkable API failed" — different problems, different handlers.
- Programmer errors (`register` code length, a non-`.content` id given to
  `stageContent`, malformed hex at `FileHash` construction) use `require`/`check` →
  `IllegalArgumentException`/`IllegalStateException`, outside the sealed hierarchy —
  misuse is not a runtime condition to branch on.
- *Rejected*: `Result`/`Either` returns (fights suspend composition, non-idiomatic on
  the JVM, doubles every signature), a 1:1 mapping of the JS error classes (loyalty is
  not a design reason), error codes.

### D9. Caching

The *semantic model* is protocol-driven and stays: content-addressed data never goes
stale, so blobs cache by hash forever; text blobs (indexes, `.content`, `.metadata`)
are small and cached in full; binary blobs (pdf/epub) are large, so the cache records
only that the hash **exists** — enough to skip re-uploads, not re-downloads. The
*representation* is redesigned:

```kotlin
internal sealed interface CacheEntry {
    /** full contents of a small text blob */
    data class Text(val text: String) : CacheEntry
    /** a large blob known to exist server-side (skip re-upload, still fetch on read) */
    data object Exists : CacheEntry
}
```

- **Honest encoding**: a sealed `CacheEntry` says what each entry means. Encoding
  "known to exist" as a null value in a string map would make the "never re-upload a known
  hash" path read as an accident of nullability rather than as intent.
- **Values are bytes, and the split is on size rather than kind.** An earlier cache held
  decoded text, which meant it could only admit a blob that decodes losslessly — so `.rm`
  pages, the one thing a stroke-editing client reads repeatedly, were fetched every time.
  Bytes admit every kind, and a caller wanting text decodes on the way out. What is worth
  holding is then a size question: `maxCachedBlobBytes` bounds a single blob so one pdf
  cannot evict everything else to hold itself, and anything above it records only that the
  store has the hash, which still skips a re-upload. `maxCacheBytes` bounds the total.
  For what this cache actually holds — entry indexes and json metadata, almost entirely
  ascii — every candidate measure agrees anyway.
- **Dump format**: versioned, kotlinx-serialized JSON —
  `{"version": 1, "text": {"<hash>": "…"}, "exists": ["<hash>", …]}` — produced by
  `dumpCache(): String`, accepted by `SessionOptions.cache`. Round-trip guaranteed by
  test; the version field makes any future format change detectable instead of
  silently corrupting (`session` rejects unknown versions with a clear error — the
  behavior). JSON over an opaque binary format because the
  dump's second job is debuggability — being able to *read* what the client knows.
  The format is this library's own; a cache is a performance artifact, so there is no
  interchange obligation and a cold start costs only a few refetches.
- **LRU**: insertion-order eviction with get-refresh over
  `LinkedHashMap(accessOrder = true)`, bounded by `maxCacheBytes`, which defaults to a
  finite value so a long-running process cannot accumulate blobs indefinitely.

- **Thread safety**: all cache access in `synchronized(lock)` blocks — critical
  sections are pure map ops (no suspension, no I/O), so a monitor beats a suspending
  `Mutex`. The benign JS race (two concurrent `getText`s of one hash fetch twice, then
  converge) is kept; single-flighting is complexity without a correctness win.
- `pruneCache` keeps the BFS-from-root reachability sweep; `clearCache` empties.
- *Rejected*: Caffeine (a dependency for ~60 lines), entry-count sizing
  (entries range from 40 bytes to megabytes; count bounds nothing anyone cares
  about), TTLs (content-addressed data cannot go stale).

### D10. No test seams at all

**Decision**: the client calls `System.currentTimeMillis()` and
`UUID.randomUUID()` **directly**. There is no clock/id abstraction, no internal
secondary constructor, no injectable environment — earlier drafts had one, and the
user's question ("is there a better way in Kotlin that doesn't require an environment
seam?") exposed that, like the transport seam, its only customer was tests.

- How tests cope without it (§8.2): the golden-hash tests drive `stageEntries` with
  explicit caller-supplied entries, so nothing is minted on those paths. The few request bodies that genuinely contain minted
  values (`putPdf`/`putEpub`'s `.content`/`.metadata`) are asserted **structurally**
  (decode, assert every field, check the minted uuid/timestamp for well-formedness)
  rather than byte-exactly — a deliberate, slightly weaker assertion that is the
  price of the deletion.
- Epoch millis (`System.currentTimeMillis()`), not `java.time`: the wire wants
  epoch-millis strings, and `java.time.Clock` would push the Android floor to API 26
  (§D2).
- The only publicly controllable identity input remains `register`'s `uuid`
  parameter, which exists because a caller genuinely needs a stable device identity —
  not as a seam.
- *Rejected*: the internal `Environment` interface (previous draft — test-only
  customer), public clock/id options (test residue in the API), mocking frameworks
  that rewrite finals (heavyweight, fragile).

### D11. Hosts

**Decision**: the three production hosts as defaults (`Hosts.AUTH`, `Hosts.RAW`,
`Hosts.UPLOAD`), each overridable in options — which is also the rmfakecloud story (point
all three at the fake) and the reason the test suite needs no seam. No config-file
handling; persisting a token is the caller's concern.

### D12. Retries — two layers, for two unrelated failures

**Decision**: two independent retry layers, because there are two unrelated failures and
conflating them would be wrong:

- **Transient**, in `AuthedHttp`: a network exception, a 5xx, or a 429 is retried with
  exponential backoff and full jitter, base 200 ms, capped at 30 s,
  `maxTransientRetries` times (default 3). Applies to every request.
- **Generation conflict**, in `Remarkable.withGenerationRetry`: losing a race for the
  root index re-runs the whole read-modify-write, base 25 ms,
  `maxGenerationRetries` times (default 10). Set to 0 to surface
  `GenerationException` immediately.

**Why the split matters**: a generation conflict must *not* be retried at the request
layer. Resending the identical root write resends the identical stale generation, so it
can only fail again; the work that has to be redone is the read-modify-write above it.
That is why `AuthedHttp` raises the internal `PreconditionFailedException` rather than
retrying, and why `RawRemarkable.putRootHash` — the only place that knows which
generation was sent — converts it into the public `GenerationException`.

**The load-bearing detail**: every minted value (document ids, page ids, timestamps) is
resolved *before* the retry loop, so a retry re-uploads byte-identical blobs. Minting
inside the loop would orphan a fresh set of blobs on every attempt, and since the store
is content-addressed and append-only, nothing would ever clean them up. `putPdf`,
`putFolder`, and `putDocument` all resolve their ids and timestamps first for this
reason; a test asserts that a conflict-and-retry does not re-upload the document body.


---

### D13. `.rm` page files

**Decision**: parse the `.rm` stroke format. It is undocumented but extensively
reverse-engineered in public, and the page files are already reachable as ordinary
component blobs — so leaving them as opaque bytes would be a gap rather than a boundary.

- **Versions 3 and 5** decode fully — layers, strokes, and per-point x/y/speed/direction/
  width/pressure. v5 differs only by one extra word in the stroke header, which is skipped
  rather than exposed as a guess.
- **Version 6** — what firmware 3.0 and later writes — also decodes to strokes. Its
  payloads use a tagged encoding: each field is introduced by a varint of
  `index shl 4 or tagType`, where `0xF` is a CrdtId (a byte plus a varint), `0xC` a
  length-prefixed subblock, and `0x8`/`0x4`/`0x1` fixed widths. Strokes live in block type
  `0x05` behind a one-byte item-type discriminator; layer names live in block type `0x02`.
  Points are 14 bytes, with speed and width as `uint16` scaled by 4, direction as a byte
  over 360°, and pressure as a byte over 255 — converted on read so `RmPoint` means the
  same thing in every version.

**A deleted stroke has no value subblock at all** — just a non-zero deleted length — and is
dropped rather than drawn. That case is not inferable from the layout; it was found by
decoding real pages and noticing the one block that would not parse.

*Consequences accepted*: `RmFile` is a sealed pair (`Lines` / `Scene`) rather than one type
with half its fields empty. Both expose `layers`, so reading strokes doesn't depend on the
firmware that wrote the page, but `Scene` additionally keeps every raw block, because a v6
page carries text, glyphs, and editing history this library does not model and would
otherwise silently discard. Parsing is strict — a truncated stroke, an over-long block, a
point run that isn't a whole number of points, or trailing bytes all raise
`ValidationException` rather than returning what was read so far, on the same reasoning as
§D4: partial output from a binary format is indistinguishable from correct output.

*Validation*: every `.rm` page in a real account was read and parsed — 564 pages across 173
items, 18 MB, of which **563 parsed**, yielding 38,890 strokes and 969,242 points. Both
paths are covered by real data: 149 pages were version 5 and 414 version 6.

The single failure is not a parser defect. That page contains 19,066 U+FFFD replacement
sequences and decodes cleanly as UTF-8, which a binary file never should: something wrote
it through a lossy UTF-8 round trip before upload. Its stored hash matches the bytes served,
so the damage is at rest on the server, and refusing it is the intended behaviour — the
alternative is emitting strokes assembled from replacement characters.

Seventy-one pages decoded to no layers. Fifty-two carry no stroke blocks at all; the other
nineteen carry only tombstones — 47 of them, every one a deleted stroke, independently
confirmed. Both are correct outcomes rather than silent loss.

**Writing.** `serializeRmFile` inverts the parser, `raw.stageRm` hashes the result, and
`setPages` commits a whole map of them through the sync protocol. Round-tripping an
untouched page reproduces the original bytes exactly, which for a content-addressed store
means the original hash and therefore no upload at all — so writing a page back is only ever
a change when it really is one, and handing `setPages` a map straight from `getPages`
costs exactly the pages that were edited. That property is what lets the typed api take
`RmFile` rather than bytes without rewriting pages the caller never touched. Achieving it
meant keeping two
words per version 3/5 stroke that the reader previously discarded (`reserved`, and the extra
word version 5 added); zeroing them would have quietly rewritten every page that carries
them, and the byte a version 6 block header reserves, which the writer previously assumed
was always zero. A version 6 page is written from its blocks rather than its decoded layers, because
those layers are a view of the blocks and re-encoding them would drop the text, glyphs, and
history this library frames but does not interpret.

`setPages` replaces pages and refuses to invent them. A `.rm` file the `.content`'s
`pages` list does not mention is a file the device will never render, so accepting an unknown
page id would produce a write that appears to succeed and shows nothing — a
`ValidationException` naming the id is the honest answer, and adding a page is
`setDocumentContent`'s business because that is the file which has to change.

**A page exists in the `.content`, not on disk.** The device writes a page's `.rm` only when
something is drawn on it, so a declared page with no `.rm` is a real, empty page rather than a
missing one. Both halves of the api follow the `.content`: `getPage` returns null for such a
page and raises only for an id the document never declared, and a write to one creates the
file, which is how a blank page gets its first strokes. Keying off the `.rm` file instead
would have made an untouched page indistinguishable from a nonexistent one — an error on read
and a refusal on write, both wrong.

*Still unmodelled*: text, glyphs, and the editing history — block types `0x00`, `0x01`,
`0x03`, `0x04`, `0x07`, `0x08`, `0x09`, `0x0A`, and `0x0D` are framed and kept but not
interpreted. The sweep also saw tool 23 and colours 9-12, which are surfaced as raw values
because no public source names them; inventing names would be a guess in the API itself.

---


## 4. API Shape

The full surface is in the generated documentation; deliberately not reproduced here,
because a signature list in prose goes stale the first time the API moves. What follows is
the shape and the reasoning behind it.

**Two tiers, both final classes.** `RemarkableClient` speaks in documents;
`RawRemarkableClient`, reachable as `client.raw`, speaks in hashes and index entries. The
raw tier is genuinely dangerous — a bad root write can orphan an account — so it sits behind
a property rather than mixed into the main surface, and its KDoc says so.

Both are classes with internal constructors, not interfaces over hidden implementations. An
interface was tried and removed. Three reasons: adding a method to a published interface
breaks every implementor, and this api is still moving; an interface with exactly one
internal implementation is the same construct §D10 rejects, aimed at the consumer's tests
instead of ours; and the seam a consumer needs already exists and is better — point the
host options at a local server, as this library's own tests do, and the real protocol code
runs. Mocking the client instead tests the mock's idea of the protocol. Decoration is the
honest cost, and it lands where it belongs: `SessionOptions.httpClient` exposes OkHttp, so
logging, caching, and extra retries are interceptors rather than 31 delegating methods.

**One identity currency, and both halves are load-bearing.** Reads and edits both take an
`ItemRef`, which pairs an `ItemId` with a `FileHash`, and every edit returns a new one. An
edit locates its item by *both*: under schema 3 an index is hashed from its entries' hashes
and not their ids, so two items whose component blobs happen to match hash identically, and
a hash-only lookup would edit whichever the root listed first. Matching on the pair also
means a ref assembled from two different items is rejected rather than silently applied to
one of them. An item's hash changes on every write,
so returning a bare hash would make the caller reassemble the pair by hand — reintroducing
exactly the transposition mistake `ItemRef` exists to prevent. A chain of edits therefore
needs no bookkeeping:

```kotlin
val renamed = api.rename(entry.ref, "new name")
val starred = api.star(renamed, true)
api.move(starred, Parent.Folder(folder.id))
```

**Illegal states are unrepresentable where it is cheap to arrange.** `Parent` is a sealed
type rather than `""`/`"trash"` strings. `DeviceToken` and `SessionToken` are distinct value
classes, so passing the wrong one is a compile error rather than a confusing runtime
rejection. `Zoom` is sealed, so a custom fit carries all six of its numbers or none of them,
instead of an enum sitting beside six independently-optional fields.

**Bulk operations report what they did not do.** `bulkMove` returns a
`BulkResult` with both `moved` and `notFound`. Losing a race with another client is normal,
so a partial result is a normal outcome — but a caller who ignores the return value must not
silently get a partial no-op, which is what returning only a map of successes would allow.

**Options are data classes with defaults, not builders.** Named arguments already give the
readability a builder would, and the immutability is free. `PutOptions` and `SessionOptions`
are large but flat, and every field has a working default.

**Errors are a sealed hierarchy** (§D8), and transport failures are deliberately *not*
wrapped: an `IOException` from OkHttp means the network is down, which is a different
problem with a different handler than the API rejecting a request.

**Timestamps cross the boundary.** `Metadata` keeps the wire's stringified epoch millis
because it round-trips; `Entry` is synthesized for the caller and never written back, so it
exposes `Long`. The conversion happens once, at the point the two meet.

---


## 5. Repo Layout

```
rmapi-kt/
├── build.gradle.kts            # kotlin, serialization, okhttp, detekt, kover,
│                               #   animalsniffer, dokka, maven-publish
├── config/detekt/detekt.yml    # deviations from the default rule set, with reasons
├── jitpack.yml                 # openjdk17, publishToMavenLocal -x test
├── .github/workflows/          # build / cut / release
└── src/main/kotlin/io/hafa/rmapikt/
    ├── Register.kt             # register / auth / session / remarkable, tokens, Hosts,
    │                           #   SessionOptions
    ├── Client.kt               # RemarkableClient and its implementation, PutOptions, Zoom
    ├── Raw.kt                  # RawRemarkableClient, index parse/serialize, staging,
    │                           #   RmFile and parseRmFile  (§D13)
    ├── Entities.kt             # ItemRef, Parent, the Entry hierarchy, ids and hashes
    ├── Content.kt              # the Content hierarchy, Metadata, CPages, wire enums
    ├── Serialization.kt        # Parent/Tags serializers, Content discrimination
    ├── RmV5.kt                 # .rm point/stroke/layer types, the v3/v5 parser  (§D13)
    ├── RmV6.kt                 # RmBlock, the v6 tagged encoding and scene decoding
    ├── Http.kt                 # internal: OkHttp suspend adapter, transient retries
    ├── Cache.kt                # internal: CacheEntry, LruCache, versioned dump/load
    ├── Digest.kt               # internal: sha256, crc32c, hex helpers
    ├── Errors.kt               # the RemarkableException hierarchy
    └── Devices.kt              # DeviceModel / DeviceScreen / deviceScreens
```

`Http.kt`, `Cache.kt`, and `Digest.kt` are entirely internal; the rest carry public
surface. Files are named for the concept they hold rather than for a single top-level
declaration, which is why `MatchingDeclarationName` is off in detekt.

---

## 6. Build & CI

- **Gradle**: Kotlin JVM with `kotlin("plugin.serialization")`, `detekt`, `dokka`,
  `maven-publish`, `org.jetbrains.kotlinx.kover` (**≥ 90% line coverage**, wired into
  `check`), and `animalsniffer` against `gummy-bears` API-21 signatures — the automated
  enforcement behind §D2.
- **Dependencies** (runtime): `kotlinx-serialization-json`, `kotlinx-coroutines-core`,
  `com.squareup.okhttp3:okhttp` (5.x preferred — see below). Test: `kotlin("test")`,
  `kotlinx-coroutines-test`, and **`com.squareup.okhttp3:mockwebserver3` +
  `mockwebserver3-junit5`** — the *primary* test dependency; every network-touching
  test runs against it (§8.2). Note: `mockwebserver3` is the OkHttp 5 package with
  the stabilized API, **not** the feature-frozen legacy `okhttp3.mockwebserver`; this is
  one reason to be on OkHttp 5.x, whose Android floor still permits API 21.
- **Publishing**: JitPack, via `maven-publish` plus `jitpack.yml`. No Maven Central and
  no GPG signing — Central would be the better home for a widely depended-on library, and
  the trade here is deliberate: JitPack costs no account, no staging, and no key
  management, at the price of a less conventional coordinate.
- **CI**: `build.yml` (push/PR: `./gradlew build` on temurin 17 — detekt, tests, kover
  gate, animalsniffer), `cut.yml` (dispatch: version bump, tag, dispatch release),
  `release.yml` (GitHub release from tag). `cut.yml` is the sole writer to `main`: it
  pushes the branch before the tag, so a moved `main` is rejected before a tag exists.
- **Docs**: Dokka HTML, bundled as a `javadoc`-classified jar so JitPack serves it.

---

## 7. Verification

**Unit tests against a local server.** There is no rmfakecloud dependency, no Docker, and
no recorded cassettes. Tests start a `MockWebServer` and point the
`authHost`/`rawHost`/`uploadHost` options at it — the same options that exist so a caller
can use rmfakecloud — so the suite needs no test-only API surface and exercises real OkHttp
over a real socket.

**The local server stores bytes; it does not model the protocol.** An earlier version
reimplemented index serialisation and both hashing rules so tests could assert against its
state. That was a mistake: a second implementation written from the same assumptions agrees
with the first whenever those assumptions are wrong, so a large part of the suite was
checking the library against itself. It also carried shared mutable state, which raced the
client's own `async` fan-out and produced a 25% flake rate whose symptom was a socket
timeout in an unrelated test.

The server now holds a blob table and a root pointer and knows nothing else: what the client
PUTs comes back on GET. Tests assert on **what the client sent** — method, path, headers,
body — which is the contract a wire-protocol client actually has. Where a test needs an
account to already contain something, it is built with the library's own staging functions,
which §7 pins byte-for-byte against a real production index.

**Values that are minted cannot be asserted directly.** There is no clock or id seam, so a
request body containing a fresh uuid or timestamp is checked field by field with the minted
values only checked for well-formedness. This is deliberate: the seam would exist solely
for the tests.

**What has been checked against real data**, which matters for a reverse-engineered format:

- Schema-4 index serialization and hashing reproduce a real 1408-entry production root
  index byte for byte, and compute the hash reMarkable itself stored it under.
- Every `.rm` page in a real account was read and parsed: 564 pages across 173 items,
  563 of which parsed, yielding 38,890 strokes and 969,242 points, across both format
  version 5 and version 6. §D13 covers the one failure, which is a file damaged before
  upload.
- Timestamp fields were surveyed across 1,468 real `.metadata` blobs before `Entry` was
  allowed to expose them as numbers.

**Coverage** is gated at 90% lines by Kover, wired into `check` and therefore into CI.

---

## 8. Testing Notes

Tests assert behaviour, not implementation: what the cloud ends up holding after an
operation, rather than which calls were made in which order. Where a property can be
asserted exactly — a hash's value rather than its length — it is.

Two areas carry more than the rest, because they are where the risk is:

- **Index format.** The entry index is the one place where bytes are the contract, since a
  schema-4 index's own hash is its address. Both schemas' serialization, both hashing
  rules, the sort order, and every malformed-line rejection are pinned explicitly. Go's
  sync15 fixtures were used as an independent cross-check on the same rules.
- **`.rm` parsing.** Fixtures are assembled byte by byte from the format layout rather than
  produced by a writer in this library; a writer would only prove the parser agrees with
  itself. Rejection cases — truncation, over-long blocks, counts that cannot fit — are
  tested because a binary parser that half-succeeds is worse than one that fails.

---


## 9. Scope

**In scope**: registration, auth, and sessions; both client tiers; all wire types; the
cache with dump/prune/clear and an LRU bound; the error taxonomy; the device table;
schema 3 and 4 reads with schema-4 root writes; the ingestion upload endpoint; and `.rm`
stroke parsing and writing (§D13). The only HTTP-related public surface is `SessionOptions.httpClient`
(§D3), and there are no test seams (§D10).

**Out of scope** (not "deferred" — simply not part of this project; §2.2 has the
rationale): CLI/shell, path filetree and globbing, archive/raw-notebook upload,
content-only file replace, automatic token refresh (device tokens don't expire;
session-token refresh is "recreate the client"), request-concurrency limits, `Flow` listing
variants, annotated-PDF export, thumbnails, disk tree cache, sync10
and the dead `/sync/v2/signed-urls/*` + `/sync/v2/sync-complete` endpoints,
websocket/notification events (notification is the `broadcast` flag on the root PUT),
`.rmapi` config files (token persistence is the caller's job), and a blocking/Java facade
(the README points at the `kotlinx.coroutines.future` bridge).
