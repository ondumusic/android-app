package com.ondumusic.player.auth

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalNetworkTest {
    private fun local(address: String) = LocalNetwork.isLocal(InetAddress.getByName(address))

    @Test
    fun homeNetworkAddressesAreLocal() {
        assertTrue(local("192.168.1.10"))
        assertTrue(local("10.0.0.5"))
        assertTrue(local("172.16.0.1"))
        assertTrue(local("172.31.255.254"))
        assertTrue(local("169.254.10.10"))
        assertTrue(local("fe80::1"))
        assertTrue(local("fd12:3456::1"))
    }

    @Test
    fun internetAddressesAreNot() {
        assertFalse(local("93.184.216.34"))
        assertFalse(local("172.32.0.1"))
        assertFalse(local("2606:4700::1111"))
        assertFalse(local("127.0.0.1"))
    }

    /** Tailscale's range: reached through a VPN, which never needs the permission. */
    @Test
    fun carrierGradeNatIsNot() = assertFalse(local("100.101.102.103"))

    @Test
    fun anAddressTypedAsTheServerIsCheckedDirectly() = runTest {
        assertTrue(LocalNetwork.isLocal("https://192.168.1.10:4000/api".toHttpUrl()))
        assertFalse(LocalNetwork.isLocal("https://93.184.216.34/api".toHttpUrl()))
    }

    @Test
    fun anMdnsNameIsLocalWithoutLookingItUp() = runTest {
        assertTrue(LocalNetwork.isLocal("https://ondu.local/api".toHttpUrl()))
    }
}
