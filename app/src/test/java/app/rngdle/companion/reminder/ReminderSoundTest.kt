package app.rngdle.companion.reminder

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ReminderSoundTest {
    private val header = ByteArray(44) { it.toByte() }
    private val wave = header + pcm(25_000, -12_500, 0)

    @Test fun defaultVolumeAttenuatesPcmWithoutChangingTheHeader() {
        assertArrayEquals(
            header + pcm(10_000, -5_000, 0),
            scaleNotificationWave(wave, ReminderOptions().notificationVolume),
        )
        assertArrayEquals(header + pcm(25_000, -12_500, 0), wave)
    }

    @Test fun zeroVolumeProducesSilenceAndFullVolumePreservesTheClip() {
        assertArrayEquals(header + pcm(0, 0, 0), scaleNotificationWave(wave, 0))
        assertArrayEquals(wave, scaleNotificationWave(wave, 100))
    }

    private fun pcm(vararg samples: Short): ByteArray = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { samples.forEach { putShort(it) } }
        .array()
}
