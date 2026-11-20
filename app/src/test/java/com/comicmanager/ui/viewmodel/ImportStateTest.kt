package com.comicmanager.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ImportStateTest {
    @Test
    fun dismissingAnOldBatchCannotEraseANewerBatchProgress() {
        val running = ImportState(
            batchId = 2, isImporting = true, phase = ImportPhase.EXTRACTING,
            completed = 3, total = 8, currentName = "current.zip", extractedImages = 35
        )
        assertSame(running, running.acknowledgeMessage(1))
        assertSame(running, running.acknowledgeMessage(2))
    }

    @Test
    fun dismissingCompletionOnlyClearsTheMessageAndPreservesTheOutcome() {
        val completed = ImportState(
            batchId = 8, phase = ImportPhase.COMPLETED, completed = 5, total = 5,
            successCount = 2, skippedCount = 2, failedCount = 1, importMessage = "Completed with errors"
        )
        val dismissed = completed.acknowledgeMessage(8)
        assertNull(dismissed.importMessage)
        assertEquals(completed.copy(importMessage = null), dismissed)
    }

    @Test
    fun equalTextFromANewerBatchStillRequiresItsOwnAcknowledgement() {
        val newer = ImportState(batchId = 9, importMessage = "Completed")
        assertSame(newer, newer.acknowledgeMessage(8))
        assertNull(newer.acknowledgeMessage(9).importMessage)
    }
}
