package app.rngdle.companion.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.rngdle.companion.MainActivity
import app.rngdle.companion.R
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReminderScheduler(private val context: Context) {
    private val repository = ReminderRepository(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun hasExactAccess(): Boolean =
        Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    suspend fun schedule() = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            val (settings, rolled) = repository.snapshot()
            val now = Instant.now()
            val events = ReminderPolicy.upcoming(now, settings, rolled)
            val exactReset = events.firstOrNull { it.kind == ReminderKind.RESET }
                ?.takeIf { settings.alarmMode && hasExactAccess() }
            val exactScheduled = if (exactReset != null) {
                try {
                    alarms.setAlarmClock(
                        AlarmManager.AlarmClockInfo(exactReset.at.toEpochMilli(), openApp()),
                        alarmIntent(exactReset, settings.revision),
                    )
                    true
                } catch (_: SecurityException) {
                    false
                }
            } else {
                alarms.cancel(alarmIntent(null, 0))
                false
            }
            val work = WorkManager.getInstance(context)
            for (event in events) {
                val delay = Duration.between(now, event.at).toMillis() +
                    if (exactScheduled && event == exactReset) 15 * 60_000 else 0
                val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setInputData(
                        Data.Builder().putString(KIND, event.kind.name)
                            .putLong(TIME, event.at.epochSecond)
                            .putLong(REVISION, settings.revision).build()
                    )
                    .build()
                val name = "reminder-${event.kind}-${event.at.epochSecond}-${settings.revision}"
                work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get()
            }
        }
    }

    suspend fun deliver(event: ReminderEvent, revision: Long) {
        val (settings, rolled) = repository.snapshot()
        val now = Instant.now()
        val manager = context.getSystemService(NotificationManager::class.java)
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (settings.revision == revision && permitted && manager.areNotificationsEnabled() &&
            ReminderPolicy.deliverable(event, now, rolled) && repository.claim(event, revision)
        ) {
            post(manager, event.kind, settings)
        }
        schedule()
    }

    private fun post(manager: NotificationManager, kind: ReminderKind, settings: ReminderSettings) {
        val alarm = settings.alarmMode && kind == ReminderKind.RESET
        val usage = if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
        val channelId = "roll-$usage-${settings.sound.orEmpty().hashCode().toUInt().toString(16)}"
        val resourceName = context.resources.getResourceEntryName(R.raw.roll_alert)
        val defaultSound = Uri.parse("android.resource://${context.packageName}/raw/$resourceName")
        val soundUri = settings.sound?.let(Uri::parse) ?: defaultSound
        val audio = AudioAttributes.Builder().setUsage(usage).build()
        val channel = NotificationChannel(
            channelId,
            if (alarm) "Будильник RNGdle" else "Напоминания RNGdle",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(soundUri, audio)
        }
        val activeChannel = try {
            manager.createNotificationChannel(channel)
            channelId
        } catch (_: SecurityException) {
            val fallback = NotificationChannel(
                "roll-$usage-default", channel.name, NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(defaultSound, audio) }
            manager.createNotificationChannel(fallback)
            fallback.id
        }
        val message = when (kind) {
            ReminderKind.ADVANCE -> "Следующий сброс RNGdle в 00:00 UTC."
            ReminderKind.RESET -> "Начался новый день RNGdle. Откройте игру, чтобы сделать ролл."
            ReminderKind.REPEAT -> "Откройте RNGdle, если ещё не сделали ролл за сегодня."
        }
        val notification = NotificationCompat.Builder(context, activeChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("RNGdle")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp())
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun clearNotification() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarmIntent(event: ReminderEvent?, revision: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 2, Intent(context, ReminderReceiver::class.java).setAction(ALARM_ACTION)
            .putExtra(KIND, event?.kind?.name).putExtra(TIME, event?.at?.epochSecond ?: 0)
            .putExtra(REVISION, revision),
        PendingIntent.FLAG_IMMUTABLE or if (event == null) 0 else PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        internal const val ALARM_ACTION = "app.rngdle.companion.RESET_ALARM"
        internal const val KIND = "kind"
        internal const val TIME = "time"
        internal const val REVISION = "revision"
        internal const val CLOCK_CHANGED = "clock_changed"
        private const val NOTIFICATION_ID = 27
        private val scheduleLock = Mutex()

        internal fun event(kind: String?, time: Long): ReminderEvent? {
            val parsed = ReminderKind.entries.firstOrNull { it.name == kind } ?: return null
            return ReminderEvent(parsed, Instant.ofEpochSecond(time))
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val scheduler = ReminderScheduler(applicationContext)
            val event = ReminderScheduler.event(
                inputData.getString(ReminderScheduler.KIND), inputData.getLong(ReminderScheduler.TIME, 0),
            )
            if (event == null) {
                if (inputData.getBoolean(ReminderScheduler.CLOCK_CHANGED, false)) {
                    ReminderRepository(applicationContext).invalidateSchedule()
                }
                scheduler.schedule()
            } else {
                if (event.at > Instant.now()) return Result.retry()
                scheduler.deliver(event, inputData.getLong(ReminderScheduler.REVISION, -1))
            }
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Result.retry()
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = ReminderScheduler.event(
            intent.getStringExtra(ReminderScheduler.KIND), intent.getLongExtra(ReminderScheduler.TIME, 0),
        )
        if (intent.action != ReminderScheduler.ALARM_ACTION || event == null) {
            val clockChanged = intent.action == Intent.ACTION_TIME_CHANGED ||
                intent.action == Intent.ACTION_TIMEZONE_CHANGED
            val work = OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(
                Data.Builder().putBoolean(ReminderScheduler.CLOCK_CHANGED, clockChanged).build(),
            ).build()
            WorkManager.getInstance(context).enqueue(work)
            return
        }
        val pending = goAsync()
        val scheduler = ReminderScheduler(context.applicationContext)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                scheduler.deliver(event, intent.getLongExtra(ReminderScheduler.REVISION, -1))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // The existing WorkManager job can retry delivery.
            } finally {
                pending.finish()
            }
        }
    }
}
