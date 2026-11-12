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
import android.os.Build
import android.util.Log
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
import java.io.IOException
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
    private val sound = ReminderSound(context)

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

    suspend fun postTestNotification(options: ReminderOptions): Boolean = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            val manager = context.getSystemService(NotificationManager::class.java)
            notificationsAllowed(manager) && post(manager, options, test = true)
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
                if (reminder.options.alarmMode) {
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
        val reminder = repository.consume(at, now, zone, notificationsAllowed(manager)) ?: return
        post(manager, reminder.options)
    }

    private fun notificationsAllowed(manager: NotificationManager): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED)

    private fun post(manager: NotificationManager, options: ReminderOptions, test: Boolean = false): Boolean {
        val alarm = options.alarmMode
        val channel = reminderChannel(manager, alarm)
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        val builder = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Пора прокрутить RNGdle")
            .setContentText("Откройте игру, чтобы сделать дневной ролл.")
            .setContentIntent(openApp())
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
        if (!alarm && channel.sound == sound.notificationUri) {
            try {
                sound.prepareNotification(options.notificationVolume)
            } catch (_: IOException) {
                Log.w("RNGdleReminders", "notification_sound_unavailable")
                builder.setSilent(true)
            }
        }
        if (test) builder.setSubText("Проверка").setTimeoutAfter(60_000)
        manager.notify(if (test) TEST_NOTIFICATION_ID else NOTIFICATION_ID, builder.build())
        return true
    }

    private fun reminderChannel(manager: NotificationManager, alarm: Boolean): NotificationChannel {
        val id = if (alarm) "roll_alarm_v2" else "roll_ready_v3"
        manager.getNotificationChannel(id)?.let { return it }
        val previous = if (alarm) null else manager.getNotificationChannel("roll_ready_v2")
        val channel = NotificationChannel(
            id,
            if (alarm) "Будильник RNGdle" else "Ролл доступен",
            previous?.importance ?: NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            val uri = when {
                alarm -> sound.alarmUri
                previous != null && previous.sound == null -> null
                else -> sound.notificationUri
            }
            setSound(
                uri,
                AudioAttributes.Builder()
                    .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            previous?.let {
                setShowBadge(it.canShowBadge())
                enableLights(it.shouldShowLights())
                lightColor = it.lightColor
                vibrationPattern = it.vibrationPattern
                enableVibration(it.shouldVibrate())
            }
        }
        manager.createNotificationChannel(channel)
        return manager.getNotificationChannel(id) ?: channel
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
        private const val TEST_NOTIFICATION_ID = 28
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
