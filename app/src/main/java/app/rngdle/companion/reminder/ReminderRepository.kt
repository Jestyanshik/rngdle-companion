package app.rngdle.companion.reminder

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.reminderStore by preferencesDataStore("reminders")

class ReminderRepository(context: Context) {
    private val store = context.applicationContext.reminderStore
    private val enabled = booleanPreferencesKey("enabled")
    private val lead = intPreferencesKey("advance_minutes")
    private val repeat = booleanPreferencesKey("repeat")
    private val alarm = booleanPreferencesKey("alarm")
    private val sound = stringPreferencesKey("sound")
    private val revision = longPreferencesKey("revision")
    private val observedRollDay = longPreferencesKey("observed_roll_day")

    val settings = store.data.map(::settingsFrom)

    private fun settingsFrom(data: Preferences) = ReminderSettings(
        enabled = data[enabled] ?: false,
        advanceMinutes = data[lead] ?: 0,
        repeat = data[repeat] ?: false,
        alarmMode = data[alarm] ?: false,
        sound = data[sound],
        revision = data[revision] ?: 0,
    )

    suspend fun change(update: (ReminderSettings) -> ReminderSettings) {
        store.edit { data ->
            val old = settingsFrom(data)
            val new = update(old)
            if (old != new) {
                data[enabled] = new.enabled
                data[lead] = new.advanceMinutes
                data[repeat] = new.repeat
                data[alarm] = new.alarmMode
                if (new.sound == null) data.remove(sound) else data[sound] = new.sound
                data[revision] = old.revision + 1
            }
        }
    }

    suspend fun snapshot(): Pair<ReminderSettings, Long?> {
        val data = store.data.first()
        return settingsFrom(data) to data[observedRollDay]
    }

    suspend fun observeRoll(day: Long) {
        store.edit { if (it[observedRollDay] != day) it[observedRollDay] = day }
    }

    suspend fun invalidateSchedule() {
        store.edit { it[revision] = (it[revision] ?: 0) + 1 }
    }

    suspend fun claim(event: ReminderEvent, expectedRevision: Long): Boolean {
        val key = longPreferencesKey("delivered_${event.kind.name}")
        val timestamp = event.at.epochSecond
        var claimed = false
        store.edit { data ->
            if ((data[revision] ?: 0) != expectedRevision || data[enabled] != true) return@edit
            if (event.kind != ReminderKind.ADVANCE && data[observedRollDay] == ReminderPolicy.day(event.at)) return@edit
            if ((data[key] ?: Long.MIN_VALUE) >= timestamp) return@edit
            data[key] = timestamp
            claimed = true
        }
        return claimed
    }
}
