package com.comicmanager.download.jm

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class JmDownloadQueueTest {
    private fun album(id: String) = JmSearchItem(id, "Comic $id", "")
    private fun queueOf(vararg ids: String) = JmDownloadQueue(ids.map(::album))

    @Test fun normalizesAndDeduplicatesWithoutChangingSelectionOrder() {
        val result = normalizeJmDownloadQueue(listOf(album(" 002 "), album("1"), album("2"), album(""), album("invalid")))
        assertEquals(listOf("2", "1"), result.map { it.id })
    }

    @Test fun albumsCanBeAppendedAndDeduplicatedWhileTheFirstDownloadIsSuspended() = runBlocking {
        val queue = queueOf("1", "2")
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val worker = async {
            runJmDownloadQueue(queue, { false }, { item ->
                calls += item.id
                if (item.id == "1") {
                    firstStarted.complete(Unit)
                    finishFirst.await()
                }
                JmNativeDownloadResult(item.name, 6)
            }, {})
        }
        firstStarted.await()
        assertEquals(2, queue.append(listOf(album("3"), album("002"), album("1"), album("4"), album("03"))))
        assertEquals(listOf("2", "3", "4"), queue.waitingAfter(1).map { it.id })
        assertEquals(listOf("1"), calls)
        finishFirst.complete(Unit)
        val result = worker.await()
        assertEquals(listOf("1", "2", "3", "4"), calls)
        assertEquals(4, result.total)
        assertEquals(4, result.completed)
    }

    @Test fun oneFailureDoesNotBlockLaterAlbumsAndProgressCountsEachAlbumOnce() = runBlocking {
        val calls = mutableListOf<String>()
        val snapshots = mutableListOf<JmBatchProgress>()
        val result = runJmDownloadQueue(queueOf("1", "2", "3"), { false }, { item ->
            calls += item.id
            if (item.id == "2") error("Unavailable")
            JmNativeDownloadResult(item.name, 24)
        }, { snapshots += it })
        assertEquals(listOf("1", "2", "3"), calls)
        assertEquals(listOf("1", "3"), result.succeededItems.map { it.id })
        assertEquals("2", result.failures.single().item.id)
        assertEquals("Unavailable", result.failures.single().message)
        assertEquals(3, result.completed)
        assertEquals(0, result.cancelledCount)
        assertEquals(listOf(1, 2, 3), snapshots.filter { it.currentItem != null }.map { it.currentIndex }.distinct())
    }

    @Test fun nextAlbumStartsOnlyAfterCurrentAlbumHasFinished() = runBlocking {
        var active = 0
        var maximumActive = 0
        runJmDownloadQueue(queueOf("1", "2"), { false }, { item ->
            active++
            maximumActive = maxOf(maximumActive, active)
            yield()
            active--
            JmNativeDownloadResult(item.name, 12)
        }, {})
        assertEquals(1, maximumActive)
    }

    @Test fun cancellationRequestedDuringCommitKeepsCommittedAlbumAndStopsRemainingQueue() = runBlocking {
        var cancellationRequested = false
        val calls = mutableListOf<String>()
        val queue = queueOf("1")
        val result = runJmDownloadQueue(queue, { cancellationRequested }, { item ->
            calls += item.id
            // The real downloader finishes a NonCancellable database commit before returning.
            queue.append(listOf(album("2")))
            cancellationRequested = true
            JmNativeDownloadResult(item.name, 12)
        }, {})
        assertEquals(listOf("1"), calls)
        assertEquals(1, result.succeededItems.size)
        assertEquals(1, result.cancelledCount)
    }

    @Test fun cancellationDuringNetworkRequestStopsQueueAndPreservesEarlierFailures() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<JmBatchProgress>()
        val calls = mutableListOf<String>()
        val job = launch {
            runJmDownloadQueue(queueOf("1", "2", "3"), { false }, { item ->
                calls += item.id
                if (item.id == "1") error("Unavailable")
                waiting.complete(Unit)
                awaitCancellation()
            }, { snapshots += it })
        }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(listOf("1", "2"), calls)
        assertEquals(1, snapshots.last().failures.size)
        assertEquals(2, snapshots.last().cancelledCount)
    }

    @Test fun cancellationAfterLastCommitDoesNotReportFinishedAlbumsAsCancelled() = runBlocking {
        var cancellationRequested = false
        val result = runJmDownloadQueue(queueOf("1"), { cancellationRequested }, { item ->
            cancellationRequested = true
            JmNativeDownloadResult(item.name, 12)
        }, {})
        assertEquals(1, result.completed)
        assertEquals(0, result.cancelledCount)
        assertFalse(result.cancelled)
    }

    @Test fun retryQueueContainsOnlyFailedAlbums() = runBlocking {
        val result = runJmDownloadQueue(queueOf("1", "2"), { false }, { item ->
            if (item.id == "2") error("Unavailable")
            JmNativeDownloadResult(item.name, 12)
        }, {})
        val retryCalls = mutableListOf<String>()
        runJmDownloadQueue(JmDownloadQueue(result.failures.map { it.item }), { false }, { item ->
            retryCalls += item.id
            JmNativeDownloadResult(item.name, 12)
        }, {})
        assertEquals(listOf("2"), retryCalls)
    }

    @Test fun cancellationBeforeFirstAlbumDoesNotStartAnyNetworkWork() = runBlocking {
        val result = runJmDownloadQueue(queueOf("1", "2"), { true }, {
            error("No download may start after cancellation")
        }, {})
        assertEquals(0, result.completed)
        assertEquals(2, result.cancelledCount)
    }
}
