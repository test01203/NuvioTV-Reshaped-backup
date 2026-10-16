package app.livechannels

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Listens to the sound the player plays (passing it on unchanged) and hands it, as mono, to a
 * [SpeechDetector] for subtitle sync.
 */
@OptIn(UnstableApi::class)
class SpeechTap : BaseAudioProcessor(), SpeechSource {
    // The sound passes here about a third of a second before the TV plays it (its buffer).
    private val detector = SpeechDetector().apply { latencyMs = 300 }
    private var channels = 2
    private var floatInput = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 16-bit PCM as most devices output it, or float PCM as some do; anything else passes untouched.
        floatInput = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> false
            C.ENCODING_PCM_FLOAT -> true
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        configuredEncoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        detector.configure(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val samples = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = if (floatInput) 4 else 2
        while (samples.remaining() >= bytesPerSample * channels) {
            var mono = 0.0
            repeat(channels) {
                mono += if (floatInput) samples.float * 32_767.0 else samples.short.toDouble()
            }
            detector.feed(mono / channels)
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    fun clear() = detector.clear()

    /** Speech (true) or not for each 100 ms heard, with its wall-clock time. */
    override fun speech(): List<Pair<Long, Boolean>> = detector.speech()

    override val hearsSound: Boolean get() = configuredEncoding != 0

    /** The encoding the player hands the tap, for the panel's diagnostic line (0: nothing yet). */
    @Volatile
    var configuredEncoding = 0
        private set

    override fun secondsHeard(): Int = detector.secondsHeard()
}
