package app.livechannels.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.livechannels.SpeechDetector
import app.livechannels.SpeechSource
import com.sun.jna.Pointer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.AudioCallback
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/** The VLC the installer ships (next to the app), found before any VLC installed on the computer. */
class BundledVlc : DiscoveryDirectoryProvider {
    override fun priority(): Int = 1_000

    override fun directories(): Array<String> {
        val resources = System.getProperty("compose.application.resources.dir") ?: return emptyArray()
        return arrayOf(File(resources, "vlc").absolutePath)
    }

    override fun supported(): Boolean = true
}

/**
 * The player: VLC (as Ace Stream's own player uses), drawing each frame into the app's window so
 * the guide, the loading meter and the subtitles show over the picture, and handing its sound to
 * the speakers through Java (so the subtitle sync hears it too).
 */
class VlcPlayer : SpeechSource {
    enum class State { Idle, Opening, Playing, Error, Ended }

    var state by mutableStateOf(State.Idle)
        private set
    /** Bumped on every frame: the picture redraws when it changes. */
    var frameNo by mutableLongStateOf(0L)
        private set
    /** When the current stream showed its first frame (0: not yet). */
    @Volatile
    var firstFrameAt = 0L
        private set
    /** When the last frame or sound arrived, to tell a stalled stream. */
    @Volatile
    var lastDataAt = 0L
        private set
    var volume by mutableStateOf(100)
    var muted by mutableStateOf(false)

    private val factory = MediaPlayerFactory(
        NativeDiscovery(),
        "--no-video-title-show",
        "--no-snapshot-preview",
        "--quiet",
        "--no-osd",
    )
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val detector = SpeechDetector().apply { configure(SAMPLE_RATE) }

    // Video: one frame kept, drawn on the next redraw.
    private val lock = Any()
    private var pixels = ByteArray(0)
    private var bitmap: Bitmap? = null
    private var frameSize = IntSize.Zero

    // Sound: VLC decodes to 16-bit stereo, played here.
    private var line: SourceDataLine? = null

    init {
        val surface = factory.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    synchronized(lock) {
                        frameSize = IntSize(sourceWidth, sourceHeight)
                        pixels = ByteArray(sourceWidth * sourceHeight * 4)
                        bitmap?.close()
                        bitmap = Bitmap().apply {
                            allocPixels(ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
                        }
                    }
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }

                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
            },
            object : RenderCallback {
                override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
                    val buffer = nativeBuffers.firstOrNull() ?: return
                    synchronized(lock) {
                        buffer.rewind()
                        val size = minOf(buffer.remaining(), pixels.size)
                        buffer.get(pixels, 0, size)
                    }
                    val now = System.currentTimeMillis()
                    if (firstFrameAt == 0L) firstFrameAt = now
                    lastDataAt = now
                    frameNo++
                }
            },
            true,
        )
        player.videoSurface().set(surface)
        player.audio().callback("S16N", SAMPLE_RATE, 2, Speakers())
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                state = State.Playing
            }

            override fun error(mediaPlayer: MediaPlayer) {
                state = State.Error
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                state = State.Ended
            }
        })
    }

    /** Opens [url]; English sound where the broadcast carries it. */
    fun play(url: String) {
        firstFrameAt = 0L
        lastDataAt = System.currentTimeMillis()
        detector.clear()
        state = State.Opening
        player.submit {
            player.media().play(
                url,
                ":network-caching=3000",
                ":audio-language=eng,en",
                ":http-reconnect",
            )
        }
    }

    fun stop() {
        state = State.Idle
        firstFrameAt = 0L
        player.submit { player.controls().stop() }
        line?.flush()
    }

    fun release() {
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
        line?.close()
        line = null
    }

    /** Draws the last frame, fitted inside this area with black bars. */
    fun draw(scope: DrawScope) {
        frameNo // read, so a new frame redraws
        synchronized(lock) {
            val image = bitmap ?: return
            if (frameSize.width <= 0 || frameSize.height <= 0) return
            image.installPixels(image.imageInfo, pixels, frameSize.width * 4)
            val scale = minOf(scope.size.width / frameSize.width, scope.size.height / frameSize.height)
            val width = (frameSize.width * scale).roundToInt()
            val height = (frameSize.height * scale).roundToInt()
            val offset = Offset((scope.size.width - width) / 2f, (scope.size.height - height) / 2f)
            scope.drawImage(
                image.asComposeImageBitmap(),
                srcSize = frameSize,
                dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                dstSize = IntSize(width, height),
                filterQuality = FilterQuality.Medium,
            )
        }
    }

    override fun speech(): List<Pair<Long, Boolean>> = detector.speech()

    override fun secondsHeard(): Int = detector.secondsHeard()

    override val hearsSound: Boolean get() = line != null

    private inner class Speakers : AudioCallback {
        override fun play(mediaPlayer: MediaPlayer, samples: Pointer, sampleCount: Int, pts: Long) {
            val bytes = samples.getByteArray(0, sampleCount * 4)
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            val gain = if (muted) 0f else volume / 100f
            for (i in 0 until sampleCount) {
                val left = shorts.getShort(i * 4)
                val right = shorts.getShort(i * 4 + 2)
                detector.feed((left + right) / 2.0)
                if (gain != 1f) {
                    shorts.putShort(i * 4, (left * gain).toInt().coerceIn(-32768, 32767).toShort())
                    shorts.putShort(i * 4 + 2, (right * gain).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            lastDataAt = System.currentTimeMillis()
            val output = line ?: openLine() ?: return
            output.write(bytes, 0, bytes.size)
        }

        override fun pause(mediaPlayer: MediaPlayer, pts: Long) {
            line?.stop()
        }

        override fun resume(mediaPlayer: MediaPlayer, pts: Long) {
            line?.start()
        }

        override fun flush(mediaPlayer: MediaPlayer, pts: Long) {
            line?.flush()
        }

        override fun drain(mediaPlayer: MediaPlayer) {
            line?.drain()
        }

        override fun setVolume(volume: Float, mute: Boolean) = Unit
    }

    private fun openLine(): SourceDataLine? = runCatching {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN)
        AudioSystem.getSourceDataLine(format).apply {
            // A fifth of a second of sound in hand: small enough to stay with the picture.
            open(format, SAMPLE_RATE * 4 / 5)
            start()
        }
    }.getOrNull().also { line = it }

    companion object {
        private const val SAMPLE_RATE = 48_000

        /** Whether VLC can be loaded at all (the bundled one, or one installed on the computer). */
        fun available(): Boolean = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    }
}
