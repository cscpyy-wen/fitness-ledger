package com.personal.fitnessledger.ui

import com.personal.fitnessledger.data.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupAvailabilityTest {
    @Test
    fun idleLedgerAllowsExportAndRestore() {
        val state = AppUiState(isInitialized = true)

        assertFalse(backupWriteInProgress(state))
        assertNull(backupRestoreBlockReason(state))
    }

    @Test
    fun activeWorkoutCanBeExportedButMustBeResolvedBeforeRestore() {
        val state = AppUiState(
            isInitialized = true,
            activeWorkout = WorkoutSession(id = 8L, startedAtMillis = 100L, title = "腿部"),
        )

        assertFalse(backupWriteInProgress(state))
        assertEquals("请先完成或取消当前训练", backupRestoreBlockReason(state))
    }

    @Test
    fun inFlightWriteBlocksBothBackupActions() {
        val state = AppUiState(isInitialized = true, isCommittingMeal = true)

        assertTrue(backupWriteInProgress(state))
        assertEquals("请等待当前保存或识别任务完成", backupRestoreBlockReason(state))
    }

    @Test
    fun unresolvedRestoreTransactionBlocksEveryBackupActionWithoutPretendingToRun() {
        val state = AppUiState(isInitialized = true, restoreRecoveryRequired = true)

        assertTrue(backupWriteInProgress(state))
        assertEquals("请先完成未收敛的账本恢复", backupRestoreBlockReason(state))
        assertFalse(state.isImportingBackup)
    }

    @Test
    fun postRestoreSnapshotReadFailureKeepsLedgerClosed() {
        val pendingReload = AppUiState(
            isInitialized = false,
            restoreRecoveryRequired = true,
        )

        val failed = refreshFailureState(
            current = pendingReload,
            recoveryGateWasActive = true,
            error = IllegalStateException("injected restored-generation read failure"),
        )

        assertTrue(failed.isInitialized)
        assertTrue(failed.restoreRecoveryRequired)
        assertTrue(backupWriteInProgress(failed))
        assertNull(failed.message)
        assertTrue("recovery must invalidate off-screen plan edits", failed.planEditorEpoch != pendingReload.planEditorEpoch)
    }
}
