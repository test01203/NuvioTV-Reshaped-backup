package app.livechannels

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Whether someone seems to be speaking, for every 100 ms of sound fed in: the energy of the
 * speech band (about 250–3500 Hz) above the recent noise floor. Language does not matter, so
 * English speech lines up with Hebrew subtitles. Fed by the TV app's player and the PC app's.
 */
class SpeechDetector {
    class Frame(val wallMs: Long, val db: Float)

    private val frames = ArrayDeque<Frame>()
    private var frameSamples = 4_800
    private var sumSquares = 0.0
    private var count = 0
    // Band-pass state: a high-pass and a low-pass, first order each.
    private var hpPrevIn = 0.0
    private var hpPrevOut = 0.0
    private var lpPrev = 0.0
    private var hpAlpha = 0.0
    private var lpAlpha = 0.0

    /**
     * How far ahead of the speakers the sound is heard here (the player's sound buffer), so
     * speech is stamped when the viewer hears it.
     */
    @Volatile
    var latencyMs = 0L

    fun configure(sampleRate: Int) {
        val rate = sampleRate.coerceAtLeast(8_000)
        frameSamples = rate / 10
        val dt = 1.0 / rate
        val rcHigh = 1.0 / (2 * Math.PI * 250)
        val rcLow = 1.0 / (2 * Math.PI * 3_500)
        hpAlpha = rcHigh / (rcHigh + dt)
        lpAlpha = dt / (rcLow + dt)
        sumSquares = 0.0
        count = 0
    }

    init {
        configure(48_000)
    }

    /** One mono sample, on the 16-bit scale. */
    fun feed(mono: Double) {
        val hp = hpAlpha * (hpPrevOut + mono - hpPrevIn)
        hpPrevIn = mono
        hpPrevOut = hp
        lpPrev += lpAlpha * (hp - lpPrev)
        sumSquares += lpPrev * lpPrev
        if (++count >= frameSamples) {
            val rms = sqrt(sumSquares / count)
            synchronized(frames) {
                frames.addLast(Frame(System.currentTimeMillis() + latencyMs, (20 * log10(max(rms, 1.0))).toFloat()))
                while (frames.size > MAX_FRAMES) frames.removeFirst()
            }
            sumSquares = 0.0
            count = 0
        }
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

    fun secondsHeard(): Int = synchronized(frames) { frames.size } / 10

    companion object {
        /** Ten minutes of 100 ms frames. */
        private const val MAX_FRAMES = 6_000
        private const val MIN_ABOVE_FLOOR_DB = 4f
    }
}

/**
 * Finds the subtitle shift that best lines the subtitles up with the speech heard: for each
 * candidate shift, how closely "a line is on screen" follows "someone speaks" over everything
 * heard (a correlation, so lines shown in silence and speech with no line both count against
 * it). Coarse over the whole [range], then fine (100 ms) around the best; the match counts only
 * when it stands far above every other shift.
 */
object AutoSync {
    /** Sound needed before a match is tried. */
    const val MIN_SECONDS = 40

    class Result(val offsetMs: Long, val confidence: Float)

    fun align(
        speech: List<Pair<Long, Boolean>>,
        cues: List<Cue>,
        programmeStartMs: Long,
        range: LongRange,
    ): Result? {
        if (speech.size < MIN_SECONDS * 10 || cues.size < 10) return null
        val speaking = BooleanArray(speech.size) { speech[it].second }
        val speakingCount = speaking.count { it }
        if (speakingCount < speech.size / 20 || speakingCount > speech.size * 19 / 20) return null
        // Where the film is at each moment heard, before any shift (in 100 ms steps).
        val base = LongArray(speech.size) { (speech[it].first - programmeStartMs) / STEP_MS }
        // Whether a line is on screen, for every 100 ms of the film.
        val filmSteps = (cues.maxOf { it.endMs } / STEP_MS + 1).toInt()
        val shown = BooleanArray(filmSteps)
        for (cue in cues) {
            val from = (cue.startMs / STEP_MS).toInt().coerceIn(0, filmSteps)
            val to = (cue.endMs / STEP_MS).toInt().coerceIn(0, filmSteps)
            for (i in from until to) shown[i] = true
        }

        fun score(offsetMs: Long): Double {
            val shift = offsetMs / STEP_MS
            var both = 0
            var shownCount = 0
            for (i in base.indices) {
                val at = base[i] + shift
                if (at < 0 || at >= filmSteps || !shown[at.toInt()]) continue
                shownCount++
                if (speaking[i]) both++
            }
            if (shownCount < 50) return Double.NaN
            // Phi correlation of "line on screen" and "speech".
            val n = speech.size.toDouble()
            val a = both.toDouble()
            val b = (shownCount - both).toDouble()
            val c = (speakingCount - both).toDouble()
            val d = n - a - b - c
            val denominator = Math.sqrt((a + b) * (c + d) * (a + c) * (b + d))
            return if (denominator <= 0) Double.NaN else (a * d - b * c) / denominator
        }

        val step = if (range.last - range.first > 60 * 60_000L) 2_000L else 1_000L
        val coarse = (range.first..range.last step step).map { it to score(it) }.filter { !it.second.isNaN() }
        if (coarse.size < 10) return null
        val (bestCoarse, _) = coarse.maxBy { it.second }
        val fine = ((bestCoarse - step)..(bestCoarse + step) step STEP_MS).map { it to score(it) }.filter { !it.second.isNaN() }
        val (best, bestScore) = fine.maxByOrNull { it.second } ?: return null
        // How far the best stands out: shifts more than 3 s away from it, as a spread.
        val others = coarse.filter { kotlin.math.abs(it.first - best) > 3_000 }.map { it.second }
        if (others.isEmpty()) return null
        val mean = others.average()
        val deviation = Math.sqrt(others.sumOf { (it - mean) * (it - mean) } / others.size).coerceAtLeast(1e-4)
        val standing = (bestScore - mean) / deviation
        return Result(best, (standing / 8.0).toFloat().coerceIn(0f, 1f))
    }

    /** A match this clear moves the subtitles. */
    const val TRUSTED = 0.5f

    private const val STEP_MS = 100L
}
