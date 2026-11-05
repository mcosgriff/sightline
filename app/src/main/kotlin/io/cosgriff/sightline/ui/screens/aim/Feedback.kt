package io.cosgriff.sightline.ui.screens.aim

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.cosgriff.sightline.analysis.AimEvent
import io.cosgriff.sightline.analysis.ToneSpec
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Aim's tone (SPEC "Feedback that doesn't need eyes"): a sine on its own thread, gliding to each new pitch so it
 * doesn't step, gated on and off eight times a second to stutter, faded so nothing clicks. Silent while the spec
 * is null (no lock).
 */
class TonePlayer {
    @Volatile
    private var target: ToneSpec? = null

    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun update(spec: ToneSpec?) {
        target = spec
    }

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "aim-tone", isDaemon = true) { play() }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    private fun play() {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, RATE / 10 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val chunk = ShortArray(RATE / 50)
            var phase = 0.0
            var hz = 0.0
            var amp = 0.0
            var n = 0L
            val gateSamples = RATE / (2 * STUTTER_HZ)
            while (running) {
                val spec = target
                if (spec != null && hz == 0.0) hz = spec.hz
                for (i in chunk.indices) {
                    if (spec != null) hz += (spec.hz - hz) * GLIDE
                    val on = spec != null && !(spec.stutter && (n / gateSamples) % 2 == 1L)
                    amp += ((if (on) VOLUME else 0.0) - amp) * FADE
                    phase += 2 * PI * hz / RATE
                    if (phase > 2 * PI) phase -= 2 * PI
                    chunk[i] = (sin(phase) * amp * Short.MAX_VALUE).toInt().toShort()
                    n++
                }
                track.write(chunk, 0, chunk.size)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        const val RATE = 44_100
        const val STUTTER_HZ = 8
        const val VOLUME = 0.35
        const val GLIDE = 0.0015
        const val FADE = 0.004
    }
}

/** Aim's haptics: a short tick on a new peak, a double buzz when the lock is lost. */
class AimHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    fun play(event: AimEvent) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        when (event) {
            AimEvent.NEW_PEAK -> v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            AimEvent.LOCK_LOST -> v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1))
        }
    }
}
