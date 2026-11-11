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
import java.time.ZoneId
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
        scheduleLock.withLock { scheduleNext() }
    }

    suspend fun recordRoll(day: Long) = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            repository.recordRoll(day)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            scheduleNext()
        }
    }

    suspend fun deliver(at: Instant) = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            postIfDue(at, Instant.now(), ZoneId.systemDefault())
            scheduleNext()
        }
    }

    private suspend fun scheduleNext() {
        var now = Instant.now()
        val zone = ZoneId.systemDefault()
        var reminder = repository.plan(now, zone)
        if (reminder != null && reminder.at <= now) {
            postIfDue(reminder.at, now, zone)
            now = Instant.now()
            reminder = repository.plan(now, zone)
        }
        if (reminder == null) {
            alarmIntent(null)?.let(alarms::cancel)
            return
        }
        val trigger = reminder.at.toEpochMilli()
        val operation = checkNotNull(alarmIntent(reminder.at))
        val exactScheduled = if (hasExactAccess()) {
            try {
                if (reminder.alarmMode) {
                    alarms.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, openApp()), operation)
                } else {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
                }
                true
            } catch (_: SecurityException) {
                false
            }
        } else false
        if (!exactScheduled) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)

        // WorkManager covers the selected slot if Android removes its alarm.
        val delay = Duration.between(now, reminder.at.plusSeconds(600)).toMillis().coerceAtLeast(0)
        val backup = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(REMINDER_AT, reminder.at.epochSecond).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("daily-roll-${reminder.at.epochSecond}", ExistingWorkPolicy.KEEP, backup)
            .result.get()
    }

    private suspend fun postIfDue(at: Instant, now: Instant, zone: ZoneId) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val reminder = repository.consume(at, now, zone, permitted && manager.areNotificationsEnabled()) ?: return
        post(manager, reminder.alarmMode)
    }

    private fun post(manager: NotificationManager, alarm: Boolean) {
        val channelId = if (alarm) "roll_alarm_v2" else "roll_ready_v2"
        val sound = if (alarm) R.raw.roll_alarm else R.raw.roll_alert
        val soundName = context.resources.getResourceEntryName(sound)
        val channel = NotificationChannel(
            channelId,
            if (alarm) "Будильник RNGdle" else "Ролл доступен",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(
                Uri.parse("android.resource://${context.packageName}/raw/$soundName"),
                AudioAttributes.Builder()
                    .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Пора прокрутить RNGdle")
            .setContentText("Откройте игру, чтобы сделать дневной ролл.")
            .setContentIntent(openApp())
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarmIntent(at: Instant?): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ALARM_ACTION)
        if (at != null) intent.putExtra(REMINDER_AT, at.epochSecond)
        val flags = if (at == null) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or flags)
    }

    companion object {
        internal const val ALARM_ACTION = "app.rngdle.companion.RESET_ALARM"
        internal const val REMINDER_AT = "daily_reminder_at"
        private const val NOTIFICATION_ID = 27
        private val scheduleLock = Mutex()
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val scheduler = ReminderScheduler(applicationContext)
        val time = inputData.getLong(ReminderScheduler.REMINDER_AT, 0)
        if (time == 0L) {
            scheduler.schedule()
            Result.success()
        } else {
            val at = Instant.ofEpochSecond(time)
            if (at > Instant.now()) Result.retry()
            else {
                scheduler.deliver(at)
                Result.success()
            }
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.retry()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val time = intent.getLongExtra(ReminderScheduler.REMINDER_AT, 0)
        if (intent.action != ReminderScheduler.ALARM_ACTION || time == 0L) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReminderWorker>().build())
            return
        }
        val pending = goAsync()
        val scheduler = ReminderScheduler(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.deliver(Instant.ofEpochSecond(time))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // WorkManager provides another opportunity to deliver this reminder.
            } finally {
                pending.finish()
            }
        }
    }
}
