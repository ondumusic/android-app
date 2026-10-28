package com.ondumusic.player.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerInfoTest {
    private fun decode(json: String) = ApiJson.decodeFromString(ServerInfo.serializer(), json)

    @Test fun readsThePresetsAServerLists() {
        val info = decode(
            """
            {"version":"1.0.0-rc.5","apiVersion":2,"defaultPreset":"default_ogg","presets":[
              {"id":"default_ogg","name":"Default (192kbps)","codec":"opus","container":"ogg",
               "mimetype":"audio/ogg","extension":"opus","bitrate":192},
              {"id":"mobile_ogg","name":"Mobile (96kbps)","codec":"opus","container":"ogg",
               "mimetype":"audio/ogg","extension":"opus","bitrate":96}]}
            """,
        )
        val list = info.presetList!!
        assertEquals("default_ogg", list.defaultId)
        assertEquals(listOf("default_ogg", "mobile_ogg"), list.presets.map { it.id })
    }

    /** Servers from before presets were listed only send the two version fields. */
    @Test fun anOlderServerStillDecodes() {
        val info = decode("""{"version":"1.0.0-rc.4","apiVersion":2}""")
        assertEquals(2, info.apiVersion)
        assertNull(info.presetList)
    }

    /** A default without presets to pick from is no list at all. */
    @Test fun anEmptyListCountsAsNone() {
        assertNull(decode("""{"version":"2.0.0","apiVersion":2,"defaultPreset":"hq","presets":[]}""").presetList)
    }
}
