package app.rngdle.companion.reminder

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPolicyTest {
    private val date = LocalDate.of(2026, 11, 11)
    private val moscow = ZoneId.of("Europe/Moscow")
    private val schedule = DailySchedule(count = 8, seed = 42, rolledDay = null, deliveryDate = null, deliveredCount = 0)

    @Test fun countIsSpreadAcrossTheLocalWindowAndStableForEachDate() {
        val slots = schedule.slots(date, moscow)
        val start = date.atStartOfDay(moscow).toInstant()
        assertEquals(8, slots.size)
        slots.forEachIndexed { index, at ->
            val fromStart = Duration.between(start, at).seconds
            assertTrue(fromStart in (index * 10800L + 2700)..(index * 10800L + 8099))
        }
        assertTrue(slots.zipWithNext().all { (first, second) -> Duration.between(first, second).toHours() >= 1 })
        assertEquals(slots, schedule.slots(date, moscow))
    }

    @Test fun daylightSavingChangeStillSpreadsRemindersAcrossTheWholeLocalDay() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8)
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val slots = schedule.slots(date, zone)
        assertEquals(23, Duration.between(start, end).toHours())
        assertEquals(schedule.count, slots.size)
        assertTrue(slots.all { it >= start && it < end })
        assertEquals(slots.sorted(), slots)
    }

    @Test fun completedRollSuppressesSlotsUntilTheNextUtcDay() {
        val zone = ZoneOffset.ofHours(14)
        val rollTime = date.atTime(8, 30).atZone(zone).toInstant()
        val reset = date.atTime(14, 0).atZone(zone).toInstant()
        val afterRoll = schedule.copy(rolledDay = ReminderPolicy.day(rollTime))
        val beforeReset = schedule.slots(date, zone).first()
        assertTrue(beforeReset < reset)
        assertFalse(afterRoll.canDeliver(beforeReset, beforeReset, zone))
        assertEquals(schedule.slots(date, zone).first { it >= reset }, afterRoll.next(rollTime, zone))
    }

    @Test fun pastSlotsAreSkippedAndDeliveredSlotsCannotBeReused() {
        val slots = schedule.slots(date, moscow)
        val first = slots.first()
        assertEquals(slots[1], schedule.next(first, moscow))
        val delivered = schedule.copy(deliveryDate = date, deliveredCount = 1, lastDelivery = first)
        assertFalse(delivered.canDeliver(first, first, moscow))
        assertEquals(slots[1], delivered.next(first.minusSeconds(600), moscow))
    }

    @Test fun changingCountStillRespectsHowManyWereDeliveredToday() {
        val now = date.atTime(8, 0).atZone(moscow).toInstant()
        val limited = schedule.copy(count = 2, deliveryDate = date, deliveredCount = 3)
        assertEquals(limited.slots(date.plusDays(1), moscow).first(), limited.next(now, moscow))
        assertNull(limited.copy(count = 0).next(now, moscow))
    }

    @Test fun staleOrEarlyDeliveriesAreRejected() {
        val first = schedule.slots(date, moscow).first()
        assertFalse(schedule.canDeliver(first, first.minusMillis(1), moscow))
        assertTrue(schedule.canDeliver(first, first.plusSeconds(600), moscow))
        assertFalse(schedule.canDeliver(first, first.plusSeconds(1800), moscow))
    }

    @Test fun onlyTheSitesPostRollCountdownConfirmsTheUtcRollDay() {
        val now = Instant.parse("2026-11-11T20:30:00Z")
        assertEquals(ReminderPolicy.day(now), ReminderPolicy.confirmedRollDay("3h 29m 59s", now))
        assertNull(ReminderPolicy.confirmedRollDay("12h 00m 00s", now))
        assertNull(ReminderPolicy.confirmedRollDay("0h 02m 99s", now))
        assertNull(ReminderPolicy.confirmedRollDay("0h 00m 00s", now))
    }
}
