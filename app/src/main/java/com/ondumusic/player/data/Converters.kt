package com.ondumusic.player.data

import androidx.room.TypeConverter
import com.ondumusic.player.model.ArtworkVariant
import java.time.Instant
import java.util.UUID

/** UUIDs are stored as text rather than Room's default blob, so the database stays readable when debugging. */
class Converters {
    @TypeConverter fun uuidToString(value: UUID?): String? = value?.toString()
    @TypeConverter fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun artworkToJson(value: List<ArtworkVariant>): String = ArtworkJson.encode(value)
    @TypeConverter fun jsonToArtwork(value: String): List<ArtworkVariant> = ArtworkJson.decode(value)
}
