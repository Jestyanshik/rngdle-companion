package app.rngdle.companion.reminder

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPolicyTest {
    @Test fun postRollCountdownConfirmsTheNextUtcMidnight() {
        val now = Instant.parse("2026-11-11T15:00:00+03:00")
        assertEquals(Instant.parse("2026-11-12T00:00:00Z"), ReminderPolicy.confirmedReset("12h 00m 00s", now))
    }

    @Test fun resetIsNotAFixedTwelveHoursFromTheRoll() {
        val now = Instant.parse("2026-11-11T20:30:00Z")
        assertNull(ReminderPolicy.confirmedReset("12h 00m 00s", now))
        assertEquals(Instant.parse("2026-11-12T00:00:00Z"), ReminderPolicy.confirmedReset("3h 29m 59s", now))
    }

    @Test fun invalidOrStaleCountdownCannotScheduleANotification() {
        val now = Instant.parse("2026-11-11T23:57:41Z")
        assertNull(ReminderPolicy.confirmedReset("", now))
        assertNull(ReminderPolicy.confirmedReset("24h 00m 00s", now))
        assertNull(ReminderPolicy.confirmedReset("0h 02m 99s", now))
        assertNull(ReminderPolicy.confirmedReset("0h 00m 00s", now))
    }

    @Test fun legacyRollDayPreservesItsResetInstant() {
        val roll = Instant.parse("2026-11-11T23:59:59Z")
        assertEquals(Instant.parse("2026-11-12T00:00:00Z"), ReminderPolicy.resetAfterDay(ReminderPolicy.day(roll)))
    }

    @Test fun reminderCannotFireEarlyAndExpiresAfterTheDeliveryWindow() {
        val reset = Instant.parse("2026-11-12T00:00:00Z")
        assertFalse(ReminderPolicy.canDeliver(reset, reset.minusMillis(1)))
        assertTrue(ReminderPolicy.canDeliver(reset, reset))
        assertTrue(ReminderPolicy.canDeliver(reset, reset.plusSeconds(600)))
        assertFalse(ReminderPolicy.canDeliver(reset, reset.plusSeconds(3600)))
    }
}
