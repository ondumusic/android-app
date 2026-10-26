package com.ondumusic.player.api

import com.ondumusic.player.R
import com.ondumusic.player.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMessagesTest {
    private val blocked = UiText.Res(R.string.error_local_network_blocked)

    /** Android lets a blocked local connection time out, like a server that's down. */
    @Test
    fun aBlockedLocalNetworkIsNamedAsSuch() {
        val timeout = SocketTimeoutException("connect timed out")
        assertEquals(blocked, ErrorMessages.login(timeout, localNetworkBlocked = true))
        assertEquals(blocked, ErrorMessages.sync(timeout, localNetworkBlocked = true))
        assertEquals(blocked, ErrorMessages.download(ConnectException(), localNetworkBlocked = true))
    }

    @Test
    fun anUnreachableServerStillSaysSoWhenAccessIsntTheProblem() =
        assertEquals(UiText.Res(R.string.error_cannot_reach_server), ErrorMessages.login(SocketTimeoutException(), localNetworkBlocked = false))

    @Test
    fun otherFailuresKeepTheirOwnMessage() {
        assertEquals(UiText.Res(R.string.error_cannot_find_host), ErrorMessages.sync(UnknownHostException(), localNetworkBlocked = true))
        assertEquals(UiText.Res(R.string.error_wrong_credentials), ErrorMessages.login(LoginRejection.InvalidCredentials(), localNetworkBlocked = true))
    }
}
