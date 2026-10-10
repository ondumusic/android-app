package com.ondumusic.player.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ondumusic.player.api.SerialUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What was opened or played from search results, newest first. */
class SearchHistory(private val prefs: SharedPreferences) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<SearchHistoryItem>> = _items.asStateFlow()

    /** Moves an entry already in the history to the front rather than listing it twice. */
    fun add(item: SearchHistoryItem) {
        _items.value = (listOf(item) + _items.value.filterNot { it.sameEntry(item) }).take(MAX_ITEMS)
        save()
    }

    fun remove(item: SearchHistoryItem) {
        _items.value = _items.value.filterNot { it.sameEntry(item) }
        save()
    }

    fun clear() {
        _items.value = emptyList()
        save()
    }

    private fun load(): List<SearchHistoryItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun save() = prefs.edit { putString(KEY, json.encodeToString(serializer, _items.value)) }

    private companion object {
        const val KEY = "searchHistory"
        const val MAX_ITEMS = 20
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val serializer = ListSerializer(SearchHistoryItem.serializer())
    }
}

@Serializable
data class SearchHistoryItem(
    val id: SerialUuid,
    val kind: Kind,
    val name: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    /** The artist of an album or song, kept for navigating without a lookup. */
    val artistId: SerialUuid? = null,
    val albumId: SerialUuid? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("artist") Artist,
        @SerialName("album") Album,
        @SerialName("song") Song,
    }

    /** Ids are only unique within a table, so an entry is its id and kind together. */
    fun sameEntry(other: SearchHistoryItem) = id == other.id && kind == other.kind
}
