package com.ondumusic.player.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Lowercase on the wire, which `UUID.toString()` already is; the server normalizes anyway. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Whole seconds, matching what the server sends and what the iOS client writes. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.truncatedTo(ChronoUnit.SECONDS).toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

typealias SerialUuid = @kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID
typealias SerialInstant = @kotlinx.serialization.Serializable(with = InstantSerializer::class) Instant
