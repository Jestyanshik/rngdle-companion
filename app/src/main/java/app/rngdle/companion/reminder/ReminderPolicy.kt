package app.rngdle.companion.reminder

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

object ReminderPolicy {
    private val countdownFormat = Regex("([0-9]{1,2})h ([0-5][0-9])m ([0-5][0-9])s")
    private val deliveryWindow = Duration.ofHours(1)

    fun day(now: Instant): Long = now.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()

    internal fun resetAfterDay(day: Long): Instant = Instant.ofEpochSecond((day + 1) * 86_400)

    fun confirmedReset(value: String, now: Instant): Instant? {
        val groups = countdownFormat.matchEntire(value)?.groupValues ?: return null
        val seconds = groups[1].toLong() * 3600 + groups[2].toLong() * 60 + groups[3].toLong()
        val reset = resetAfterDay(day(now))
        val expected = Duration.between(now, reset).seconds
        return reset.takeIf { seconds in 1..86_400 && abs(seconds - expected) <= 10 }
    }

    fun isCurrent(reset: Instant, now: Instant): Boolean = now < reset.plus(deliveryWindow)

    fun canDeliver(reset: Instant, now: Instant): Boolean = now >= reset && isCurrent(reset, now)
}
