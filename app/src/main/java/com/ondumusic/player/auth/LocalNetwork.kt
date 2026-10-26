package com.ondumusic.player.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.net.Inet6Address
import java.net.InetAddress

/**
 * From Android 17, an app needs the user's permission to reach the local network, and
 * without it a connection there just times out. Only a server on the home network needs
 * it, so it's asked for when the server's address is local, and only then.
 */
object LocalNetwork {
    /** Only ever checked or asked for once [applies]; older systems never see it. */
    @SuppressLint("InlinedApi")
    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** Before Android 17 the internet permission covers the local network too. */
    val applies: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(context: Context): Boolean =
        !applies || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Whether reaching [url] takes the permission that [context] doesn't have yet. */
    suspend fun needsPermission(context: Context, url: HttpUrl): Boolean = !isGranted(context) && isLocal(url)

    /**
     * Where the name resolves to now: a server reached by the same name at home and away
     * is only local while at home. A name that won't resolve counts as not local, and
     * signing in reports it as usual.
     */
    suspend fun isLocal(url: HttpUrl): Boolean {
        // Resolving an mDNS name is itself local traffic, so it would fail without the permission.
        if (url.host.endsWith(".local", ignoreCase = true)) return true
        val addresses = withContext(Dispatchers.IO) { runCatching { InetAddress.getAllByName(url.host) }.getOrNull() }
        return addresses.orEmpty().any(::isLocal)
    }

    /**
     * Android's private, link-local and multicast ranges. Its list also has carrier-grade
     * NAT (100.64.0.0/10), left out here: that's the range Tailscale hands out, and traffic
     * through a VPN never needs the permission.
     */
    fun isLocal(address: InetAddress): Boolean = when {
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress -> true
        // Unique local addresses, fc00::/7.
        address is Inet6Address -> address.address[0].toInt() and 0xfe == 0xfc
        else -> false
    }
}
