package com.ondumusic.player.model

/**
 * Whether this build and the server it's talking to speak the same API.
 *
 * `apiVersion` is an integer the server bumps only when it breaks existing clients, so a
 * mismatch is a fact rather than a judgement — and it can point either way, because the
 * app and the server update on their own schedules.
 *
 * Messages should name the side that's behind first, so the first words say who has to
 * act, and show the server's own version rather than the API integer: "1.3.0" is what
 * you look for when you go to update it.
 */
sealed class ServerCompatibility {
    data object Ok : ServerCompatibility()

    /** The server has moved on; this build can't keep up. */
    data class AppTooOld(override val serverVersion: String) : ServerCompatibility()

    /** This build expects an API the server doesn't have yet. */
    data class ServerTooOld(override val serverVersion: String) : ServerCompatibility()

    /**
     * What a dismissal is remembered against: say it once per server version, not once
     * ever, so an upgrade that doesn't fix it speaks up again.
     */
    open val serverVersion: String?
        get() = null

    /**
     * Said once per server version rather than once ever: a dismissal that outlived the
     * condition would hide a real problem, and one that came back every launch would be
     * nagging about something the user can't fix on the spot.
     */
    fun shouldShow(dismissedVersion: String): Boolean {
        val serverVersion = serverVersion ?: return false
        return serverVersion != dismissedVersion
    }

    companion object {
        fun compare(serverApiVersion: Int, serverVersion: String, supported: Int): ServerCompatibility = when {
            serverApiVersion > supported -> AppTooOld(serverVersion)
            serverApiVersion < supported -> ServerTooOld(serverVersion)
            else -> Ok
        }
    }
}
