package app.rngdle.companion.reminder

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.reminderStore by preferencesDataStore("reminders")

internal data class RollReminder(val resetAt: Instant, val alarmMode: Boolean)

class ReminderRepository(context: Context) {
    private val store = context.applicationContext.reminderStore
    private val alarm = booleanPreferencesKey("alarm")
    private val reset = longPreferencesKey("next_roll_at")
    private val delivered = longPreferencesKey("delivered_RESET")
    private val observedRollDay = longPreferencesKey("observed_roll_day")

    val alarmMode = store.data.map { it[alarm] ?: false }

    suspend fun setAlarmMode(enabled: Boolean) {
        store.edit { it[alarm] = enabled }
    }

    suspend fun recordReset(at: Instant): Boolean {
        var changed = false
        store.edit { data ->
            if (data[reset] == at.epochSecond) return@edit
            data[reset] = at.epochSecond
            changed = true
        }
        return changed
    }

    internal suspend fun pending(): RollReminder? = pending(store.data.first())

    internal suspend fun claim(at: Instant): RollReminder? {
        var claimed: RollReminder? = null
        store.edit { data ->
            val reminder = pending(data)?.takeIf { it.resetAt == at } ?: return@edit
            data[delivered] = at.epochSecond
            claimed = reminder
        }
        return claimed
    }

    private fun pending(data: Preferences): RollReminder? {
        // Older versions stored the UTC day of the last confirmed roll.
        val at = data[reset]?.let(Instant::ofEpochSecond)
            ?: data[observedRollDay]?.let(ReminderPolicy::resetAfterDay)
            ?: return null
        if ((data[delivered] ?: Long.MIN_VALUE) >= at.epochSecond) return null
        return RollReminder(at, data[alarm] ?: false)
    }
}
