package com.ondumusic.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ArtistEntity::class, AlbumEntity::class, TrackEntity::class,
        PlaylistEntity::class, PlaylistItemEntity::class, PendingMutationEntity::class,
    ],
    version = 1,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun artists(): ArtistDao
    abstract fun albums(): AlbumDao
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun playlistItems(): PlaylistItemDao
    abstract fun pendingMutations(): PendingMutationDao

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                // Mostly a cache of the server, but not entirely: queued playlist edits and
                // download state live only here. Ship real migrations for schema changes; this
                // is the last resort, matching iOS, which re-syncs and recovers downloads from
                // the reconciliation manifest.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
