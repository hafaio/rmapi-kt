package io.hafa.rmapikt

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private const val FILE_TYPE_KEY = "fileType"

/**
 * `ignoreUnknownKeys = false` is the library's decoding policy, not an incidental setting:
 * a payload carrying a key we don't model is reported rather than silently accepted, so a
 * read-modify-write can never write back a file with the unrecognised part missing. The
 * cost is that a field added by new firmware fails the parse until this library is updated;
 * [ValidationException] carries the raw text so a caller can still read it in the meantime.
 *
 * `encodeDefaults = false` keeps a field the device never wrote from appearing in what we
 * write back.
 */
internal val wireJson: Json = Json {
    encodeDefaults = false
    ignoreUnknownKeys = false
}

/** Maps the root and the trash to the empty string and `"trash"` the cloud uses for them. */
internal object ParentSerializer : KSerializer<Parent> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.hafa.rmapikt.Parent", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Parent) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): Parent = Parent.ofWire(decoder.decodeString())
}

/**
 * Reads whichever of the two tag shapes the device wrote and writes the same one back.
 *
 * An element is an object under current firmware and a bare string under older firmware.
 * Normalising the two would mean a read-modify-write silently upgraded the device's tag
 * format, so the distinction is carried in [Tags] instead.
 */
internal object TagsSerializer : KSerializer<Tags> {
    private val structured = ListSerializer(Tag.serializer())
    private val legacy = ListSerializer(String.serializer())

    // Both shapes are lists and the element type is only consulted for framing; no single
    // descriptor describes a union of the two.
    override val descriptor: SerialDescriptor = legacy.descriptor

    override fun serialize(encoder: Encoder, value: Tags) {
        when (value) {
            is Tags.Structured -> encoder.encodeSerializableValue(structured, value.tags)
            is Tags.Legacy -> encoder.encodeSerializableValue(legacy, value.names)
        }
    }

    override fun deserialize(decoder: Decoder): Tags {
        val jsonDecoder = decoder as JsonDecoder
        val elements = jsonDecoder.decodeJsonElement().jsonArray
        return when {
            elements.isEmpty() -> Tags.Empty
            elements.first() is JsonObject ->
                Tags.Structured(jsonDecoder.json.decodeFromJsonElement(structured, elements))
            else -> Tags.Legacy(jsonDecoder.json.decodeFromJsonElement(legacy, elements))
        }
    }
}

/**
 * Tells the three content shapes apart by which keys are present.
 *
 * Nothing on the wire tags a `.content` file with its type: `fileType` appears only on
 * documents, so its absence is what identifies a collection. Templates do not appear here —
 * theirs is empty, and reads as a collection's.
 */
internal object ContentSerializer : JsonContentPolymorphicSerializer<Content>(Content::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Content> {
        val fields = element.jsonObject
        return if (FILE_TYPE_KEY in fields) {
            DocumentContent.serializer()
        } else {
            CollectionContent.serializer()
        }
    }
}

/**
 * Keys reMarkable writes into `.content` that this library deliberately does not model.
 *
 * `modifed` is the device's own misspelling of `modified`, present on a minority of
 * documents. Modelling it would put a firmware typo into the public api, where a caller
 * would have to decide what to do with it; rejecting it would make those documents
 * unreadable. So it is neither: stripped before decoding and put back on the way out, so a
 * read-modify-write leaves it exactly as it found it.
 */
private val CONTENT_QUIRK_KEYS = setOf("modifed")

/** Parses a `.content` payload, ignoring the quirk keys above. */
internal fun decodeContent(text: String): Content {
    val fields = parseObject(text, "content")
    return try {
        wireJson.decodeFromJsonElement(
            ContentSerializer,
            JsonObject(fields.filterKeys { it !in CONTENT_QUIRK_KEYS }),
        )
    } catch (error: SerializationException) {
        throw ValidationException("could not parse content: ${error.message}", text, error)
    } catch (error: IllegalArgumentException) {
        throw ValidationException("could not parse content: ${error.message}", text, error)
    }
}

/** The quirk keys a `.content` payload carried, to be written back unchanged. */
internal fun contentQuirks(text: String): JsonObject =
    runCatching { parseObject(text, "content") }
        .map { fields -> JsonObject(fields.filterKeys { it in CONTENT_QUIRK_KEYS }) }
        .getOrElse { JsonObject(emptyMap()) }

/** Renders a `.content` payload, restoring any quirk keys the original carried. */
internal fun encodeContent(
    content: Content,
    quirks: JsonObject = JsonObject(emptyMap()),
): String {
    val encoded = wireJson.encodeToJsonElement(ContentSerializer, content).jsonObject
    return wireJson.encodeToString(JsonObject.serializer(), JsonObject(encoded + quirks))
}

private fun parseObject(text: String, what: String): JsonObject = try {
    wireJson.parseToJsonElement(text).jsonObject
} catch (error: SerializationException) {
    throw ValidationException("could not parse $what: ${error.message}", text, error)
} catch (error: IllegalArgumentException) {
    throw ValidationException("could not parse $what: ${error.message}", text, error)
}

/**
 * Parses [text] as [what], reporting a malformed payload as a [ValidationException] that
 * still carries the original text.
 *
 * The api is reverse-engineered, so a parse failure is as likely to mean the cloud changed
 * as it is to mean the caller did something wrong; either way the caller keeps the option
 * of reading the payload themselves.
 */
internal fun <T> decodeWire(serializer: KSerializer<T>, text: String, what: String): T =
    try {
        wireJson.decodeFromString(serializer, text)
    } catch (error: SerializationException) {
        throw ValidationException("could not parse $what: ${error.message}", text, error)
    } catch (error: IllegalArgumentException) {
        throw ValidationException("could not parse $what: ${error.message}", text, error)
    }

internal fun <T> encodeWire(serializer: KSerializer<T>, value: T): String =
    wireJson.encodeToString(serializer, value)
