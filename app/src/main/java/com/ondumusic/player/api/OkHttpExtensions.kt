package com.ondumusic.player.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
}

/** `base` + "/a/b", keeping whatever path `base` already has (it ends in `/api`). */
fun HttpUrl.appendingPath(endpoint: String): HttpUrl =
    newBuilder().apply {
        // A trailing slash leaves an empty last segment, which addPathSegments would keep.
        if (pathSegments.lastOrNull() == "") removePathSegment(pathSize - 1)
        addPathSegments(endpoint.trimStart('/'))
    }.build()
