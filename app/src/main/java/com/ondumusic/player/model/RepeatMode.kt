package com.ondumusic.player.model

enum class RepeatMode(val raw: String) {
    Off("off"), One("one"), All("all");

    companion object {
        fun fromRaw(raw: String?): RepeatMode = entries.firstOrNull { it.raw == raw } ?: Off
    }
}
