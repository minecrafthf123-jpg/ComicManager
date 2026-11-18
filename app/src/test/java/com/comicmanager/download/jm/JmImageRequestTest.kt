package com.comicmanager.download.jm

import kotlinx.coroutines.*
import okhttp3.*
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class JmImageRequestTest {
    @Test fun cancellationCancelsActualNetworkCallBeforeAnyResponseArrives() = runBlocking {
        val call = PendingCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitImageBytes() }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        // A late failure after cancellation must be harmless.
        call.callback!!.onFailure(call, IOException("Canceled"))
    }

    @Test fun failedNetworkCallPropagatesFailure() = runBlocking {
        val call = PendingCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { call.awaitImageBytes() } }
        call.callback!!.onFailure(call, IOException("Network unavailable"))
        assertEquals("Network unavailable", result.await().exceptionOrNull()?.message)
    }

    private class PendingCall : Call {
        var callback: Callback? = null
        private var cancelled = false
        override fun request() = Request.Builder().url("https://example.com/image.jpg").build()
        override fun execute(): Response = error("Must use cancellable asynchronous requests")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = PendingCall()
    }
}
