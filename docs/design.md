# rmapi-kt — Design Document & Plan

A **well-designed Kotlin library for the reMarkable cloud API, informed by rmapi-js**.
[rmapi-js](https://github.com/erikbrinkman/rmapi-js) (v11.1.2) is the authority on the
**wire protocol, the feature set, and the scope** — but not on API aesthetics: where its
shape is an artifact of TypeScript, zod, Promises, or browser constraints, rmapi-kt is
designed the way a Kotlin library should be, and every such divergence is recorded with
its rationale (§D7 is the centerpiece). The Go [rmapi](https://github.com/ddvk/rmapi)
serves only as an independent wire-protocol cross-check plus a source of index/hashing
test vectors. Build, CI, and house style follow
[mideakt](https://github.com/hafaio/mideakt).

This is a design/plan only. All Kotlin below is illustrative signature sketching, not
production code.

---

## 1. Summary

- **What**: `hafaio/rmapi-kt`, a single-module Kotlin/JVM **library** (no CLI), group
  `io.hafa`, package `io.hafa.rmapikt`, published via JitPack. Plain Kotlin/JVM that is
  deliberately **Android-safe (minSdk 21, no desugaring)** — no Android module, no AGP.
- **Feature set**: exactly rmapi-js 11.1.2 — registration/auth/session, the high-level
  client (list, get, create/upload, update, move/rename/star/delete, bulk ops, cache
  management) and the low-level raw client (root hash + generation, blob get/put, entry
  indexes, schema 3+4 read / schema-4 root write, the simple `/doc/v2/files` upload).
- **Design posture**: Kotlin-native where rmapi-js is JS-shaped. Highlights: `suspend`
  API with structured concurrency; sealed `Entry`/`Content` hierarchies with a sealed
  `Tags` type replacing the four legacy/modern content-class duplicates; value classes
  for hashes/ids and an `ItemRef(id, hash)` pair that replaces every `(id, hash)`
  two-argument hazard; a sealed `Parent` (Root/Trash/Folder) replacing `""`/`"trash"`
  magic strings; the per-call `refresh: Boolean` threading replaced by one explicit
  `refreshRoot()`; the `put*` (sync-protocol construction) vs `upload*` (server-side
  ingestion) families kept under rmapi-js's names with the mechanism difference made
  unmissable in KDoc; a redesigned error taxonomy; a Kotlin-native cache with a versioned
  dump format (rmapi-js byte compatibility deliberately dropped); passthrough
  serializers that round-trip unknown JSON keys (the zod-boundary semantics, kept
  because they're *load-bearing*, not because zod has them).
- **Dependencies**: `kotlinx-serialization-json`, `kotlinx-coroutines-core`, and
  **OkHttp** (settled), used directly — the library's only injectable is an
  `OkHttpClient` in `SessionOptions` (timeouts/interceptors/proxies, the established
  JVM pattern; §D3). No transport abstraction, no test seams of any kind. SHA-256
  (`MessageDigest`), zip (`java.util.zip`), and UUIDs (`java.util.UUID`) come from
  the platform; CRC32C is a ~30-line internal table implementation
  (`java.util.zip.CRC32C` needs Android 34). The JS library's `crc-32`, `jszip`, and
  `uuid` dependencies disappear.
- **Build/CI**: mideakt's setup — Gradle Kotlin DSL, Kotlin 2.2.x on JVM 17, detekt
  (default rules + public-API KDoc gate), Dokka javadoc jar, JitPack, `build`/`cut`/
  `release` workflows — plus a 90% Kover line-coverage gate and an animalsniffer check
  against Android API-21 signatures.
- **Verification**: unit tests only — against a local **MockWebServer**
  (`mockwebserver3`), reached through the `authHost`/`rawHost`/`uploadHost` options
  that already exist for rmfakecloud, so testing requires *zero* test-only API
  surface. Independent verification is a fresh agent session auditing **intent
  parity** (every rmapi-js capability reachable, every divergence justified in §D7)
  and test fidelity (each of the 46 JS test cases' assertion intent covered), §7.
- **Tests**: all 46 rmapi-js test cases (45 `index.spec.ts` + 1 `lru.spec.ts`) ported
  **by behavior** onto kotlin.test/JUnit 5, with golden fixtures (index bodies, hashes)
  checked in; Go's sync15 index/hashing vectors ported as independent cross-checks.

---

## 2. Prior Art

### 2.1 rmapi-js (protocol, feature set, and scope authority)

Source of truth: `/Users/erk/projects/rmapi-js/src/` — `index.ts` (high-level API),
`raw.ts` (low-level API + all zod schemas), `error.ts`, `lru.ts`, `devices.ts`,
`utils.ts`; tests in `index.spec.ts`, `lru.spec.ts`, `test-utils.ts`.

**Wire model.** The cloud is an immutable content-addressed store: every file is stored
under the SHA-256 of its contents at `GET/PUT {rawHost}/sync/v3/files/{hash}` (with an
`rm-filename` header naming the logical file, and `x-goog-hash: crc32c=<b64>` on
uploads). An *item* (document/collection/template) is an **entry index** — a text file
of `hash:type:id:subfiles:size` lines (schema 3) or with a `4\n0:<id>:<count>:<size>`
header line (schema 4). The whole account is a root index of item indexes. The root
hash plus a monotonically increasing **generation** live at
`GET {rawHost}/sync/v4/root` / `PUT {rawHost}/sync/v3/root`; a PUT with a stale
generation fails with `{"message":"precondition failed"}`. Schema 3 hashes an index as
SHA-256 of the concatenated raw entry hashes; schema 4 hashes the index file bytes like
any other file. Newly written **root** indexes must always be schema 4 (the cloud 400s
"Software must be updated" on schema-3 roots); document indexes keep the account's
schema version.

**Auth.** `POST {authHost}/token/json/2/device/new` with the 8-letter one-time code +
`deviceDesc` + `deviceID` uuid → long-lived device token (raw text, not JSON);
`POST {authHost}/token/json/2/user/new` with `Authorization: Bearer <deviceToken>` →
short-lived session token used as bearer everywhere else. Hosts: auth
`https://webapp-prod.cloud.remarkable.engineering`, raw
`https://eu.tectonic.remarkable.com`, upload `https://internal.cloud.remarkable.com`.

**Simple upload API.** `POST {uploadHost}/doc/v2/files` with mime
`application/pdf` / `application/epub+zip` / `folder`, `rm-meta` (base64 JSON
`{file_name}`), `rm-source: RoR-Browser` → `{docID, hash}`. Schema-agnostic server-side
ingestion, used by the native browser extension.

**Feature set to cover** (the parity contract): module functions `register`, `auth`,
`session`, `remarkable`; high-level `listItems`, `listIds`, `getContent`,
`getMetadata`, `getPdf`, `getEpub`, `getDocument`, `putPdf`, `putEpub`, `putFolder`,
`uploadEpub`, `uploadPdf`, `uploadFolder`, `updateDocument`, `updateCollection`,
`updateTemplate`, `move`, `delete`, `rename`, `stared` (sic), `bulkMove`, `bulkDelete`,
`dumpCache`, `pruneCache`, `clearCache`; the raw API (`getRootHash`, `getHash`,
`getText`, `getEntries`, `getContent`, `getMetadata`, `putRootHash`, `putFile`,
`putText`, `putContent`, `putMetadata`, `putEntries`, `uploadFile`, cache ops); the
wire types (`Entry` union, `SimpleEntry`, `RawEntry`, `Entries`, the `Content` union
with legacy string-tag variants, `Metadata`, tags, cPages, options types,
`deviceScreens`); errors (`GenerationError`, `ResponseError`, `ValidationError`,
`HashNotFoundError`, bare `Error`s for missing components, zod errors).

**Load-bearing behaviors to preserve** (protocol/semantics, not aesthetics):

- High-level mutators are read-modify-write over the raw API: fetch root index → find
  entry *by hash* (error if absent) → rewrite the one file → rebuild the item index →
  rebuild the root index (always schema 4) → PUT root hash with the cached generation.
  A generation conflict invalidates the client's cached root/generation so the next
  call refetches. Retry policy belongs to the caller.
- `delete` = move to trash; `bulkDelete` = bulk move to trash — no hard delete.
- zod schemas are `.passthrough()` (unknown keys preserved) except collection and
  template content (`.strict()`). Passthrough is load-bearing: updates re-serialize
  fetched payloads, so dropping unknown keys would destroy device data.
- The `putFile`-family split — entry (hash precomputed locally) usable immediately,
  upload completing concurrently, root hash PUT only after all uploads settle.
- Root/generation and schema version cached per client instance.
- Uploads dedupe through the cache: a known hash is never re-PUT.
- `putPdf`/`putEpub` fabricate content with `pageCount: 1`, one fake page uuid, and
  `sizeInBytes`, which the cloud expects for never-opened docs.
- Parallel fan-out (`Promise.all`) for entry resolution and component uploads,
  **unbounded** — kept unbounded in Kotlin (settled).

**Design artifacts NOT to copy** (JS/zod/browser-shaped; redesigned in §3): the
`(id, hash)` two-argument pairing on every getter; `refresh: Boolean` threaded through
every method (and duplicated inside `PutOptions`); four
`Content` classes duplicated solely because legacy tags are `string[]`; `HashEntry`/
`HashesEntry` single-field wrappers; `getHash` naming; the `stared` typo; a cache dump
that is a raw JSON map with `null` meaning "binary blob known to exist"; errors
carrying a `RegExp`; `getDocument` returning an in-memory zip (a browser-ism — JS has
no stdlib zip, so JSZip and a single blob was the pragmatic shape there).

### 2.2 Go rmapi — wire-protocol cross-check only

Studied from a shallow clone of ddvk/rmapi @ 434da60d (scratchpad `rmapi-go/`; the
juruen original was archived 2023-12). **Role in this design: none as an API model.**
Its shell-oriented surface (path resolution, globbing, REPL, config files) is not an
API style this library wants. Go rmapi contributes exactly two things:

1. **Independent confirmation of the reverse-engineered wire protocol**: same auth
   endpoints with raw-text token responses; `GET /sync/v4/root` / `PUT /sync/v3/root`
   with `{broadcast, hash, generation}` and HTTP 412 → wrong-generation; blob
   `GET/PUT /sync/v3/files/{hash}` with `rm-filename`; `x-goog-hash: crc32c=<base64>`
   on uploads; identical hash rules (file = sha256(bytes); schema-3 index = sha256 of
   concatenated binary entry hashes sorted by id; schema-4 root = sha256 of the index
   bytes); roots always schema 4 (server rejects new v3 roots — regression-tested);
   document indexes named `<uuid>.docSchema`. It also confirms the dead ends: sync10
   and `/sync/v2/signed-urls/*` were deleted from the fork in 2024, and
   `POST /sync/v2/sync-complete` is a no-op stub — device notification is purely the
   `broadcast: true` flag on the root PUT.
2. **Independent test vectors** for the sync15 index/hashing formats
   (`api/sync15/tree_test.go`), ported in §8.4 precisely because they come from a
   different implementation.

Minor wire divergences, resolved in rmapi-js's favor (both work against the real
cloud): Go sends `rm-filename: roothash` on the root PUT and
`content-type: application/octet-stream` on blob PUTs, js sends neither; Go sorts index
entries by plain byte order where js uses `localeCompare` (identical for uuid ids —
corroborating the plain-`compareTo` choice, settled).

Go's other features (commit-retry loop, filetree/globs, disk tree cache, archive
upload, content-only replace, annotations/`.rm` codec, thumbnails, `.rmapi` config,
CLI) are **out of scope, not deferred** — rmapi-js defines the feature set, and some of
these (glob addressing in a library API) are design this project explicitly rejects.

### 2.3 mideakt (house conventions)

`/Users/erk/projects/mideakt`: single Gradle module, group `io.hafa`, Kotlin 2.2.20,
JVM 17, `withSourcesJar`, kotlinx-serialization-json as the only runtime dep,
`kotlin("test")` on the JUnit Platform, detekt 1.23.x (`buildUponDefaultConfig`,
public-API KDoc gate on, `MagicNumber`/`MatchingDeclarationName` off), Dokka 2.0 HTML
as a `javadoc`-classified jar, `maven-publish` + JitPack (`jitpack.yml`: openjdk17,
`publishToMavenLocal -x test`), MIT, files named by concept, golden-vector
cross-validation against the canonical reference implementation, and `build`/`cut`/
`release` workflows. rmapi-kt copies all of this.

---

## 3. Design Decisions

Each decision: what, why, rejected alternatives. §D7 collects the full
rmapi-js-vs-rmapi-kt divergence table.

### D1. Naming, group, module layout *(settled)*

**Decision**: repo `hafaio/rmapi-kt`, single Gradle module, group `io.hafa`, artifact
`rmapi-kt`, package `io.hafa.rmapikt`. Library only — no CLI module.

- Matches mideakt: JitPack coordinates `com.github.hafaio:rmapi-kt`; package = artifact
  = repo basename (as `io.hafa.mideakt`).
- Repo settings per `/Users/erk/projects/CLAUDE.md`: squash-only merges,
  delete-branch-on-merge, issues on, wiki off; topics `remarkable`,
  `remarkable-tablet`, `kotlin`, `jvm`, `coroutines`, `api-client`, `android-library`;
  homepage → `https://jitpack.io/#hafaio/rmapi-kt`; description: "Kotlin/JVM client for
  the reMarkable cloud API, informed by rmapi-js. Android-compatible."
- *Rejected*: `core`/`cli` multi-module (settled out of scope), package `io.hafa.rmapi`
  (breaks the package = artifact convention).

### D2. Kotlin/JVM targets and the Android floor *(settled)*

**Decision**: Kotlin 2.2.x, **JVM target 17** matching mideakt verbatim, plus an
API-usage discipline keeping the library **Android-safe at minSdk 21 with no
desugaring**, enforced by animalsniffer against `gummy-bears` API-21 signatures in CI:

- JVM *bytecode* 17 is fine on Android (AGP 8+/D8 consume class-file 61); the
  constraint is *API* availability, so: JDK APIs must exist on API 21.
- Per-API audit:
  - `java.time` — **not used** (the wire wants epoch-millis strings; the internal
    clock is `nowMillis(): Long`, §D10). This alone would have forced API 26.
  - HTTP — OkHttp (§D3, settled), whose 4.x line supports API 21; that sets the
    Android floor. `java.net.http.HttpClient` is Android 34+ and unusable.
  - `java.util.zip.CRC32C` — Android 34+; replaced by a ~30-line internal
    table-driven Castagnoli implementation (`Digest.kt`), pinned by RFC 3720 vectors
    and cross-checked against the JS `crc-32/crc32c` package's outputs.
  - `java.util.Base64` — Android 26+; use Kotlin stdlib `kotlin.io.encoding.Base64`
    (stable in 2.2) for `x-goog-hash` and `rm-meta`.
  - `java.util.HexFormat` — JDK 17-only, absent on Android; tiny internal
    `toHex`/`hexToBytes`, as in mideakt.
  - Logging — none; the schema-3-root warning (JS `console.warn`) is
    `System.err.println` (logcat-visible, zero deps, zero API surface).
  - Safe and used freely: `MessageDigest`, `java.util.zip.Zip{Input,Output}Stream`
    (API 1), `java.util.UUID`, `java.util.concurrent`, `Charsets.UTF_8`.
  - No file I/O anywhere (cache dump is a `String`; token persistence is the
    caller's job) — no `java.nio.file` concerns.
  - kotlinx-coroutines and kotlinx-serialization both run at API 21.
- *Rejected*: JVM target 8/11 "for Android" (bytecode level isn't the constraint),
  JVM 21, Kotlin Multiplatform (large build cost; the JS side of the fence already
  exists), an AGP/Android module (settled: plain JVM that happens to be Android-safe).

### D3. HTTP: OkHttp used directly, configurable via an `OkHttpClient` option *(settled)*

**Decision**: rmapi-kt uses **OkHttp** directly and internally — no transport
abstraction, no HTTP types of its own. The library's only HTTP-related public surface
is one option:

```kotlin
public data class SessionOptions(
    val rawHost: String = Hosts.RAW,
    val uploadHost: String = Hosts.UPLOAD,
    val cache: String? = null,                 // a previous dumpCache() (§D9)
    val maxCacheSize: Long = Long.MAX_VALUE,   // bytes
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
  is acceptable: the engine choice is settled, OkHttp is the JVM/Android standard
  with a decades-long track record, and this exact coupling is what Retrofit et al.
  have shipped successfully for years.
- **Why OkHttp** (settled): the Android-standard, battle-tested HTTP client; its 4.x
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
  options already exist (for rmfakecloud); tests point them at a local MockWebServer.
  rmapi-js needs a monkey-patched global (`spyOn(globalThis, "fetch")`) to test;
  rmapi-kt needs nothing beyond options it has anyway. This is the core reason the
  seams were unnecessary.
- *Rejected*: **a custom `HttpTransport` abstraction** (previous draft — test-only
  customer, see above), **`java.net.http.HttpClient`** (Android 34+ only), **Ktor
  client** (a framework layer — plugin system, config DSL, several artifacts — over
  the same engine class of work), **`HttpURLConnection` zero-dependency default**
  (viable but settled against: OkHttp is worth its weight — connection pooling,
  HTTP/2, TLS handling — "okhttp is great").

### D4. JSON: kotlinx-serialization with passthrough; the Content family redesigned

**Decision**: `kotlinx-serialization-json` (house JSON library). Two sub-decisions:

**(a) Passthrough is kept — it's load-bearing.** Updates are read-modify-write of
`.content`/`.metadata` files, so unknown keys (cPages internals, future firmware
fields) must survive a decode→modify→encode round trip. Plain `ignoreUnknownKeys`
would silently destroy device data. Every passthrough wire type is a `data class` of
typed known fields plus a trailing `val extra: JsonObject = JsonObject(emptyMap())`;
a shared internal `PassthroughSerializer<T>` (~40 lines) splits unknown keys out on
decode and merges them back on encode. Types that are strict in the wire schema
(collection content, template content) reject unknown keys and have no `extra`. This
keeps zod's *semantics* because the cloud demands them — the *mechanism* is idiomatic
kotlinx-serialization.

**(b) The legacy-tags duplication is collapsed with a sealed `Tags` type.** rmapi-js
has `CollectionContent`/`LegacyCollectionContent` and `DocumentContent`/
`LegacyDocumentContent` — four classes whose only difference is whether `tags` is
`Tag[]` or `string[]` (a zod-union artifact). rmapi-kt models the varying *field*
honestly:

```kotlin
public sealed interface Tags {
    public data class Structured(val tags: List<Tag>) : Tags
    public data class Legacy(val names: List<String>) : Tags   // pre-structured-tag firmware
    public val names: List<String>                              // uniform read access
}
```

so `Content` is exactly three types — `CollectionContent`, `DocumentContent`,
`TemplateContent` — and a legacy payload round-trips byte-faithfully (a `Legacy` value
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
`coroutineScope { async { … } }` for the parallelism rmapi-js gets from `Promise.all` —
entry resolution in `listItems`, component uploads in `putPdf` — **unbounded,
exactly like rmapi-js** (settled; no semaphores, no knobs). No dispatcher is
hard-coded; OkHttp's `enqueue` runs on its own executor and the suspend adapter just
resumes the caller, so the API is safe from any context, including Android's main
thread. Generation conflicts throw `GenerationException` and invalidate the client's
cached root — retry policy is the caller's (settled; no retry helper).

The one structural remodel: rmapi-js's `putFile` returns `[RawEntry, Promise<void>]` —
a value plus a *hot* running promise. In Kotlin that would be a `Deferred` with no
owner, a structured-concurrency smell. rmapi-kt splits it into **stage + upload**:
`stageFile(...)` is pure (hash computed locally; returns the entry plus the bytes to
send) and `suspend fun upload(staged)` performs the PUT. High-level ops stage
everything, use the entries immediately, and `awaitAll` uploads inside their own
`coroutineScope` — same concurrency, honest ownership, and no raw-API method needs a
`CoroutineScope` parameter.

No blocking/Java facade in v1 — Java callers bridge with
`kotlinx.coroutines.future.future {}` (README shows it). *Rejected*: `Flow` variants
(settled out), callback/`CompletableFuture` surface, exposing hot `Deferred`s.

### D6. Type modeling

**Decision** (full signatures in §4):

- `@JvmInline value class FileHash(val hex: String)` (validated 64-hex) and
  `@JvmInline value class ItemId(val value: String)` — the two strings that must never
  be confused for each other.
- **`ItemRef(id, hash)`** — rmapi-js's `SimpleEntry`, renamed to say what it is: a
  reference to one item at one state. More importantly it becomes **the parameter** of
  every getter: `getContent(ref)`, not `getContent(id, hash)`. The JS two-argument
  pairing is the API's most likely caller bug (transposed arguments) and an artifact
  of positional JS style; the ref type eliminates it. `Entry.ref` exposes it from
  listings.
- `sealed interface Entry` with `CollectionEntry`, `DocumentEntry`, `TemplateEntry`
  data classes (the JS names `DocumentType`/`TemplateType` collide with the
  `metadata.type` wire strings; the wire strings themselves stay `"DocumentType"`
  etc.).
- `sealed interface Parent { Root, Trash, Folder(id) }` replacing the `""`/`"trash"`
  magic strings (settled — reviewed and kept). Wire mapping lives in one serializer.
- All wire data classes are immutable `val`-only with `copy()` as the update mechanism
  (the frozen-dataclass preference). `ByteArray` never sits in a data class.
- Enums for closed wire vocabularies: `SchemaVersion(V3, V4)`, `FileType`,
  `Orientation`, `TextAlignment` (`""` → `Default`), `ZoomMode`, `BackgroundFilter`,
  `UploadKind` (pdf/epub/folder mimes), `EntryType`, `DocumentComponent`.
- Timestamps stay `String` (the wire's stringified epoch-millis, with quirks like
  `"0"`) — parsing to a time type would be lossy and push `java.time` onto the API.
- `deviceScreens`/`DeviceModel`/`DeviceScreen` port directly.

### D7. The divergence table (design decisions, not apologies)

The centerpiece: everywhere rmapi-kt's surface differs from rmapi-js, with the reason.
Capability coverage is 1:1 (§7's audit enforces it); shape is Kotlin's.

| rmapi-js | rmapi-kt | why the Kotlin design is different |
|---|---|---|
| `(id, hash)` argument pairs on every getter | `ItemRef` parameter (`getContent(ref)`) | kills the transposition hazard; the pair *is* a domain concept, so it gets a type |
| `refresh?: boolean` on ~10 methods, plus `PutOptions.refresh` | one explicit `suspend fun refreshRoot(): RootInfo`; no per-call flags | the flag controls one thing — trust in the cached root. One named operation beats a boolean threaded through every signature (the JS duplication inside `PutOptions` shows where that leads). Default behavior is identical (JS defaults to `false` everywhere) |
| `put*` vs `upload*` families | **names kept deliberately** (settled), distinction carried by KDoc | `put` is accurate — the document already exists and is being put into the cloud; a `create*` rename was considered and rejected on those grounds. The real problem (two similar name families, two very different mechanisms) is solved by documentation: both families' KDoc states it unmissably — `put*` = the client builds the component files and commits them through the sync protocol (full `PutOptions`, generation-sensitive, can throw `GenerationException`); `upload*` = hand the file to the server's simple ingestion endpoint (`/doc/v2/files`; no options, no generation involvement) — each cross-referencing the other |
| `PutOptions` | `PutOptions` minus the `refresh` field | name kept with the `put*` family; stays an immutable defaulted data class (20+ fields make function default-args unwieldy); `refresh` concern moved to `refreshRoot()` |
| `Partial<DocumentContent>` update payloads | update lambda: `updateDocument(hash) { it.copy(textAlignment = …) }` | Kotlin has no `Partial`; a `copy()` lambda over the fetched content is type-safe and states the read-modify-write honestly |
| `stared(hash, stared)` | `setStarred(hash, starred)` (settled) | typo fixed; wire field stays `pinned`; KDoc cross-references the JS name |
| returns `HashEntry {hash}` / `HashesEntry {hashes}` | returns `FileHash` / `Map<FileHash, FileHash>` | single-field wrapper interfaces are a TS-ism; the value class already names the thing |
| `getDocument(): Uint8Array` (zip built with JSZip) | `getDocumentFiles(ref): Map<String, ByteArray>` (fileName → bytes) | the zip was a browser-ism (JS has no stdlib zip; one blob was the usable shape there). The honest result is the component files; a caller who wants a zip has `java.util.zip` one import away. Drops the JSZip-equivalent dependency entirely |
| `getHash(fileName, hash)` | `getBlob(fileName, hash)` | "get hash" reads as computing a hash; `getBlob` says what it returns |
| `listIds(): SimpleEntry[]` | `listRefs(): List<ItemRef>` | it never returned ids; it returns id+hash refs — the name now says so |
| `Entry = CollectionEntry \| DocumentType \| TemplateType` | sealed `Entry` with `*Entry` names | union → sealed interface; names de-collided from the wire's `metadata.type` strings |
| 4 content classes (legacy tag variants) | 3 content classes + sealed `Tags` | §D4(b): model the varying *field*, not four class copies |
| `""` / `"trash"` parent strings | sealed `Parent` (Root/Trash/Folder) | magic strings → types (settled, reviewed, kept) |
| errors: 4 classes + zod errors + bare `Error`s | designed sealed taxonomy (§D8) | JS mixes typed errors, zod leaks, and anonymous `Error`s; Kotlin callers get one sealed hierarchy to branch on |
| cache dump = raw JSON map, `null` = "exists" | versioned Kotlin-native dump (§D9) | settled: no byte compatibility; the `null` encoding and UTF-16 sizing were JS-isms |
| `[RawEntry, Promise<void>]` | stage + upload (§D5) | hot promise → structured concurrency |
| tests require monkey-patching the global `fetch` | tests require nothing — host options + MockWebServer (§D3, §8) | the hosts are already overridable (rmfakecloud); pointing them at a local server needs zero test-only surface, where JS needs `spyOn(globalThis, "fetch")` |
| no HTTP configurability (global `fetch` only) | `SessionOptions.httpClient: OkHttpClient` | timeouts/interceptors/proxies via the established JVM pattern (Retrofit's `.client(...)`); cost: OkHttp is part of the API contract (§D3) |
| `Uint8Array` | `ByteArray` | direct analog |
| options objects | defaulted data classes / default args | Kotlin idiom; small option sets become default args (`register`), large ones stay data classes (`PutOptions`) |

Everything else maps by name and behavior: `register`, `auth`, `session`,
`remarkable`, `listItems`, `getContent`, `getMetadata`, `getPdf`, `getEpub`, `move`,
`delete`, `rename`, `bulkMove`, `bulkDelete`, `dumpCache`, `pruneCache`, `clearCache`,
and the raw client (modulo stage/upload and `getBlob`).

### D8. Error taxonomy — designed for the caller, not mapped from JS

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

/** a payload didn't match the reverse-engineered schema; [rawJson] is the escape hatch */
public class ValidationException(
    message: String,
    /** the raw text that failed validation, for callers who want to parse it themselves */
    public val rawJson: String? = null,
    cause: Throwable? = null,
) : RemarkableException(message, cause)

/** the hash you passed isn't an entry of the current root index */
public class HashNotFoundException(public val hash: FileHash) :
    RemarkableException("'${hash.hex}' not found in the root index")

/** the item exists but lacks the requested component (e.g. no .epub on a pdf doc) */
public class ComponentNotFoundException(
    public val ref: ItemRef,
    public val component: DocumentComponent,   // Content | Metadata | Pdf | Epub
) : RemarkableException("no $component in item ${ref.id.value}")
```

Design choices and their reasons:

- **`GenerationException` carries `staleGeneration`** — a retry/debug loop wants to
  know what it sent; the server doesn't report the new generation on conflict, so
  nothing more is pretended.
- **`ResponseException` does not opine on retryability** (settled — "use the simple
  thing"): callers have `status` and can draw their own retryable line; a library
  judgment (429/5xx?) was considered and dropped as speculative policy.
- **`ValidationException` drops JS's `field`/`regex` payload** (a `RegExp` inside an
  error is a zod-adjacent artifact nobody branches on) and instead carries
  **`rawJson`** — operationalizing rmapi-js's own documented workaround ("if
  validation fails, use the low-level api and parse the raw text yourself") into the
  exception itself. All schema failures land here (wrapping
  `SerializationException`), where JS leaks both `ValidationError` and raw
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

### D9. Caching — Kotlin-native (rmapi-js byte compatibility dropped; settled)

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

- **Honest encoding**: rmapi-js's `Map<string, string | null>` with `null` meaning
  "exists" is a JS-ism; the sealed type says what each entry means and makes the
  "never re-upload a known hash" path read as intent.
- **Honest size unit**: `maxCacheSize` is measured in **bytes** (UTF-8 length of
  cached text plus a fixed per-entry key overhead). The JS UTF-16-code-unit measure
  existed only to match `String.length`; bytes are what someone bounding memory means.
- **Dump format**: versioned, kotlinx-serialized JSON —
  `{"version": 1, "text": {"<hash>": "…"}, "exists": ["<hash>", …]}` — produced by
  `dumpCache(): String`, accepted by `SessionOptions.cache`. Round-trip guaranteed by
  test; the version field makes any future format change detectable instead of
  silently corrupting (`session` rejects unknown versions with a clear error — the
  behavior JS has for malformed caches). JSON over an opaque binary format because the
  dump's second job is debuggability — being able to *read* what the client knows.
  **No rmapi-js interchange** (settled): a cache is a performance artifact; migrating
  users start cold and lose nothing but a few refetches.
- **LRU**: same design as `lru.ts` because it's *right*, not because it's there —
  insertion-order eviction with get-refresh over `LinkedHashMap(accessOrder = true)`,
  size-accounted in bytes, `maxCacheSize = Long.MAX_VALUE` default (unbounded).
- **Thread safety**: all cache access in `synchronized(lock)` blocks — critical
  sections are pure map ops (no suspension, no I/O), so a monitor beats a suspending
  `Mutex`. The benign JS race (two concurrent `getText`s of one hash fetch twice, then
  converge) is kept; single-flighting is complexity without a correctness win.
- `pruneCache` keeps the BFS-from-root reachability sweep; `clearCache` empties.
- *Rejected*: rmapi-js dump compatibility (settled — it would freeze the `null`
  encoding forever), Caffeine (a dependency for ~60 lines), entry-count sizing
  (entries range from 40 bytes to megabytes; count bounds nothing anyone cares
  about), TTLs (content-addressed data cannot go stale).

### D10. No test seams at all *(settled)*

**Decision**: the client calls `System.currentTimeMillis()` and
`UUID.randomUUID()` **directly**. There is no clock/id abstraction, no internal
secondary constructor, no injectable environment — earlier drafts had one, and the
user's question ("is there a better way in Kotlin that doesn't require an environment
seam?") exposed that, like the transport seam, its only customer was tests.

- How tests cope without it (§8.2): the golden-hash tests drive `stageEntries` with
  explicit caller-supplied entries — exactly as rmapi-js's own tests do — so nothing
  is minted on those paths. The few request bodies that genuinely contain minted
  values (`putPdf`/`putEpub`'s `.content`/`.metadata`) are asserted **structurally**
  (decode, assert every field, check the minted uuid/timestamp for well-formedness)
  rather than byte-exactly — a deliberate, slightly weaker assertion that is the
  price of the deletion.
- Epoch millis (`System.currentTimeMillis()`), not `java.time`: the wire wants
  epoch-millis strings, and `java.time.Clock` would push the Android floor to API 26
  (§D2).
- The only publicly controllable identity input remains `register`'s `uuid`
  parameter — rmapi-js parity and a genuine user need (stable device identity), not a
  seam.
- *Rejected*: the internal `Environment` interface (previous draft — test-only
  customer), public clock/id options (test residue in the API), mocking frameworks
  that rewrite finals (heavyweight, fragile).

### D11. Hosts

**Decision**: the three rmapi-js host defaults (`Hosts.AUTH`, `Hosts.RAW`,
`Hosts.UPLOAD`), overridable in options — which is also the rmfakecloud story (point
all three at the fake). No config-file handling; token persistence is the caller's
concern, exactly as in rmapi-js (settled).

---

## 4. Full Proposed API Surface

Illustrative signatures — KDoc omitted, bodies elided. Package `io.hafa.rmapikt`.

```kotlin
// ---------- construction ----------

public suspend fun register(
    code: String,
    deviceDesc: DeviceDescription = DeviceDescription.BrowserChrome,
    uuid: String? = null,                      // random when null; stable device identity
    authHost: String = Hosts.AUTH,
): String                                      // long-lived device token

public suspend fun auth(deviceToken: String, authHost: String = Hosts.AUTH): String

public fun session(sessionToken: String, options: SessionOptions = SessionOptions()): RemarkableClient

public suspend fun remarkable(
    deviceToken: String,
    authHost: String = Hosts.AUTH,
    options: SessionOptions = SessionOptions(),
): RemarkableClient

public data class SessionOptions(
    val rawHost: String = Hosts.RAW,           // https://eu.tectonic.remarkable.com
    val uploadHost: String = Hosts.UPLOAD,     // https://internal.cloud.remarkable.com
    val cache: String? = null,                 // a previous dumpCache() (versioned format, §D9)
    val maxCacheSize: Long = Long.MAX_VALUE,   // bytes
    val httpClient: OkHttpClient = OkHttpClient(),   // timeouts/interceptors/proxy (§D3)
)

// ---------- identifiers ----------

@JvmInline public value class FileHash(val hex: String)    // init { require 64 lowercase hex }
@JvmInline public value class ItemId(val value: String)

/** a reference to one item at one state — the argument of every getter */
public data class ItemRef(val id: ItemId, val hash: FileHash)

public sealed interface Parent {
    public data object Root : Parent
    public data object Trash : Parent
    public data class Folder(val id: ItemId) : Parent
}

// ---------- entries ----------

public sealed interface Entry {
    public val id: ItemId
    public val hash: FileHash
    public val visibleName: String
    public val lastModified: String
    public val pinned: Boolean
    public val parent: Parent?
    public val ref: ItemRef get() = ItemRef(id, hash)
}

public data class CollectionEntry(
    override val id: ItemId, override val hash: FileHash, override val visibleName: String,
    override val lastModified: String, override val pinned: Boolean, override val parent: Parent?,
    val tags: Tags = Tags.Structured(emptyList()),
) : Entry

public data class DocumentEntry(
    override val id: ItemId, override val hash: FileHash, override val visibleName: String,
    override val lastModified: String, override val pinned: Boolean, override val parent: Parent?,
    val fileType: FileType, val lastOpened: String, val tags: Tags = Tags.Structured(emptyList()),
) : Entry

public data class TemplateEntry(
    override val id: ItemId, override val hash: FileHash, override val visibleName: String,
    override val lastModified: String, override val pinned: Boolean, override val parent: Parent?,
    val createdTime: String?, val source: String?, val new: Boolean?,
) : Entry

// ---------- high-level client ----------

public interface RemarkableClient {
    public val raw: RawRemarkableClient

    /** refetch the root hash/generation, discarding the cached view (replaces JS's per-call refresh flags) */
    public suspend fun refreshRoot(): RootInfo

    public suspend fun listItems(): List<Entry>
    public suspend fun listRefs(): List<ItemRef>

    public suspend fun getContent(ref: ItemRef): Content
    public suspend fun getMetadata(ref: ItemRef): Metadata
    public suspend fun getPdf(ref: ItemRef): ByteArray
    public suspend fun getEpub(ref: ItemRef): ByteArray
    /** every component file of the item, by logical file name */
    public suspend fun getDocumentFiles(ref: ItemRef): Map<String, ByteArray>

    // put* — the client builds the component files and commits them through the sync
    // protocol: full PutOptions control, generation-sensitive (may throw
    // GenerationException). KDoc on each cross-references its upload* counterpart.
    public suspend fun putPdf(visibleName: String, pdf: ByteArray, options: PutOptions = PutOptions()): ItemRef
    public suspend fun putEpub(visibleName: String, epub: ByteArray, options: PutOptions = PutOptions()): ItemRef
    public suspend fun putFolder(visibleName: String, parent: Parent = Parent.Root): ItemRef

    // upload* — hand the file to the server's simple ingestion endpoint
    // (/doc/v2/files): the server does the work; no options, no generation
    // involvement. KDoc on each cross-references its put* counterpart.
    public suspend fun uploadPdf(visibleName: String, pdf: ByteArray): ItemRef
    public suspend fun uploadEpub(visibleName: String, epub: ByteArray): ItemRef
    public suspend fun uploadFolder(visibleName: String): ItemRef

    public suspend fun updateDocument(hash: FileHash, update: (DocumentContent) -> DocumentContent): FileHash
    public suspend fun updateCollection(hash: FileHash, update: (CollectionContent) -> CollectionContent): FileHash
    public suspend fun updateTemplate(hash: FileHash, update: (TemplateContent) -> TemplateContent): FileHash

    public suspend fun move(hash: FileHash, parent: Parent): FileHash
    public suspend fun delete(hash: FileHash): FileHash                       // move to Trash
    public suspend fun rename(hash: FileHash, visibleName: String): FileHash
    public suspend fun setStarred(hash: FileHash, starred: Boolean): FileHash

    public suspend fun bulkMove(hashes: Collection<FileHash>, parent: Parent): Map<FileHash, FileHash>
    public suspend fun bulkDelete(hashes: Collection<FileHash>): Map<FileHash, FileHash>

    public fun dumpCache(): String
    public suspend fun pruneCache()
    public fun clearCache()
}

public data class PutOptions(
    val parent: Parent = Parent.Root,
    val pinned: Boolean = false,
    val coverPageNumber: Int = -1,
    val authors: List<String>? = null,
    val title: String? = null,
    val publicationDate: String? = null,
    val publisher: String? = null,
    val extraMetadata: Map<String, String> = emptyMap(),
    val fontName: String = "",
    val lineHeight: Int = -1,
    val margins: Int = 125,
    val orientation: Orientation = Orientation.Portrait,
    val tags: List<String>? = null,
    val textAlignment: TextAlignment = TextAlignment.Justify,
    val textScale: Double = 1.0,
    val zoomMode: ZoomMode = ZoomMode.BestFit,
    val viewBackgroundFilter: BackgroundFilter? = null,
    val customZoomScale: Double? = null,
    val customZoomCenterX: Double? = null,
    val customZoomCenterY: Double? = null,
    val customZoomPageWidth: Double? = null,
    val customZoomPageHeight: Double? = null,
    val customZoomOrientation: Orientation? = null,
)   // note: no refresh field — that concern is refreshRoot()'s

// ---------- raw client ----------

public interface RawRemarkableClient {
    public suspend fun getRootHash(): RootInfo
    public suspend fun putRootHash(hash: FileHash, generation: Long, broadcast: Boolean = true): RootUpdate

    public suspend fun getBlob(fileName: String, hash: FileHash): ByteArray
    public suspend fun getText(fileName: String, hash: FileHash): String
    public suspend fun getEntries(fileName: String, hash: FileHash): EntryIndex
    public suspend fun getContent(fileName: String, hash: FileHash): Content
    public suspend fun getMetadata(fileName: String, hash: FileHash): Metadata

    // stage (pure, local hashing) + upload (network) — see §D5
    public fun stageFile(id: String, bytes: ByteArray): StagedFile
    public fun stageText(id: String, text: String): StagedFile
    public fun stageContent(id: String, content: Content): StagedFile        // requires ".content" id
    public fun stageMetadata(id: String, metadata: Metadata): StagedFile     // requires ".metadata" id
    public fun stageEntries(id: String, entries: List<RawEntry>, schemaVersion: SchemaVersion): StagedFile
    public suspend fun upload(staged: StagedFile)

    /** the simple ingestion endpoint backing the high-level upload* family */
    public suspend fun uploadFile(visibleName: String, bytes: ByteArray, kind: UploadKind): ItemRef

    public fun dumpCache(): String
    public fun clearCache()
}

public data class RootInfo(val hash: FileHash, val generation: Long, val schemaVersion: SchemaVersion)
public data class RootUpdate(val hash: FileHash, val generation: Long)

public class StagedFile internal constructor(
    public val entry: RawEntry,
    internal val fileName: String,
    internal val bytes: ByteArray,
    internal val cacheText: String?,   // for text stages: cached on upload success
)

public data class RawEntry(
    val type: RawEntryType,            // Collection (wire 80000000) | File (wire 0)
    val hash: FileHash,
    val id: String,                    // "<uuid>.content", "<uuid>", "root", ...
    val subfiles: Int,
    val size: Long,
)

public data class EntryIndex(
    val entries: List<RawEntry>,
    val id: String? = null,            // schema 4 only
    val size: Long? = null,            // schema 4 only
)

public enum class SchemaVersion(public val wire: Int) { V3(3), V4(4) }
public enum class UploadKind(public val mime: String) {
    Pdf("application/pdf"), Epub("application/epub+zip"), Folder("folder"),
}

// ---------- content & metadata (wire types; all val-only) ----------

public sealed interface Tags {
    public data class Structured(val tags: List<Tag>) : Tags
    public data class Legacy(val names: List<String>) : Tags
    public val names: List<String>     // uniform read access
}

public sealed interface Content

public data class CollectionContent(
    val tags: Tags = Tags.Structured(emptyList()),
) : Content                                                    // strict decode (no extra)

public data class DocumentContent(
    val coverPageNumber: Int,
    val documentMetadata: DocumentMetadata,
    val extraMetadata: Map<String, String>,
    val fileType: FileType,
    val fontName: String,
    val lineHeight: Int,
    val orientation: Orientation,
    val pageCount: Int,
    val textAlignment: TextAlignment,
    val textScale: Double,
    val tags: Tags? = null,
    val formatVersion: Int? = null,
    val margins: Int? = null,
    val pages: List<String>? = null,
    val pageTags: List<PageTag>? = null,
    val redirectionPageMap: List<Int>? = null,
    val sizeInBytes: String? = null,
    val originalPageCount: Int? = null,
    val lastOpenedPage: Int? = null,
    val dummyDocument: Boolean? = null,
    val zoomMode: ZoomMode? = null,
    val customZoomScale: Double? = null,
    val customZoomCenterX: Double? = null,
    val customZoomCenterY: Double? = null,
    val customZoomPageWidth: Double? = null,
    val customZoomPageHeight: Double? = null,
    val customZoomOrientation: Orientation? = null,
    val viewBackgroundFilter: BackgroundFilter? = null,
    val transform: Transform? = null,
    val keyboardMetadata: KeyboardMetadata? = null,
    val cPages: CPages? = null,
    val extra: JsonObject = JsonObject(emptyMap()),            // passthrough
) : Content

public data class TemplateContent(
    val name: String, val author: String, val iconData: String,
    val categories: List<String>, val labels: List<String>,
    val orientation: Orientation, val templateVersion: String,
    val supportedScreens: List<SupportedScreen>,
    val constants: List<Map<String, Int>>? = null,
    val items: List<JsonObject>,
    val formatVersion: Int? = null,
) : Content                                                    // strict decode (no extra)

public data class Metadata(
    val visibleName: String,
    val parent: Parent,
    val pinned: Boolean,
    val type: EntryType,               // DocumentType | CollectionType | TemplateType (wire strings)
    val lastModified: String,
    val lastOpened: String? = null,
    val lastOpenedPage: Int? = null,
    val createdTime: String? = null,
    val deleted: Boolean? = null,
    val metadatamodified: Boolean? = null,
    val modified: Boolean? = null,
    val synced: Boolean? = null,
    val version: Int? = null,
    val new: Boolean? = null,
    val source: String? = null,
    val extra: JsonObject = JsonObject(emptyMap()),            // passthrough
)

public data class Tag(val name: String, val timestamp: Long, val extra: JsonObject = JsonObject(emptyMap()))
public data class PageTag(val name: String, val pageId: String, val timestamp: Long, val extra: JsonObject = JsonObject(emptyMap()))
// DocumentMetadata, KeyboardMetadata, Transform, CPages/CPagePage/CPageUUID/CPage*Value: direct ports of raw.ts

// ---------- errors (§D8): declared there ----------

// ---------- devices ----------

public enum class DeviceModel { RM100, RM110, RM02A, RM03A, RM102 }
public data class DeviceScreen(val name: String, val width: Int, val height: Int, val dpi: Int)
public val deviceScreens: Map<DeviceModel, DeviceScreen>
```

Behavioral parity notes baked into the implementation plan:

- `putRootHash` failure with `{"message":"precondition failed"}` → `GenerationException`
  and invalidation of the client's cached root/generation (mirrors `#putRootHash`).
- `stageEntries("root", …, V3)` emits the "reMarkable rejects schema 3 root indexes"
  warning (JS `console.warn` → `System.err.println`); high-level code always writes
  roots as schema 4.
- Index serialization is byte-identical to `raw.ts` `putEntries`: entries sorted by id
  with plain lexicographic `compareTo` (settled; identical to JS's `localeCompare` for
  uuid ids, corroborated by Go's byte-order sort), schema-4 header
  `0:<'.' for root|id>:<count>:<size>`, type coerced to `0` in schema-4 lines.
- `getEntries` parses both schemas with the same malformed-line errors as `raw.ts`.
- The `put*` flow fabricates content/metadata exactly as JS's `#putFile`/`putFolder`
  do (pageCount 1, fake page uuid, `sizeInBytes`, epoch-millis strings).

---

## 5. Module / Repo Layout

```
rmapi-kt/
├── build.gradle.kts            # mideakt's, plus coroutines, okhttp, kover, animalsniffer
├── settings.gradle.kts         # rootProject.name = "rmapi-kt"
├── gradle.properties           # dokka V2 flags (as mideakt)
├── jitpack.yml                 # openjdk17, publishToMavenLocal -x test
├── config/detekt/detekt.yml    # mideakt's deviations file verbatim
├── LICENSE                     # MIT
├── README.md                   # badges (build/jitpack), model overview, usage,
│                               #   put* vs upload* mechanism explainer, custom-
│                               #   transport snippet, Java-interop note
├── scripts/                    # one-off fixture extraction from index.spec.ts
├── .github/
│   ├── dependabot.yml
│   └── workflows/{build,cut,release}.yml    # copied from mideakt
├── src/main/kotlin/io/hafa/rmapikt/
│   ├── Register.kt             # register / auth / session / remarkable, Hosts, SessionOptions
│   ├── Client.kt               # RemarkableClient + implementation
│   ├── Raw.kt                  # RawRemarkableClient + implementation, index parse/serialize
│   ├── Entities.kt             # Entry hierarchy, ItemRef, Parent, ids
│   ├── Content.kt              # Content hierarchy, Tags, Metadata, Tag, CPages, enums
│   ├── Serialization.kt        # PassthroughSerializer, Tags serializer, Content discrimination
│   ├── Http.kt                 # internal: OkHttp suspend adapter + authed request building
│   ├── Cache.kt                # CacheEntry, LruCache, versioned dump/load
│   ├── Digest.kt               # sha256, Crc32c (internal), hex helpers
│   ├── Errors.kt               # RemarkableException hierarchy
│   └── Devices.kt              # DeviceModel/DeviceScreen/deviceScreens
└── src/test/
    ├── kotlin/io/hafa/rmapikt/ # ported suites, §8
    └── resources/fixtures/     # index bodies + content/metadata JSON extracted from index.spec.ts
```

File-per-concept naming follows mideakt (`MatchingDeclarationName` off in detekt).

---

## 6. Build & CI

- **Gradle**: mideakt's `build.gradle.kts` plus `kotlin("plugin.serialization")`,
  `org.jetbrains.kotlinx.kover` (verification rule: **≥ 90% line coverage** of
  `io.hafa.rmapikt`, settled; wired into `check`), and `animalsniffer` with
  `gummy-bears` API-21 signatures (automated §D2 enforcement).
- **Dependencies** (runtime): `kotlinx-serialization-json`, `kotlinx-coroutines-core`,
  `com.squareup.okhttp3:okhttp` (5.x preferred — see below). Test: `kotlin("test")`,
  `kotlinx-coroutines-test`, and **`com.squareup.okhttp3:mockwebserver3` +
  `mockwebserver3-junit5`** — the *primary* test dependency; every network-touching
  test runs against it (§8.2). Note: `mockwebserver3` is the OkHttp 5 package with
  the stabilized API, **not** the feature-frozen legacy `okhttp3.mockwebserver`; this
  is a reason to pin OkHttp 5.x (its Android floor permitting API 21). If
  implementation ends up pinning OkHttp 4.x instead, fall back to the legacy artifact
  and note the tradeoff.
- **Publishing** (settled): JitPack via `maven-publish` + `jitpack.yml` exactly as
  mideakt. No Maven Central, no GPG.
- **CI**: `build.yml` (push/PR: `./gradlew build` on temurin 17 — detekt, tests, kover
  gate, animalsniffer), `cut.yml` (dispatch: version bump, tag, dispatch release),
  `release.yml` (GitHub release from tag) — copied from mideakt verbatim apart from
  the repo name.
- **Docs**: Dokka HTML via JitPack's javadoc hosting, as mideakt.

---

## 7. Verification Plan

**Testing is unit testing.** No rmfakecloud, no Docker, no recorded cassettes, no
live-cloud smoke test (all settled) — and, after deleting the seams (§D3/§D10), no
test-only API surface either. Tests start a local **MockWebServer** and point the
`authHost`/`rawHost`/`uploadHost` options at it — options that exist for rmfakecloud
anyway. `server.enqueue(...)` is the FIFO response queue rmapi-js's `createMockFetch`
hand-rolls; `server.takeRequest()` yields the method/path/headers/body assertions its
call log provided. The ported tests (§8) — including the golden-hash and byte-exact
index-body tests that pin actual wire bytes — are the behavioral contract, exercised
through real OkHttp over a real socket.

**Independent verification is a separate reviewer, not separate infrastructure.**
After implementation, a fresh agent session with *no implementation context* (inputs:
this plan, the rmapi-js checkout, the finished rmapi-kt source) performs two audits:

1. **Intent-parity audit.** Walk rmapi-js's public exports capability-by-capability
   (`src/index.ts` + `src/raw.ts`; `bun doc:md` output is a convenient checklist) and
   confirm each capability is *reachable* in rmapi-kt. Signatures are not expected to
   match — the API deliberately diverges — so for every divergence the auditor checks
   there is a §D7 row (or equivalent rationale in §3) naming and justifying it. A
   capability with no Kotlin path, or a divergence with no recorded rationale, fails
   the audit.
2. **Test-fidelity audit.** For each row of the §8.3 mapping table, read the JS test
   and its Kotlin counterpart side by side and confirm the Kotlin test covers the JS
   test's **assertion intent** — same fixtures (byte-identical where extracted to
   resources), same scripted responses, equally strong or stronger expected values —
   even where the Kotlin call shape differs. A weakened stand-in (asserting a hash's
   length where JS asserts its value) fails the audit.

The verifier reports discrepancies; the implementer fixes; repeat until clean. The
implementer must not pre-fill either audit. Acceptance: both audits clean, the ported
suite green, the Kover gate met.

---

## 8. Test-Porting Plan

### 8.1 How the rmapi-js tests work

`index.spec.ts` (1146 lines) uses bun's test runner. `test-utils.ts` provides
`mockFetch(...responses)` — a spy on the global `fetch` returning a FIFO of canned
responses (`emptyResponse`, `textResponse`, `jsonResponse`, `bytesResponse`), throwing
"didn't set next response" past the end, and recording calls for URL/header/body
assertions. Fixtures are inline template strings (schema-3/4 index bodies built with
`repHash`-style 64-char hashes) and inline typed content/metadata literals. Every test
builds the client with `remarkable("")` (the first queued response satisfies the auth
exchange). `lru.spec.ts` covers the LRU cache in isolation.

### 8.2 Kotlin test stack: MockWebServer, zero test-only surface

- `kotlin.test` on the JUnit 5 platform (mideakt's stack) with
  `kotlinx-coroutines-test`'s `runTest`, plus **`mockwebserver3` +
  `mockwebserver3-junit5`** (§6).
- **The pattern**: each test starts a `MockWebServer` (the JUnit 5 extension manages
  lifecycle) and builds the client with
  `session(token, SessionOptions(rawHost = server.url("/").toString(), …))` — the
  same host options that exist for rmfakecloud. **Testing therefore needs zero
  test-only API**: this is the concrete demonstration that the deleted seams were
  unnecessary.
- `server.enqueue(MockResponse(...))` replaces `createMockFetch`'s hand-rolled FIFO;
  `server.takeRequest(): RecordedRequest` (method, path, headers, body) replaces its
  call log. Small local builders map the four JS helpers — `emptyResponse()`,
  `textResponse()`, `jsonResponse()`, `bytesResponse()` — onto `MockResponse`, so
  ported tests still read like their JS counterparts. "No network call happened"
  (JS's `session()` test asserts the fetch spy was never invoked) is
  `server.requestCount == 0`.
- **Fidelity upside**: tests now exercise real OkHttp — real header encoding, real
  request bytes on a real socket — and can simulate connection-level failures
  (disconnects, stalls) no in-process fake could. Cost: real sockets are marginally
  slower per test; negligible at 46 tests.
- A `fixtures.kt` helper ports `repHash`; shared multi-line index fixtures live in
  `src/test/resources/fixtures/` **extracted verbatim from `index.spec.ts`** (one-off
  script in `scripts/`), so the bytes are provably the JS suite's data, not re-typed.
- Golden values asserted verbatim: the issue-#25 schema-4 entry hash
  (`3c89dd3036f0b335188659d4f7139fcfd906167d99729d638af956906b647646`) and the
  byte-exact schema-4 root body `4\n0:.:1:219\n<hash>:0:<id>:2:219\n` with its SHA-256.
  These drive `stageEntries` with explicit caller-supplied entries — as rmapi-js's own
  tests do — so no minted values are involved.
- **Minted values, asserted structurally** (the price of deleting the `Environment`
  seam, §D10): for the `putPdf`/`putEpub` flows, whose `.content`/`.metadata` bodies
  contain a freshly minted page uuid and `System.currentTimeMillis()` timestamps, the
  recorded request body is decoded and every field asserted — with the minted values
  checked for well-formedness (`pages` holds one syntactically valid uuid;
  `lastModified` parses as a plausible epoch-millis string) instead of exact bytes.
  This is deliberately, slightly weaker than a byte-exact pin on those two bodies;
  every deterministic body remains byte-asserted.

### 8.3 JS test → Kotlin test mapping (by behavior; all 46 accounted for)

Because the API deliberately diverges (§D7), rows map **assertion intent**, not
signatures. Target classes in `src/test/kotlin/io/hafa/rmapikt/`: `RegisterTest`,
`AuthTest`, `ClientConstructionTest`, `ListTest`, `GetTest`, `PutTest`,
`UploadTest`, `EditTest`, `BulkTest`, `CacheTest`, `FailureTest`, `LruCacheTest`.

| # | JS test (`index.spec.ts` unless noted) | Kotlin test (by behavior) | Notes |
|---|---|---|---|
| 1 | `register() > success` | `RegisterTest.success` | token returned, single call, correct endpoint/body |
| 2 | `register() > invalid` | `RegisterTest.rejectsBadCodeLength` | `IllegalArgumentException` (JS: bare Error) |
| 3 | `register() > error` | `RegisterTest.non2xxThrowsResponseException` | |
| 4 | `auth() > success` | `AuthTest.success` | asserts `Authorization: Bearer …` header |
| 5 | `auth() > error` | `AuthTest.non2xxThrows` | |
| 6 | `remarkable() > success` | `ClientConstructionTest.remarkableExchangesToken` | |
| 7 | `remarkable() > error` | `ClientConstructionTest.remarkableAuthFailure` | |
| 8 | `session() > uses provided token and skips exchange` | `ClientConstructionTest.sessionSkipsExchange` | `server.requestCount == 0` |
| 9 | `session() > throws when cache is invalid` | `ClientConstructionTest.sessionRejectsCorruptCache` | invalid/unversioned dump → clear error (format is ours, behavior is the JS intent) |
| 10 | `#listItems()` | `ListTest.listItemsDocumentAndTemplate` | doc + template entries, full field mapping onto sealed `Entry` |
| 11 | `#getMetadata() accepts lastOpenedPage -1` | `GetTest.metadataAcceptsNegativeLastOpenedPage` | |
| 12 | `#listIds()` | `ListTest.listRefs` | same fixture; result asserted as `ItemRef`s |
| 13 | `#getContent() > DocumentType` | `GetTest.contentDocument` | |
| 14 | `#getContent() > CollectionType legacy tags` | `GetTest.contentCollectionLegacyTags` | asserts `Tags.Legacy(["Remarcal","calendar"])` + re-encode round-trips as strings |
| 15 | `#getContent() > DocumentType legacy tags` | `GetTest.contentDocumentLegacyTags` | ditto on `DocumentContent` |
| 16 | `#getContent() > handles empty textAlignment and null pages` | `GetTest.contentEmptyAlignmentNullPages` | `TextAlignment.Default`, `pages == null` |
| 17 | `#getContent() > handles empty transform object` | `GetTest.contentEmptyTransform` | |
| 18 | `#getContent() > TemplateType` | `GetTest.contentTemplate` | |
| 19 | `#getContent() > TemplateType without constants` | `GetTest.contentTemplateNoConstants` | |
| 20 | `#getContent() > Validation Error` | `GetTest.contentValidationFailure` | `ValidationException` with `rawJson` carrying the offending text (JS: ZodError; unification per §D8) |
| 21 | `#getMetadata()` | `GetTest.metadata` | |
| 22 | `#getPdf()` | `GetTest.pdfBytes` | |
| 23 | `#getEpub()` | `GetTest.epubBytes` | |
| 24 | `#getDocument()` | `GetTest.documentFiles` | JS asserts zip length > 0; Kotlin asserts the map's file names *and* bytes — same intent, stronger |
| 25 | `#uploadPdf()` | `UploadTest.uploadPdf` | docID/hash mapped into `ItemRef` |
| 26 | `#putEntries() v4` | `PutTest.stageEntriesV4GoldenHash` | golden `3c89dd30…` hash |
| 27 | `#putEntries() schema 4 root index` | `PutTest.stageEntriesV4RootIndexBytes` | byte-exact body, URL contains hash, type coerced to 0 |
| 28 | `#putPdf()` | `PutTest.putPdf` | full 4-file + index + root flow; minted-value bodies asserted structurally (§8.2) |
| 29 | `#uploadEpub()` | `UploadTest.uploadEpub` | |
| 30 | `#putEpub()` | `PutTest.putEpub` | |
| 31 | `#createFolder()` | `PutTest.putFolderMixedSchemas` | asserts root body starts `4\n0:.:` while doc index stays `3\n` |
| 32 | `#stared()` | `EditTest.setStarred` | |
| 33 | `#move()` | `EditTest.move` | `Parent.Trash` / `Parent.Folder` variants |
| 34 | `#move() failure` | `EditTest.moveUnknownHashThrowsHashNotFound` | |
| 35 | `#delete()` | `EditTest.deleteMovesToTrash` | |
| 36 | `#rename()` | `EditTest.rename` | |
| 37 | `#bulkMove()` | `BulkTest.bulkMove` | result map keyed by old `FileHash` |
| 38 | `#bulkDelete()` | `BulkTest.bulkDelete` | |
| 39 | `#pruneCache()` | `CacheTest.pruneDropsUnreachable` | warm-start from a v1 dump; orphan dropped, reachable kept |
| 40 | `#dumpCache()` | `CacheTest.dumpRoundTripAndClear` | JS asserts dump non-empty / `{}` after clear; Kotlin asserts dump→restore round-trip equality and empty-after-clear — same intent, format-agnostic and stronger |
| 41 | `validation fail` | `FailureTest.invalidParentRejected` | `Parent.Folder(ItemId("invalid"))` fails `ItemId` validation at construction — check moved earlier than JS, intent identical |
| 42 | `generation fail` | `FailureTest.preconditionFailedThrowsGenerationException` | also asserts `staleGeneration` |
| 43 | `request fail` | `FailureTest.malformedRootJson` | |
| 44 | `response fail` | `FailureTest.non2xxThrowsResponseException` | message contains body |
| 45 | `verification fail` | `FailureTest.schemaMismatchThrowsValidation` | JS #43/#45 are duplicates; both rows keep an assertion so the table stays total |
| 46 | `lru.spec.ts > LruCache()` | `LruCacheTest.sizeAccountingAndEviction` | same scenario re-expressed in bytes: update-in-place no-evict, LRU eviction, delete, clear |

Kotlin-only additions (coverage only — no API surface): passthrough round-trip tests
(unknown keys survive `updateDocument`/`rename`), `Tags` legacy/structured serializer
round-trips, `Parent` wire-mapping, `refreshRoot` semantics (cached root reused across
calls; refetched after `refreshRoot()` and after a `GenerationException`), cache
thread-safety smoke (parallel writers), versioned-dump rejection of unknown versions,
a custom-`OkHttpClient` pass-through test (an interceptor-tagged client is actually
used), and connection-failure tests (MockWebServer disconnect mid-body → the
transport's `IOException` surfaces unwrapped, per §D8).

### 8.4 Go test porting (brief)

Only the sync15 index/hashing tests overlap the scope, and they are worth porting
because their fixtures come from an *independent* implementation — disagreement with
the JS-derived tests would expose a latent bug in one of the references:

| Go test (ddvk/rmapi) | Kotlin test | Notes |
|---|---|---|
| `api/sync15/tree_test.go`: entry-line parsing | `IndexFormatTest.parsesGoEntryFixtures` | lift inline `hash:type:id:subfiles:size` fixtures verbatim |
| `tree_test.go`: v3 index parse | `IndexFormatTest.parsesSchema3Index` | |
| `tree_test.go`: v4 index parse (header, count mismatch) | `IndexFormatTest.parsesSchema4Index` | rmapi-js *errors* on count mismatch where Go warns — keep js behavior, assert it |
| `tree_test.go`: doc index serialization (schema 3) | `IndexFormatTest.serializesDocIndex` | |
| `tree_test.go`: root v4 serialization + `TestRootIndexWritesV4WhenMirroredV3` | `IndexFormatTest.rootIndexAlwaysV4` | overlaps JS rows #27/#31 but keeps Go's fixture bytes |
| `common.go` `HashEntries` doc-hash rule | `HashingTest.schema3HashIsConcatenatedSortedHashes` | cross-checks the JS golden hash of row #26 |

Not ported: `filetree/`, `archive/`, `encoding/rm/`, `annotations/`, `config/`,
`shell/`/`util/` tests — all out-of-scope features (§2.2, §9).

### 8.5 Coverage gate

Kover `verify` at ≥ 90% line coverage of `io.hafa.rmapikt` (settled), wired into
`check` (hence CI and the JitPack publish path). The mapping table exercises every
public method; the additions push branch coverage on serialization, cache, and HTTP
adapter paths.

---

## 9. V1 Scope

**Feature set: rmapi-js 11.1.2, exactly.** Registration/auth/session, both client
tiers, all wire types, cache with dump/prune/clear + LRU bound, errors, devices table,
schema 3+4 read / schema-4 root write, the simple upload API. **Surface: designed in
Kotlin** — the §D7 table is the normative list of shape divergences; §7's audit
guarantees no capability was lost in the redesign. The only HTTP-related public
surface is `SessionOptions.httpClient: OkHttpClient` (§D3); there are no transport
abstractions and no test seams of any kind (§D10).

**Out of scope** (not "deferred" — simply not part of this project; §2.2 has the
rationale): CLI/shell, path filetree and globbing, archive/raw-notebook upload,
content-only file replace, automatic token refresh (device tokens don't expire;
session-token refresh is "recreate the client", as rmapi-js documents), retry helpers
(caller's policy), request-concurrency limits (parity: unbounded), `Flow` listing
variants, annotated-PDF export and `.rm` parsing, thumbnails, disk tree cache, sync10
and the dead `/sync/v2/signed-urls/*` + `/sync/v2/sync-complete` endpoints,
websocket/notification events (notification is the `broadcast` flag on the root PUT),
`.rmapi` config files (token persistence is the caller's job), rmapi-js cache-dump
interchange (cold start instead), and a blocking/Java facade (README documents the
`kotlinx.coroutines.future` bridge).

---

## 10. Open Questions For The User

(Settled and no longer open: owner `hafaio`; library-only; Android-safe minSdk 21;
OkHttp used directly with `SessionOptions.httpClient` as the only HTTP-related public
surface — **no transport abstraction and no environment seam; both deleted**, testing
runs on MockWebServer through the existing host options; JitPack; mideakt build/CI;
90% Kover gate; the rename set — `put*` names **kept** (put/upload distinction
carried by KDoc), `listRefs`/`getDocumentFiles`/`getBlob`/`ItemRef`/`setStarred`
accepted; no library opinion on retryability; plain-`compareTo` entry sorting; Go as
wire cross-check + hashing vectors; unit-tests-only verification with a fresh-session
intent-parity + test-fidelity audit; Kotlin-native cache and errors with rmapi-js
compatibility dropped.)

1. **Entry-sort collation** (informational, not blocking): `raw.ts` sorts index
   entries with `localeCompare`; Go sorts by plain byte order; the plan uses plain
   `compareTo`. Confirmed that ICU root collation and code-unit order agree across
   the id character set actually in play (lowercase hex, `-`, `.`, `/`), so no
   divergence is expected; the golden-hash tests pin it. Noted only in case some
   future id form falls outside that character set.
