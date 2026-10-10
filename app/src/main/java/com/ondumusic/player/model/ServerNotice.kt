package com.ondumusic.player.model

import com.ondumusic.player.api.ServerNoticeDto
import java.time.Instant
import java.util.UUID

/**
 * Something the server wants to say — a library path that has gone missing, and
 * whatever else it learns to report later.
 *
 * `code` is the contract and `message` is the safety net: we show our own wording for
 * codes we know, so it's localised and says what to do, and fall back to the server's
 * English for codes added after this build shipped. That fallback is the whole reason
 * the server can add notices without an app update.
 */
data class ServerNotice(
    val id: UUID,
    val code: String,
    val level: Level,
    /** The server's own English. Shown only for codes this build doesn't know. */
    val message: String,
    val raisedAt: Instant,
) {
    enum class Level {
        Info,
        Warning;

        companion object {
            /**
             * Anything the server invents later is worth showing, and worth showing
             * quietly rather than as an alarm.
             */
            fun fromRawValue(rawValue: String): Level = if (rawValue == "warning") Warning else Info
        }
    }

    /** The codes this build has its own words for. */
    enum class Known(val code: String) {
        LibraryUnavailable("library_unavailable"),
    }

    val known: Known?
        get() = Known.entries.firstOrNull { it.code == code }
}

fun ServerNoticeDto.toNotice() = ServerNotice(
    id = id,
    code = code,
    level = ServerNotice.Level.fromRawValue(level),
    message = message,
    raisedAt = raisedAt,
)
