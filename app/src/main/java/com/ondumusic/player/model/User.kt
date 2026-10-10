package com.ondumusic.player.model

import kotlinx.serialization.Serializable
import com.ondumusic.player.api.UuidSerializer
import java.util.UUID

@Serializable
data class User(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val username: String,
)
