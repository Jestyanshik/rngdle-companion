package app.rngdle.companion.reminder

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.flow.map

private val Context.reminderStore by preferencesDataStore("reminders")

internal data class RollReminder(val at: Instant, val options: ReminderOptions)

class ReminderRepository(context: Context) {
    private val store = context.applicationContext.reminderStore
    private val alarm = booleanPreferencesKey("alarm")
    private val count = intPreferencesKey("daily_reminder_count")
    private val notificationVolume = intPreferencesKey("notification_volume_percent")
    private val seed = longPreferencesKey("daily_reminder_seed")
    private val pendingAt = longPreferencesKey("daily_reminder_pending_at")
    private val rolledDay = longPreferencesKey("observed_roll_day")
    private val legacyReset = longPreferencesKey("next_roll_at")
    private val deliveryDay = longPreferencesKey("daily_reminder_delivery_day")
    private val deliveredCount = intPreferencesKey("daily_reminder_delivered_count")
    private val lastDelivery = longPreferencesKey("daily_reminder_last_delivery")

    val options = store.data.map(::optionsFrom)

    suspend fun setAlarmMode(enabled: Boolean) {
        store.edit { it[alarm] = enabled }
    }

    suspend fun adjustCount(delta: Int) {
        store.edit { it[count] = (optionsFrom(it).count + delta).coerceIn(0, ReminderOptions.MAX_COUNT) }
    }

    suspend fun setNotificationVolume(percent: Int) {
        store.edit { it[notificationVolume] = percent.coerceIn(0, 100) }
    }

    internal suspend fun recordRoll(day: Long) {
        store.edit { if ((lastRollDay(it) ?: Long.MIN_VALUE) < day) it[rolledDay] = day }
    }

    internal suspend fun plan(now: Instant, zone: ZoneId): RollReminder? {
        var reminder: RollReminder? = null
        store.edit { data ->
            val options = optionsFrom(data)
            val randomSeed = data[seed] ?: Random.nextLong().also { data[seed] = it }
            val schedule = scheduleFrom(data, options, randomSeed)
            val pending = data[pendingAt]?.let(Instant::ofEpochSecond)
            val at = pending?.takeIf {
                if (it > now) schedule.contains(it, zone) else schedule.canDeliver(it, now, zone)
            } ?: schedule.next(now, zone)
            if (at == null) data.remove(pendingAt)
            else {
                data[pendingAt] = at.epochSecond
                reminder = RollReminder(at, options)
            }
        }
        return reminder
    }

    internal suspend fun consume(at: Instant, now: Instant, zone: ZoneId, permitted: Boolean): RollReminder? {
        var reminder: RollReminder? = null
        store.edit { data ->
            if (data[pendingAt] != at.epochSecond || at > now) return@edit
            data.remove(pendingAt)
            val randomSeed = data[seed] ?: return@edit
            val options = optionsFrom(data)
            if (!permitted || !scheduleFrom(data, options, randomSeed).canDeliver(at, now, zone)) return@edit

            val today = now.atZone(zone).toLocalDate().toEpochDay()
            val sent = if (data[deliveryDay] == today) data[deliveredCount] ?: 0 else 0
            data[deliveryDay] = today
            data[deliveredCount] = sent + 1
            data[lastDelivery] = at.epochSecond
            reminder = RollReminder(at, options)
        }
        return reminder
    }

    private fun optionsFrom(data: Preferences) = ReminderOptions(
        count = (data[count] ?: ReminderOptions().count).coerceIn(0, ReminderOptions.MAX_COUNT),
        alarmMode = data[alarm] ?: false,
        notificationVolume = (data[notificationVolume] ?: ReminderOptions.DEFAULT_NOTIFICATION_VOLUME).coerceIn(0, 100),
    )

    private fun lastRollDay(data: Preferences): Long? = listOfNotNull(
        data[rolledDay],
        // Version 0.1.3 stored the reset following the observed roll.
        data[legacyReset]?.let { ReminderPolicy.day(Instant.ofEpochSecond(it).minusSeconds(1)) },
    ).maxOrNull()

    private fun scheduleFrom(data: Preferences, options: ReminderOptions, randomSeed: Long) = DailySchedule(
        count = options.count,
        seed = randomSeed,
        rolledDay = lastRollDay(data),
        deliveryDate = data[deliveryDay]?.let(LocalDate::ofEpochDay),
        deliveredCount = data[deliveredCount] ?: 0,
        lastDelivery = data[lastDelivery]?.let(Instant::ofEpochSecond),
    )
}
