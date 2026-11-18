package com.comicmanager.download.jm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation closes the actual socket, including while response bytes are being read. */
internal suspend fun Call.awaitImageBytes(): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val bytes = response.use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body?.bytes()?.takeIf { body -> body.isNotEmpty() }
                        ?: throw IOException("Empty image response")
                }
                if (continuation.isActive) continuation.resume(bytes)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
