package app.rngdle.companion.reminder

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

enum class ReminderKind { ADVANCE, RESET, REPEAT }

data class ReminderSettings(
    val enabled: Boolean = false,
    val advanceMinutes: Int = 0,
    val repeat: Boolean = false,
    val alarmMode: Boolean = false,
    val sound: String? = null,
    val revision: Long = 0,
)

data class ReminderEvent(val kind: ReminderKind, val at: Instant)

object ReminderPolicy {
    private val countdownFormat = Regex("([0-9]{1,2})h ([0-5][0-9])m ([0-5][0-9])s")

    fun day(now: Instant): Long = now.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()

    private fun startOfDay(day: Long): Instant = Instant.ofEpochSecond(day * 86_400)

    fun nextReset(now: Instant): Instant = startOfDay(day(now) + 1)

    fun matchesCountdown(value: String, now: Instant): Boolean {
        val groups = countdownFormat.matchEntire(value)?.groupValues ?: return false
        val hours = groups[1].toInt()
        if (hours > 24) return false
        val seconds = hours * 3600L + groups[2].toInt() * 60 + groups[3].toInt()
        val expected = Duration.between(now, nextReset(now)).seconds
        return seconds in 1..86_400 && abs(seconds - expected) <= 10
    }

    fun upcoming(now: Instant, settings: ReminderSettings, observedRollDay: Long?): List<ReminderEvent> {
        if (!settings.enabled) return emptyList()
        val reset = nextReset(now)
        val today = day(now)
        return buildList {
            add(ReminderEvent(ReminderKind.RESET, reset))
            if (settings.advanceMinutes > 0) {
                val advance = Duration.ofMinutes(settings.advanceMinutes.toLong())
                val beforeReset = reset.minus(advance)
                add(ReminderEvent(ReminderKind.ADVANCE, if (beforeReset > now) beforeReset else reset.plusSeconds(86_400).minus(advance)))
            }
            if (settings.repeat && observedRollDay != today) {
                val nextHour = Instant.ofEpochSecond((now.epochSecond / 3600 + 1) * 3600)
                if (nextHour < reset) add(ReminderEvent(ReminderKind.REPEAT, nextHour))
            }
        }
    }

    fun deliverable(event: ReminderEvent, now: Instant, observedRollDay: Long?): Boolean {
        if (now < event.at || day(now) != day(event.at)) return false
        if (event.kind == ReminderKind.ADVANCE) return now < nextReset(event.at)
        return observedRollDay != day(now) && now < event.at.plusSeconds(3600)
    }
}
