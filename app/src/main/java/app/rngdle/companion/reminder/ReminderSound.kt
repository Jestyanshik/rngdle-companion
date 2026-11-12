package app.rngdle.companion.reminder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import app.rngdle.companion.R
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReminderSoundProvider : FileProvider()

internal class ReminderSound(private val context: Context) {
    private val file: File get() = File(context.filesDir, "reminder-sound/roll_alert.wav")

    val notificationUri: Uri
        get() = FileProvider.getUriForFile(context, "${context.packageName}.reminder-sound", file)

    val alarmUri: Uri
        get() {
            val name = context.resources.getResourceEntryName(R.raw.roll_alarm)
            return Uri.parse("android.resource://${context.packageName}/raw/$name")
        }

    fun prepareNotification(volume: Int) {
        val source = context.resources.openRawResource(R.raw.roll_alert).use { it.readBytes() }
        val wave = scaleNotificationWave(source, volume)
        // Android opens the same channel URI for each sound. Replace its contents atomically.
        val destination = AtomicFile(file)
        val output = destination.startWrite()
        try {
            output.write(wave)
            destination.finishWrite(output)
        } catch (error: Exception) {
            destination.failWrite(output)
            throw error
        }
        // SystemUI can start playing before Android dispatches the notification's URI grants.
        try {
            context.grantUriPermission("com.android.systemui", notificationUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The notification service also grants its own reader access on delivery.
        }
    }
}

internal fun scaleNotificationWave(source: ByteArray, volume: Int): ByteArray {
    // The bundled chime is a 16-bit PCM WAV with a single canonical data chunk.
    val samplesStart = 44
    require(source.size >= samplesStart && (source.size - samplesStart) % 2 == 0)
    val percent = volume.coerceIn(0, 100)
    val wave = source.copyOf()
    val samples = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
    for (offset in samplesStart until wave.size step 2) {
        val sample = samples.getShort(offset).toInt()
        samples.putShort(offset, (sample * percent / 100).toShort())
    }
    return wave
}
