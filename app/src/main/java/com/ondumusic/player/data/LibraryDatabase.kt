package com.ondumusic.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ArtistEntity::class, AlbumEntity::class, TrackEntity::class,
        PlaylistEntity::class, PlaylistItemEntity::class, PendingMutationEntity::class,
        DownloadQueueEntity::class,
    ],
    version = 2,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun artists(): ArtistDao
    abstract fun albums(): AlbumDao
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun playlistItems(): PlaylistItemDao
    abstract fun pendingMutations(): PendingMutationDao
    abstract fun downloadQueue(): DownloadQueueDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_queue` (`trackId` TEXT NOT NULL, `enqueuedAt` INTEGER NOT NULL, " +
                        "`paused` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`trackId`))"
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)

        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                .addMigrations(*MIGRATIONS)
                // Mostly a cache of the server, but not entirely: queued playlist edits and
                // download state live only here. Ship real migrations for schema changes; this
                // is the last resort, matching iOS, which re-syncs and recovers downloads from
                // the files still on disk.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
