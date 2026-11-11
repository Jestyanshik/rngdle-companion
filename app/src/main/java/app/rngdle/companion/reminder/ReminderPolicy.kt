package app.rngdle.companion.reminder

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.random.Random

data class ReminderOptions(val count: Int = 3, val alarmMode: Boolean = false) {
    companion object {
        const val MAX_COUNT = 8
    }
}

internal data class DailySchedule(
    val count: Int,
    val seed: Long,
    val rolledDay: Long?,
    val deliveryDate: LocalDate?,
    val deliveredCount: Int,
    val lastDelivery: Instant? = null,
) {
    fun next(now: Instant, zone: ZoneId): Instant? {
        val today = now.atZone(zone).toLocalDate()
        return (0L..2L).asSequence()
            .flatMap { slots(today.plusDays(it), zone).asSequence() }
            .firstOrNull { it > now && eligible(it, zone) }
    }

    fun canDeliver(at: Instant, now: Instant, zone: ZoneId): Boolean =
        at <= now && now < at.plusSeconds(30 * 60) &&
            ReminderPolicy.day(at) == ReminderPolicy.day(now) &&
            at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate() &&
            contains(at, zone)

    fun contains(at: Instant, zone: ZoneId): Boolean =
        eligible(at, zone) && at in slots(at.atZone(zone).toLocalDate(), zone)

    private fun eligible(at: Instant, zone: ZoneId): Boolean =
        (rolledDay == null || ReminderPolicy.day(at) > rolledDay) &&
            (lastDelivery == null || at > lastDelivery) &&
            (deliveryDate != at.atZone(zone).toLocalDate() || deliveredCount < count)

    internal fun slots(date: LocalDate, zone: ZoneId): List<Instant> {
        if (count == 0) return emptyList()
        val start = date.atTime(8, 0).atZone(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val seconds = Duration.between(start, end).seconds
        if (seconds <= 0) return emptyList()

        val random = Random(seed xor date.toEpochDay())
        return List(count) { index ->
            val intervalStart = seconds * index / count
            val intervalEnd = seconds * (index + 1) / count
            val margin = (intervalEnd - intervalStart) / 4
            // Sampling the middle half keeps adjacent reminders from clustering.
            start.plusSeconds(random.nextLong(intervalStart + margin, intervalEnd - margin))
        }
    }
}

object ReminderPolicy {
    private val countdownFormat = Regex("([0-9]{1,2})h ([0-5][0-9])m ([0-5][0-9])s")

    fun day(now: Instant): Long = now.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()

    fun confirmedRollDay(value: String, now: Instant): Long? {
        val groups = countdownFormat.matchEntire(value)?.groupValues ?: return null
        val seconds = groups[1].toLong() * 3600 + groups[2].toLong() * 60 + groups[3].toLong()
        val day = day(now)
        val reset = Instant.ofEpochSecond((day + 1) * 86_400)
        val expected = Duration.between(now, reset).seconds
        return day.takeIf { seconds in 1..86_400 && abs(seconds - expected) <= 10 }
    }
}
