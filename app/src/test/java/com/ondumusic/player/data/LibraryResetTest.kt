package com.ondumusic.player.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryResetTest {
    @Test
    fun aDatabaseRoomCantMigrateReportsTheReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A newer schema than this build knows, as after installing an older build over a newer one.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("library.db").apply { parentFile?.mkdirs() }, null).use {
            it.version = 99
        }
        var reset = false

        val db = LibraryDatabase.create(context) { reset = true }
        db.openHelper.writableDatabase
        db.close()

        assertTrue(reset)
    }
}
