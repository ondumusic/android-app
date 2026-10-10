package com.ondumusic.player.data

import com.ondumusic.player.api.ApiJson
import com.ondumusic.player.model.ArtworkVariant
import kotlinx.serialization.builtins.ListSerializer

/** Kept out of [Converters]: Room's processor can't see the serializer the serialization plugin generates. */
internal object ArtworkJson {
    private val serializer = ListSerializer(ArtworkVariant.serializer())

    fun encode(value: List<ArtworkVariant>): String = ApiJson.encodeToString(serializer, value)

    fun decode(value: String): List<ArtworkVariant> =
        runCatching { ApiJson.decodeFromString(serializer, value) }.getOrDefault(emptyList())
}
