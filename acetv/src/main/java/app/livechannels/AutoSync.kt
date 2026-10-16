package app.livechannels

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens to the sound the player plays (passing it on unchanged) and keeps, for every 100 ms,
 * whether someone seems to be speaking: the energy of the speech band (about 250–3500 Hz) above
 * the recent noise floor. Language does not matter, so English speech lines up with Hebrew
 * subtitles.
 */
@OptIn(UnstableApi::class)
class SpeechTap : BaseAudioProcessor() {
    class Frame(val wallMs: Long, val db: Float)

    private val frames = ArrayDeque<Frame>()
    private var channels = 2
    private var sampleRate = 48_000
    private var frameSamples = 4_800
    private var sumSquares = 0.0
    private var count = 0
    // Band-pass state: a high-pass and a low-pass, first order each.
    private var hpPrevIn = 0.0
    private var hpPrevOut = 0.0
    private var lpPrev = 0.0
    private var hpAlpha = 0.0
    private var lpAlpha = 0.0

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
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        frameSamples = sampleRate / 10
        val dt = 1.0 / sampleRate
        val rcHigh = 1.0 / (2 * Math.PI * 250)
        val rcLow = 1.0 / (2 * Math.PI * 3_500)
        hpAlpha = rcHigh / (rcHigh + dt)
        lpAlpha = dt / (rcLow + dt)
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
            mono /= channels
            val hp = hpAlpha * (hpPrevOut + mono - hpPrevIn)
            hpPrevIn = mono
            hpPrevOut = hp
            lpPrev += lpAlpha * (hp - lpPrev)
            sumSquares += lpPrev * lpPrev
            if (++count >= frameSamples) {
                val rms = sqrt(sumSquares / count)
                synchronized(frames) {
                    frames.addLast(Frame(System.currentTimeMillis(), (20 * log10(max(rms, 1.0))).toFloat()))
                    while (frames.size > MAX_FRAMES) frames.removeFirst()
                }
                sumSquares = 0.0
                count = 0
            }
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    override fun onReset() {
        clear()
    }

    fun clear() {
        synchronized(frames) { frames.clear() }
    }

    /** Speech (true) or not for each 100 ms heard, with its wall-clock time. */
    fun speech(): List<Pair<Long, Boolean>> {
        val copy = synchronized(frames) { frames.toList() }
        if (copy.size < 50) return emptyList()
        // Between the quiet end of what was heard and its loud end, so a film with music under
        // everything still splits into speech and pauses.
        val sorted = copy.map { it.db }.sorted()
        val floor = sorted[sorted.size / 5]
        val loud = sorted[sorted.size * 9 / 10]
        val threshold = maxOf(floor + MIN_ABOVE_FLOOR_DB, floor + (loud - floor) * 0.5f)
        return copy.map { it.wallMs to (it.db > threshold) }
    }

    /** The encoding the player hands the tap, for the panel's diagnostic line (0: nothing yet). */
    @Volatile
    var configuredEncoding = 0
        private set

    fun secondsHeard(): Int = synchronized(frames) { frames.size } / 10

    companion object {
        /** Ten minutes of 100 ms frames. */
        private const val MAX_FRAMES = 6_000
        private const val MIN_ABOVE_FLOOR_DB = 4f
    }
}

/**
 * Finds the subtitle shift that best lines the subtitles up with the speech heard: for each
 * candidate shift, how often a line is on screen while someone speaks, against lines on screen
 * in silence. Coarse (1 s) over ±[rangeMs], then fine (100 ms) around the best.
 */
object AutoSync {
    class Result(val offsetMs: Long, val confidence: Float)

    fun align(
        speech: List<Pair<Long, Boolean>>,
        cues: List<Cue>,
        programmeStartMs: Long,
        currentOffsetMs: Long,
        rangeMs: Long = 10 * 60_000L,
    ): Result? {
        if (speech.size < 600 || cues.size < 10) return null
        val speaking = BooleanArray(speech.size) { speech[it].second }
        val times = LongArray(speech.size) { speech[it].first - programmeStartMs }
        val speechShare = speaking.count { it }.toFloat() / speaking.size
        if (speechShare < 0.05f || speechShare > 0.95f) return null

        fun score(offset: Long): Float {
            var hit = 0
            var miss = 0
            for (i in times.indices) {
                val shown = Subtitles.cueAt(cues, times[i] + offset) != null
                if (shown) {
                    if (speaking[i]) hit++ else miss++
                }
            }
            val total = hit + miss
            return if (total < 50) Float.NEGATIVE_INFINITY else (hit - miss * speechShare / (1 - speechShare)) / total
        }

        val coarse = ((currentOffsetMs - rangeMs)..(currentOffsetMs + rangeMs) step 1_000L).map { it to score(it) }
        val (bestCoarse, bestCoarseScore) = coarse.maxByOrNull { it.second } ?: return null
        if (bestCoarseScore == Float.NEGATIVE_INFINITY) return null
        val fine = ((bestCoarse - 1_000L)..(bestCoarse + 1_000L) step 100L).map { it to score(it) }
        val (best, bestScore) = fine.maxByOrNull { it.second } ?: return null
        // How far the best stands out from the typical shift: a clear peak is a real match.
        val scores = coarse.map { it.second }.filter { it.isFinite() }.sorted()
        val median = scores[scores.size / 2]
        val spread = (scores.last() - scores.first()).coerceAtLeast(1e-3f)
        return Result(best, ((bestScore - median) / spread).coerceIn(0f, 1f))
    }
}
