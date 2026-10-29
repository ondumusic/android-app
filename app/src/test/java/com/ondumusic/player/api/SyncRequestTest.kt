package com.ondumusic.player.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRequestTest {
    /**
     * Sent on every sync, first one included: the server records it in the cursor and
     * resends everything once the number changes, which only works if it was there to
     * compare against.
     */
    @Test
    fun everySyncSaysWhichSchemaItReads() {
        val query = ApiClient.syncQuery(null)
        assertEquals(ApiClient.SYNC_SCHEMA_VERSION.toString(), query["schemaVersion"])
        assertNull(query["cursor"])
    }

    @Test
    fun aDeltaSyncSendsTheCursorToo() {
        val query = ApiClient.syncQuery("v1-abc")
        assertEquals("v1-abc", query["cursor"])
        assertEquals(ApiClient.SYNC_SCHEMA_VERSION.toString(), query["schemaVersion"])
    }
}
