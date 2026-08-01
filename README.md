# rmapi-kt

[![build](https://github.com/hafaio/rmapi-kt/actions/workflows/build.yml/badge.svg)](https://github.com/hafaio/rmapi-kt/actions/workflows/build.yml)
[![jitpack](https://jitpack.io/v/hafaio/rmapi-kt.svg)](https://jitpack.io/#hafaio/rmapi-kt)
[![license](https://img.shields.io/github/license/hafaio/rmapi-kt)](LICENSE)

Kotlin/JVM client for the reMarkable cloud API, a port of
[rmapi-js](https://github.com/erikbrinkman/rmapi-js). Plain Kotlin/JVM, and
Android-compatible down to API 21.

## API

All data is stored by its SHA-256 hash. This includes raw files ("documents") and
folders ("collections"); the hash captures the full current state, which is how
simultaneous edits are detected. Most reads and edits take an input hash and return an
output hash. Every entry also has an `id`, a uuid4 that is stable for the lifetime of
the file or folder. Two parents are special: the root collection (where files live by
default) and the trash — both modeled by `Parent` rather than magic strings.

## Usage

Register once with the eight-letter code from
<https://my.remarkable.com/device/desktop/connect>, then persist the device token.

```kotlin
val deviceToken = register("abcdefgh")   // persist this; it does not expire
val api = remarkable(deviceToken)
val entries = api.listItems()
```

In stateless environments, exchange the device token once and reuse the session:

```kotlin
val sessionToken = auth(deviceToken)     // short-lived
val api = session(sessionToken)          // no network call
```

Uploading a pdf or epub:

```kotlin
api.uploadPdf("name", bytes)
api.uploadEpub("name", bytes)
```

There are two upload mechanisms, and the distinction matters:

- `upload*` hands the file to the server's ingestion endpoint. Robust, no options.
- `put*` builds the document's component files client-side and commits them through the
  sync protocol. Full control via `PutOptions`, and it can throw `GenerationException`
  if the cloud moved underneath you.

```kotlin
// a line height reMarkable's own apps don't expose
api.putEpub("name", bytes, PutOptions(lineHeight = 180))

// fetch an uploaded epub by reference
val epub = api.getEpub(entry.ref)
```

## Configuration

The client takes an `OkHttpClient`, so timeouts, interceptors, proxies, and certificate
pinning are all configured the usual way:

```kotlin
val api = session(
    sessionToken,
    SessionOptions(httpClient = OkHttpClient.Builder().build()),
)
```

The three hosts are overridable too, which is how you point it at
[rmfakecloud](https://github.com/ddvk/rmfakecloud).

## Gotchas

This API is reverse-engineered, so responses are validated against what we expect. A
`ValidationException` means the payload didn't match — it carries the raw text so you
can parse it yourself, and the low-level `api.raw` client will hand you the unparsed
blob.

Exporting rendered documents happens inside reMarkable's own apps and requires laying
out the notebook format; that's out of scope here.

## Installation

Via [JitPack](https://jitpack.io/#hafaio/rmapi-kt):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.hafaio:rmapi-kt:<version>")
}
```

Maven users note that OkHttp 5 is published as a Kotlin Multiplatform project — the
`okhttp` artifact is empty for Maven, so select `okhttp-jvm` or `okhttp-android`
explicitly. Gradle handles this automatically.

## Design

[`docs/design.md`](docs/design.md) records the full design, including where this
library deliberately diverges from rmapi-js and why.

## License

MIT
