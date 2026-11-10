package app.rngdle.companion.reminder

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPolicyTest {
    @Test fun resetAlwaysUsesUtcEvenWhenTheInputUsesAnOffset() {
        val now = Instant.parse("2026-11-11T02:30:00+03:00")
        assertEquals(Instant.parse("2026-11-11T00:00:00Z"), ReminderPolicy.nextReset(now))
        assertEquals(Instant.parse("2026-11-12T00:00:00Z"), ReminderPolicy.nextReset(ReminderPolicy.nextReset(now)))
    }

    @Test fun advanceReminderMovesToTheFollowingDayOnceItsTimeHasPassed() {
        val now = Instant.parse("2026-11-10T23:58:00Z")
        val plan = ReminderPolicy.upcoming(now, ReminderSettings(enabled = true, advanceMinutes = 15), null)
        assertTrue(ReminderEvent(ReminderKind.RESET, Instant.parse("2026-11-11T00:00:00Z")) in plan)
        assertTrue(ReminderEvent(ReminderKind.ADVANCE, Instant.parse("2026-11-11T23:45:00Z")) in plan)
    }

    @Test fun repeatStopsOnObservedRollAndResumesOnTheNextUtcDay() {
        val now = Instant.parse("2026-11-10T22:30:00Z")
        val day = ReminderPolicy.day(now)
        val prefs = ReminderSettings(enabled = true, repeat = true)
        val repeat = ReminderEvent(ReminderKind.REPEAT, Instant.parse("2026-11-10T23:00:00Z"))
        assertTrue(repeat in ReminderPolicy.upcoming(now, prefs, null))
        assertFalse(repeat in ReminderPolicy.upcoming(now, prefs, day))
        assertFalse(ReminderPolicy.deliverable(repeat, repeat.at, day))
        assertFalse(ReminderPolicy.deliverable(repeat, ReminderPolicy.nextReset(now), null))
        assertTrue(ReminderPolicy.upcoming(ReminderPolicy.nextReset(now), prefs, day).any { it.kind == ReminderKind.REPEAT })
    }

    @Test fun countdownMustAgreeWithTheCurrentUtcBoundary() {
        val now = Instant.parse("2026-11-10T23:57:41Z")
        assertTrue(ReminderPolicy.matchesCountdown("0h 02m 19s", now))
        assertFalse(ReminderPolicy.matchesCountdown("24h 00m 00s", now))
        assertFalse(ReminderPolicy.matchesCountdown("0h 02m 99s", now))
        assertFalse(ReminderPolicy.matchesCountdown("0h 00m 00s", now))
    }

    @Test fun disabledRemindersScheduleNothing() {
        assertTrue(ReminderPolicy.upcoming(Instant.EPOCH, ReminderSettings(), null).isEmpty())
    }
}
