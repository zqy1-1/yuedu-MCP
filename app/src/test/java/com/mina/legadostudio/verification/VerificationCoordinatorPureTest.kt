package com.mina.legadostudio.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationCoordinatorPureTest {
    private fun assertInRange(id: Int) {
        assertTrue("id=$id", id in 10_000 until 1_010_000)
        assertNotEquals(1237, id)
    }

    @Test fun notificationIdStaysInRangeForUuids() {
        repeat(500) { assertInRange(verificationNotificationId(java.util.UUID.randomUUID().toString())) }
    }

    @Test fun notificationIdHandlesNegativeAndExtremeHashCodes() {
        val negative = generateSequence(0) { it + 1 }.map { "s$it" }.first { it.hashCode() < 0 }
        assertInRange(verificationNotificationId(negative))
        assertInRange(verificationNotificationId(""))
        val minHash = "polygenelubricants"
        assertEquals(Int.MIN_VALUE, minHash.hashCode())
        assertInRange(verificationNotificationId(minHash))
    }

    @Test fun notificationIdIsStableForSameSession() {
        val id = "3f1c2a9e-0000-4000-8000-123456789abc"
        assertEquals(verificationNotificationId(id), verificationNotificationId(id))
    }

    @Test fun completedSessionIsSkipped() {
        assertTrue(shouldSkipComplete("COMPLETED"))
        assertFalse(shouldSkipComplete("WAITING"))
        assertFalse(shouldSkipComplete(""))
    }
}
