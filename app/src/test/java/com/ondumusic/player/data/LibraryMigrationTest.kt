package com.ondumusic.player.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * Builds a version-1 database from its exported schema, fills it, and opens it with the
 * current Room database and only the shipped migrations — no destructive fallback, so a
 * missing or wrong migration fails here, and Room checks the migrated schema on open.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun createVersion(version: Int): SQLiteDatabase {
        val schema = Json.parseToJsonElement(File("schemas/${LibraryDatabase::class.qualifiedName}/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach {
                db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    @Test
    fun migrates1To2KeepingEverything() = runTest {
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val trackId = UUID.randomUUID()
        val playlistId = UUID.randomUUID()
        createVersion(1).apply {
            execSQL("INSERT INTO artists (id, name, sortName, slug, albumCount, insertedAt, artwork) VALUES ('$artistId', 'A', 'A', 'a', 1, 0, '[]')")
            execSQL("INSERT INTO albums (id, artistId, name, sortName, slug, artwork, isUnknown, trackCount, insertedAt) " +
                "VALUES ('$albumId', '$artistId', 'B', 'B', 'b', '[]', 0, 1, 0)")
            execSQL("INSERT INTO tracks (id, artistId, albumArtistId, albumId, name, disc, duration, mimetype, streamUrl, downloadUrl, " +
                "insertedAt, playCount, isDownloaded, downloadedFileSize, localFilePath) VALUES ('$trackId', '$artistId', '$artistId', " +
                "'$albumId', 'T', 1, 1.0, 'audio/flac', '/s', '/d', 0, 3, 1, 1234, 'Downloads/x.flac')")
            execSQL("INSERT INTO playlists (id, name, trackCount, insertedAt, isFolder, autoDownload) VALUES ('$playlistId', 'P', 0, 0, 0, 1)")
            execSQL("INSERT INTO pending_mutations (localId, kind, payload, createdAt, attemptCount) VALUES ('${UUID.randomUUID()}', 'k', '{}', 0, 0)")
            close()
        }

        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(*LibraryDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val track = db.tracks().get(trackId)!!.track
            assertTrue(track.isDownloaded)
            assertEquals(1234L, track.downloadedFileSize)
            assertEquals("Downloads/x.flac", track.localFilePath)
            assertEquals(3, track.playCount)
            assertTrue(db.playlists().get(playlistId)!!.autoDownload)
            assertEquals(1, db.pendingMutations().all().size)

            db.downloadQueue().insert(listOf(DownloadQueueEntity(trackId, 5)))
            assertEquals(listOf(DownloadQueueEntity(trackId, 5, paused = false)), db.downloadQueue().all())
        } finally {
            db.close()
        }
    }
}
