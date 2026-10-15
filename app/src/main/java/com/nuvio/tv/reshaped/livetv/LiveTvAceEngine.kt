package com.nuvio.tv.reshaped.livetv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.acestream.engine.service.v0.IAceStreamEngine
import org.acestream.engine.service.v0.IAceStreamEngineCallback
import org.acestream.engine.service.v0.IStartEngineResponse

/**
 * Starts the Ace Stream engine of an installed Ace Stream app in the background, so Ace Stream
 * channels play without opening that app first. Uses the apps' public engine service (the same
 * one Ace Stream's own client library binds to), and stays bound while Nuvio runs so the engine
 * keeps going.
 */
internal object LiveTvAceEngine {
    private const val TAG = "LiveTvAceEngine"
    private const val SERVICE_ACTION = "org.acestream.engine.service.v0.IAceStreamEngine"
    private const val START_TIMEOUT_MS = 60_000L

    /** Ace Stream apps, by preference when several are installed (the newest wins anyway). */
    private val KNOWN_PACKAGES = listOf(
        "org.acestream.core.atv",
        "org.acestream.core",
        "org.acestream.media.atv",
        "org.acestream.media",
    )

    private val lock = Mutex()
    private var connection: ServiceConnection? = null

    /** Whether [engineUrl] is the engine on this device, which Nuvio can start itself. */
    fun isLocal(engineUrl: String): Boolean {
        val host = engineUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return host == "127.0.0.1" || host == "localhost"
    }

    /** Whether an Ace Stream app that provides the engine is installed. */
    fun isInstalled(context: Context): Boolean = servicePackage(context) != null

    /**
     * Makes sure the engine at [engineUrl] answers, starting the installed one when it is on this
     * device. Throws [LiveTvException] with [LiveTvError.AceStreamNotInstalled] or
     * [LiveTvError.AceStreamEngine] when it cannot.
     */
    suspend fun ensureRunning(context: Context, engineUrl: String) {
        if (answers(engineUrl)) return
        if (!isLocal(engineUrl)) throw LiveTvException(LiveTvError.AceStreamEngine)
        lock.withLock {
            if (answers(engineUrl)) return
            val pkg = servicePackage(context) ?: throw LiveTvException(LiveTvError.AceStreamNotInstalled)
            val started = withTimeoutOrNull(START_TIMEOUT_MS) { start(context.applicationContext, pkg) } ?: false
            // The engine says it is ready a moment before its HTTP API takes requests.
            if (!started || !waitUntilAnswers(engineUrl)) throw LiveTvException(LiveTvError.AceStreamEngine)
        }
    }

    private suspend fun start(context: Context, pkg: String): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val callback = object : IAceStreamEngineCallback.Stub() {
            override fun onUnpacking() {
                Log.d(TAG, "engine unpacking")
            }
            override fun onStarting() {
                Log.d(TAG, "engine starting")
            }
            override fun onReady(listenPort: Int) {
                Log.d(TAG, "engine ready on $listenPort")
                ready.complete(listenPort != -1)
            }
            override fun onStopped() {
                ready.complete(false)
            }
            override fun onWaitForNetworkConnection() = Unit
            override fun onPlaylistUpdated() = Unit
            override fun onEPGUpdated() = Unit
            override fun onRestartPlayer() = Unit
            override fun onSettingsUpdated() = Unit
            override fun onAuthUpdated() = Unit
        }
        val response = object : IStartEngineResponse.Stub() {
            override fun onResult(success: Boolean) {
                ready.complete(success)
            }
        }
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IAceStreamEngine.Stub.asInterface(binder)
                runCatching {
                    service.registerCallbackExt(callback, true)
                    service.startEngineWithCallback(response)
                }.onFailure {
                    Log.w(TAG, "engine service call failed", it)
                    ready.complete(false)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "engine service disconnected")
            }
        }
        connection?.let { old -> runCatching { context.unbindService(old) } }
        connection = null
        val intent = Intent(SERVICE_ACTION).setPackage(pkg)
        val bound = runCatching { context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "could not bind the engine service of $pkg", it) }
            .getOrDefault(false)
        if (!bound) return false
        connection = serviceConnection
        return ready.await()
    }

    /**
     * Plays [channel] in Ace Stream's own player (Ace Player), the way Ace Stream asks other apps
     * to start its content: free for the viewer, unlike external players. A channel listed by
     * content id starts with `org.acestream.action.start_content`; one listed by infohash gets an
     * engine session whose playback link opens in the Ace Stream app, which the engine counts as
     * its own player. False when no Ace Stream app could take it.
     */
    suspend fun openInAcePlayer(context: Context, channel: LiveTvChannel): Boolean {
        val pkg = servicePackage(context) ?: return false
        val link = channel.streamUrl
        val contentId = Regex("""[?&]id=([0-9a-fA-F]{40})""").find(link)?.groupValues?.get(1)
        val infohash = Regex("""[?&]infohash=([0-9a-fA-F]{40})""").find(link)?.groupValues?.get(1)
        val intents = buildList {
            if (contentId != null) add(startContentIntent("acestream:?content_id=$contentId"))
            if (infohash != null) {
                channel.aceEngineUrl()?.let { engine ->
                    runCatching { ensureRunning(context, engine) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
                LiveTvAceStream.playbackLink(link)?.let { playback ->
                    add(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(android.net.Uri.parse(playback), "video/*")
                            .setPackage(pkg),
                    )
                }
                add(startContentIntent("acestream:?infohash=$infohash"))
                add(startContentIntent("acestream:?url=" + android.net.Uri.encode("magnet:?xt=urn:btih:$infohash")))
            }
        }
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "Ace Player did not take ${intent.data}", it) }
                .isSuccess
            if (started) return true
        }
        return false
    }

    private fun startContentIntent(data: String): Intent =
        Intent("org.acestream.action.start_content")
            .setData(android.net.Uri.parse(data))
            // Straight into Ace Player, without the "open with" choice.
            .putExtra("org.acestream.EXTRA_SELECTED_PLAYER", "{\"type\": 3}")

    private suspend fun waitUntilAnswers(engineUrl: String): Boolean {
        repeat(20) {
            if (answers(engineUrl)) return true
            kotlinx.coroutines.delay(500)
        }
        return false
    }

    private suspend fun answers(engineUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val http = URL("${engineUrl.trimEnd('/')}/webui/api/service?method=get_version").openConnection() as HttpURLConnection
            http.connectTimeout = 1500
            http.readTimeout = 3000
            try {
                http.responseCode in 200..499
            } finally {
                http.disconnect()
            }
        }.getOrDefault(false)
    }

    /** The installed Ace Stream app with the highest version, as Ace Stream's own client picks it. */
    private fun servicePackage(context: Context): String? {
        val pm = context.packageManager
        val candidates = LinkedHashSet(KNOWN_PACKAGES)
        runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(Intent(SERVICE_ACTION), PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.serviceInfo?.packageName }
                .forEach(candidates::add)
        }
        return candidates.mapNotNull { pkg -> versionCode(pm, pkg)?.let { pkg to it } }.maxByOrNull { it.second }?.first
    }

    private fun versionCode(pm: PackageManager, pkg: String): Long? = try {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
