package io.nekohasekai.sfa.compose.screen.smart

import io.nekohasekai.sfa.constant.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPolicyStateTest {
    @Test
    fun hashesExactUtf8BytesWithoutNormalizingPolicy() {
        assertEquals("", smartPolicyHash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", smartPolicyHash("abc"))
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", smartPolicyHash("{}"))
        assertNotEquals(smartPolicyHash("{}"), smartPolicyHash(" {}"))
        assertNotEquals(smartPolicyHash("é"), smartPolicyHash("e\u0301"))
    }

    @Test
    fun lifecycleAndIncompleteSnapshotsNeverClaimApplied() {
        assertEquals(SmartPolicyApplyState.NextStart, state(Status.Stopped, null))
        assertEquals(SmartPolicyApplyState.Unknown, state(Status.Starting, emptyList()))
        assertEquals(SmartPolicyApplyState.Unknown, state(Status.Stopping, emptyList()))
        assertEquals(SmartPolicyApplyState.Unknown, state(Status.Started, null))
        assertEquals(SmartPolicyApplyState.NotUsed, state(Status.Started, emptyList()))
    }

    @Test
    fun everyGroupMustMatchIncludingLegacyGroupsAndEmptyOverride() {
        val applied = group(smartPolicyHash("abc"))
        val mismatch = group("different")
        assertEquals(SmartPolicyApplyState.Applied, state(Status.Started, listOf(applied, applied)))
        assertEquals(SmartPolicyApplyState.Pending, state(Status.Started, listOf(applied, mismatch)))
        assertEquals(SmartPolicyApplyState.Pending, state(Status.Started, listOf(mismatch, applied)))
        assertEquals(SmartPolicyApplyState.Pending, state(Status.Started, listOf(applied, group(null))))
        assertEquals(SmartPolicyApplyState.Applied, smartPolicyApplyState(Status.Started, "", listOf(group(""), group(null))))
        assertEquals(SmartPolicyApplyState.Pending, smartPolicyApplyState(Status.Started, "", listOf(group("nonempty"))))
    }

    @Test
    fun unknownBeforePendingPreservesOneNotificationAllowance() {
        val tracker = SmartPolicyReconcileTracker()
        assertFalse(tracker.shouldNotify(1, Status.Starting, "abc", null))
        assertFalse(tracker.shouldNotify(1, Status.Started, "abc", null))
        assertTrue(tracker.shouldNotify(1, Status.Started, "abc", listOf(group(""))))
        assertFalse(tracker.shouldNotify(1, Status.Started, "abc", null))
        assertFalse(tracker.shouldNotify(1, Status.Started, "abc", listOf(group(""))))
    }

    @Test
    fun firstAppliedOrNotUsedResultConsumesAllowanceWithoutNotification() {
        val tracker = SmartPolicyReconcileTracker()
        assertFalse(tracker.shouldNotify(1, Status.Started, "abc", listOf(group(smartPolicyHash("abc")))))
        assertFalse(tracker.shouldNotify(1, Status.Started, "abc", listOf(group(""))))
        assertFalse(tracker.shouldNotify(1, Status.Started, "other", emptyList()))
        assertFalse(tracker.shouldNotify(1, Status.Started, "other", listOf(group(""))))
    }

    @Test
    fun differentStringsEachGetAllowanceButReturningToSameStringDoesNot() {
        val tracker = SmartPolicyReconcileTracker()
        val snapshot = listOf(group("different"))
        assertTrue(tracker.shouldNotify(1, Status.Started, "abc", snapshot))
        assertTrue(tracker.shouldNotify(1, Status.Started, "other", snapshot))
        assertFalse(tracker.shouldNotify(1, Status.Started, "abc", snapshot))
        assertTrue(tracker.shouldNotify(1, Status.Started, "", snapshot))
        assertFalse(tracker.shouldNotify(1, Status.Started, "", snapshot))
    }

    @Test
    fun restartRestoresAllowanceForSameStoredString() {
        val tracker = SmartPolicyReconcileTracker()
        val snapshot = listOf(group(""))
        assertTrue(tracker.shouldNotify(1, Status.Started, "abc", snapshot))
        assertFalse(tracker.shouldNotify(1, Status.Stopped, "abc", null))
        assertFalse(tracker.shouldNotify(2, Status.Starting, "abc", null))
        assertFalse(tracker.shouldNotify(2, Status.Started, "abc", null))
        assertTrue(tracker.shouldNotify(2, Status.Started, "abc", snapshot))
        assertFalse(tracker.shouldNotify(2, Status.Started, "abc", snapshot))
    }

    @Test
    fun explicitSaveConsumesOnlyItsStringAndInstance() {
        val tracker = SmartPolicyReconcileTracker()
        val snapshot = listOf(group("different"))
        tracker.markSaved(1, "abc")
        assertFalse(tracker.shouldNotify(1, Status.Started, "abc", null))
        assertFalse(tracker.shouldNotify(1, Status.Started, "abc", snapshot))
        assertTrue(tracker.shouldNotify(1, Status.Started, "other", snapshot))
        assertTrue(tracker.shouldNotify(2, Status.Started, "abc", snapshot))
        tracker.markSaved(3, "other")
        assertFalse(tracker.shouldNotify(3, Status.Started, "other", snapshot))
        assertTrue(tracker.shouldNotify(3, Status.Started, "abc", snapshot))
    }

    private fun state(status: Status, snapshot: List<SmartStatus>?) = smartPolicyApplyState(status, "abc", snapshot)

    private fun group(hash: String?): SmartStatus {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/smart/status.json"))
            .bufferedReader().use { it.readText() }
        return SmartJson.status(fixture).copy(
            preference = hash?.let { SmartPreference("profile", "phone", false, "override", it, kotlinx.serialization.json.JsonObject(emptyMap())) },
        )
    }
}
