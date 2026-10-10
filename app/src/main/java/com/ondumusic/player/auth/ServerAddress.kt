package com.ondumusic.player.auth

import com.ondumusic.player.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerAddress {
    /**
     * Debug only: release builds refuse an http:// server outright, so nothing cleartext is
     * ever requested. Debug keeps it so a local server works without setting up TLS first.
     */
    val allowsInsecureServers: Boolean = BuildConfig.DEBUG

    /**
     * Resolves what the user typed into the API base URL: https unless a scheme is given,
     * and `/api` unless a path is. [allowingInsecure] is a parameter rather than only a
     * build flag so both policies are testable — tests build as debug and would otherwise
     * never exercise the release behaviour.
     */
    fun resolve(input: String, allowingInsecure: Boolean = allowsInsecureServers): HttpUrl? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if ("://" !in text) text = "https://$text"

        val url = text.toHttpUrlOrNull() ?: return null
        if (!allowingInsecure && url.scheme == "http") return null

        val hasPath = url.pathSegments.any { it.isNotEmpty() }
        return if (hasPath) url else url.newBuilder().encodedPath("/api").build()
    }

    /** What the login field shows for a stored address: the defaults are left implied. */
    fun display(url: String): String = url.removePrefix("https://").removeSuffix("/api")
}
