package com.plugin.music_notification

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import app.tauri.plugin.Invoke
import com.plugin.music_notification.core.JsonSessionCodec
import com.plugin.music_notification.core.NormalizationConfig
import com.plugin.music_notification.core.PrefsSessionStore
import com.plugin.music_notification.core.QueueSongInfo
import org.json.JSONArray
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@InvokeArg
class PingArgs {
  var value: String? = null
}

@InvokeArg
class PlayArgs {
  var url: String = ""
  var title: String? = null
  var artist: String? = null
  var album: String? = null
  var coverUrl: String? = null
}

@InvokeArg
class SeekArgs {
  var position: Long = 0
  var autoPlay: Boolean = false
}

@InvokeArg
class PlayTrackAtIndexArgs {
  var index: Int = 0
  var autoPlay: Boolean = true
  var startAtMs: Long? = null
}

@InvokeArg
class PauseAfterArgs {
  var delayMs: Long = 0
}

@InvokeArg
class SetVolumeArgs {
  var volume: Float = 0.5f
}

@InvokeArg
class SetNormalizationConfigArgs {
  var mode: String = "auto"
  var manualVolume: Float = 0.5f
  var fixedLufs: Double = -27.0
  var lufsPrecacheCount: Int = 5
}

@InvokeArg
class SetServerArgs {
  var libraryName: String? = null
}

@InvokeArg
class QueueSongArgs {
  var id: Long = -1L
  var name: String = ""
  var deviceId: String? = null
  var sourceKind: String = ""
  var localUri: String? = null
  var tempSongUrl: String? = null
  var lufs: Double? = null
  var coverUrl: String? = null
}

@InvokeArg
class PlayingQueueArgs {
  var songs: Array<QueueSongArgs> = emptyArray()
  var currentIndex: Int? = null
}

@InvokeArg
class SetPlayingQueueArgs {
  var queue: PlayingQueueArgs = PlayingQueueArgs()
  var playMode: String? = null
}

@InvokeArg
class SetPlayModeArgs {
  var playMode: String? = null
}

@InvokeArg
class SetHeadsetMediaButtonDisabledArgs {
  var disabled: Boolean = false
}

/**
 * Bridges webview invokes to [MusicPlayerService] over a bound connection.
 *
 * Commands execute synchronously on the service's main thread and resolve
 * with the real outcome (validation failures report success=false), instead
 * of the historical fire-and-forget intents that always reported
 * success=true. Playback outcomes continue to stream through the
 * `playback:event` snapshot channel.
 */
@TauriPlugin
class MusicNotificationPlugin(private val activity: Activity): Plugin(activity) {

    companion object {
        private const val TAG = "MusicNotificationPlugin"
        private const val PREFS_NAME = "music_notification"
        private const val PREF_SERVER_LIB_NAME = "server_lib_name"
        private const val BIND_TIMEOUT_MS = 3_000L
        private const val COMMAND_TIMEOUT_MS = 5_000L

        fun getServerLibName(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(PREF_SERVER_LIB_NAME, null)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceConnection: ServiceConnection? = null
    private var pendingLatch: CountDownLatch? = null

    @Volatile
    private var boundService: MusicPlayerService? = null

    override fun load(webView: WebView) {
        super.load(webView)
        // load() runs on the main thread and connection callbacks are
        // delivered there too — kick the bind off without waiting.
        startBinding()
    }

    override fun onDestroy() {
        serviceConnection?.let { connection ->
            try {
                activity.unbindService(connection)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unbind MusicPlayerService", e)
            }
        }
        serviceConnection = null
        boundService = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- binding

    /**
     * Starts the bound connection if needed and returns the latch to await
     * for the connection, or null when already connected (or binding
     * failed). Never blocks.
     */
    @Synchronized
    private fun startBinding(): CountDownLatch? {
        boundService?.let { return null }
        serviceConnection?.let { return pendingLatch }

        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                boundService = (service as MusicPlayerService.MusicPlayerBinder).service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }

        val intent = Intent(activity, MusicPlayerService::class.java)
        val bound = try {
            activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "bindService failed", e)
            false
        }
        if (!bound) {
            return null
        }
        serviceConnection = connection
        pendingLatch = latch
        return latch
    }

    private fun ensureBound(): MusicPlayerService? {
        boundService?.let { return it }
        val latch = startBinding() ?: return boundService
        return try {
            if (latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                boundService
            } else {
                Log.e(TAG, "Timed out waiting for MusicPlayerService connection")
                null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * Runs [block] against the bound service on its main thread and waits for
     * the real result. [ensureStarted] also promotes the service to a started
     * foreground service for playback commands.
     */
    private fun <T> withService(
        ensureStarted: Boolean = false,
        fallback: () -> T,
        block: (MusicPlayerService) -> T
    ): T {
        val service = ensureBound()
        if (service == null) {
            return fallback()
        }
        if (ensureStarted) {
            promoteToForegroundService()
        }
        val future = FutureTask(Callable { block(service) })
        mainHandler.post(future)
        return try {
            future.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Service command timed out or failed", e)
            fallback()
        }
    }

    private fun promoteToForegroundService() {
        val intent = Intent(activity, MusicPlayerService::class.java).apply {
            action = MusicPlayerService.ACTION_START_SERVICE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MusicPlayerService", e)
        }
    }

    private fun unavailableResult(ret: JSObject): JSObject {
        ret.put("success", false)
        ret.put("message", "MusicPlayerService unavailable")
        return ret
    }

    // ------------------------------------------------------------- commands

    @Command
    fun ping(invoke: Invoke) {
        val args = invoke.parseArgs(PingArgs::class.java)
        val ret = JSObject()
        ret.put("value", args.value ?: "pong from music notification plugin")
        invoke.resolve(ret)
    }

    @Command
    fun play(invoke: Invoke) {
        val args = invoke.parseArgs(PlayArgs::class.java)
        if (args.url.isEmpty()) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", "URL is required")
            invoke.resolve(ret)
            return
        }

        val result = withService(ensureStarted = true, fallback = { null }) { service ->
            service.commandPlay(args.url, args.title, args.artist, args.album, args.coverUrl)
        }

        val ret = JSObject()
        if (result == null) {
            unavailableResult(ret)
        } else {
            ret.put("success", result.success)
            result.message?.let { ret.put("message", it) }
        }
        invoke.resolve(ret)
    }

    @Command
    fun pause(invoke: Invoke) {
        val result = withService(fallback = { null }) { it.commandPause() }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun pauseAfter(invoke: Invoke) {
        val args = invoke.parseArgs(PauseAfterArgs::class.java)
        val result = withService(fallback = { null }) { it.commandPauseAfter(args.delayMs) }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun resume(invoke: Invoke) {
        val result = withService(ensureStarted = true, fallback = { null }) { it.commandResume() }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun stop(invoke: Invoke) {
        val result = withService(fallback = { null }) { it.commandStop() }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun next(invoke: Invoke) {
        val result = withService(ensureStarted = true, fallback = { null }) { it.commandNext() }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun previous(invoke: Invoke) {
        val result = withService(ensureStarted = true, fallback = { null }) { it.commandPrevious() }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun seek(invoke: Invoke) {
        val args = invoke.parseArgs(SeekArgs::class.java)
        val result = withService(fallback = { null }) { it.commandSeek(args.position, args.autoPlay) }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun playTrackAtIndex(invoke: Invoke) {
        val args = invoke.parseArgs(PlayTrackAtIndexArgs::class.java)
        val result = withService(ensureStarted = true, fallback = { null }) { service ->
            service.commandPlayTrackAtIndex(args.index, args.autoPlay, args.startAtMs)
        }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun seekAndPlay(invoke: Invoke) {
        val args = invoke.parseArgs(SeekArgs::class.java)
        val result = withService(ensureStarted = true, fallback = { null }) { it.commandSeekAndPlay(args.position) }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun getState(invoke: Invoke) {
        val snapshot = withService(fallback = { null }) { service ->
            Pair(service.coreRuntime(), service.nativeStatus())
        } ?: run {
            val persisted = PrefsSessionStore(activity).load()
            Pair(persisted.runtime, if (persisted.runtime.isPlaying) "playing" else "idle")
        }

        val ret = JSObject()
        ret.put("isPlaying", snapshot.first.isPlaying)
        ret.put("position", snapshot.first.positionMs)
        ret.put("duration", snapshot.first.durationMs)
        invoke.resolve(ret)
    }

    @Command
    fun setPlayingQueue(invoke: Invoke) {
        val args = invoke.parseArgs(SetPlayingQueueArgs::class.java)
        val songs = args.queue.songs.map {
            QueueSongInfo(
                id = it.id,
                name = it.name,
                deviceId = it.deviceId,
                sourceKind = it.sourceKind,
                localUri = it.localUri,
                tempSongUrl = it.tempSongUrl,
                lufs = it.lufs,
                coverUrl = it.coverUrl
            )
        }
        val invalidSong = songs.firstOrNull { !it.hasValidLocator() }
        if (invalidSong != null) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put(
                "message",
                "Invalid ${invalidSong.sourceKind} queue locator for ${invalidSong.name}"
            )
            invoke.resolve(ret)
            return
        }
        val playMode = args.playMode ?: "sequential"

        val result = withService(fallback = { null }) { service ->
            service.commandSetQueue(songs, args.queue.currentIndex, playMode)
        }

        if (result == null) {
            // Service not reachable: persist the queue so it is waiting when
            // the service next starts.
            PrefsSessionStore(activity).save(
                com.plugin.music_notification.core.SessionSnapshot(
                    queue = com.plugin.music_notification.core.PlayingQueueSnapshot(
                        songs, args.queue.currentIndex
                    ),
                    runtime = com.plugin.music_notification.core.PlaybackRuntimeSnapshot(false, 0L, 0L),
                    playMode = playMode,
                    currentSongId = songs.getOrNull(args.queue.currentIndex ?: 0)?.id
                )
            )
        }

        val ret = JSObject()
        ret.put("success", result?.success ?: true)
        result?.message?.let { ret.put("message", it) }
        invoke.resolve(ret)
    }

    @Command
    fun getPlaybackSession(invoke: Invoke) {
        val serviceSnapshot = withService(fallback = { null }) { service ->
            Pair(service.getSession(), service.nativeStatus())
        }

        val snapshot = serviceSnapshot?.first ?: PrefsSessionStore(activity).load()
        val status = serviceSnapshot?.second
            ?: if (snapshot.runtime.isPlaying) "playing" else "idle"

        val ret = JSObject()
        val queue = JSObject()
        val songs = JSONArray()
        snapshot.queue.songs.forEach { song ->
            val songObj = JSObject()
            songObj.put("id", song.id)
            songObj.put("name", song.name)
            songObj.put("deviceId", song.deviceId)
            songObj.put("sourceKind", song.sourceKind)
            songObj.put("localUri", song.localUri)
            songObj.put("tempSongUrl", song.tempSongUrl)
            songObj.put("lufs", song.lufs)
            songObj.put("coverUrl", song.coverUrl)
            songs.put(songObj)
        }
        queue.put("songs", songs)
        queue.put("currentIndex", snapshot.queue.currentIndex)

        val runtime = JSObject()
        runtime.put("isPlaying", snapshot.runtime.isPlaying)
        runtime.put("positionMs", snapshot.runtime.positionMs)
        runtime.put("durationMs", snapshot.runtime.durationMs)
        runtime.put("status", status)

        ret.put("queue", queue)
        ret.put("runtime", runtime)
        ret.put("playMode", snapshot.playMode)
        ret.put("currentSongId", snapshot.currentSongId)
        invoke.resolve(ret)
    }

    @Command
    fun clearPlayingQueue(invoke: Invoke) {
        val result = withService(fallback = { null }) { it.commandClearQueue() }
        if (result == null) {
            PrefsSessionStore(activity).save(JsonSessionCodec.emptySessionSnapshot())
        }
        invoke.resolve(resultRet(result ?: successResult()))
    }

    @Command
    fun setPlayMode(invoke: Invoke) {
        val args = invoke.parseArgs(SetPlayModeArgs::class.java)
        val playMode = args.playMode ?: "sequential"
        val result = withService(fallback = { null }) { it.commandSetPlayMode(playMode) }
        if (result == null) {
            val store = PrefsSessionStore(activity)
            store.save(store.load().copy(playMode = playMode))
        }
        invoke.resolve(resultRet(result ?: successResult()))
    }

    @Command
    fun startService(invoke: Invoke) {
        val ret = JSObject()
        try {
            promoteToForegroundService()
            ret.put("success", true)
            ret.put("message", "Service started")
        } catch (e: Exception) {
            ret.put("success", false)
            ret.put("message", e.message)
        }
        invoke.resolve(ret)
    }

    @Command
    fun stopService(invoke: Invoke) {
        val ret = JSObject()
        try {
            val serviceIntent = Intent(activity, MusicPlayerService::class.java).apply {
                action = MusicPlayerService.ACTION_STOP_SERVICE
            }
            activity.startService(serviceIntent)
            ret.put("success", true)
            ret.put("message", "Service stop requested")
        } catch (e: Exception) {
            ret.put("success", false)
            ret.put("message", e.message)
        }
        invoke.resolve(ret)
    }

    @Command
    fun setVolume(invoke: Invoke) {
        val args = invoke.parseArgs(SetVolumeArgs::class.java)
        val result = withService(fallback = { null }) { it.commandSetVolume(args.volume) }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun setNormalizationConfig(invoke: Invoke) {
        val args = invoke.parseArgs(SetNormalizationConfigArgs::class.java)
        val config = NormalizationConfig(
            mode = com.plugin.music_notification.core.normalizeNormalizationMode(args.mode),
            manualVolume = args.manualVolume,
            fixedLufs = args.fixedLufs,
            lufsPrecacheCount = args.lufsPrecacheCount
        )
        val result = withService(fallback = { null }) { it.commandSetNormalizationConfig(config) }
        invoke.resolve(resultRet(result))
    }

    @Command
    fun setServer(invoke: Invoke) {
        val args = invoke.parseArgs(SetServerArgs::class.java)

        if (args.libraryName == null) {
            val ret = JSObject()
            ret.put("success", false)
            ret.put("message", "libraryName is required")
            invoke.resolve(ret)
            return
        }

        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_SERVER_LIB_NAME, args.libraryName)
            .apply()

        val ret = JSObject()
        ret.put("success", true)
        ret.put("message", "Server library registered")
        invoke.resolve(ret)
    }

    @Command
    fun setHeadsetMediaButtonDisabled(invoke: Invoke) {
        val args = invoke.parseArgs(SetHeadsetMediaButtonDisabledArgs::class.java)
        val result = withService(fallback = { null }) { service ->
            service.commandSetHeadsetMediaButtonDisabled(args.disabled)
            true
        }
        if (result == null) {
            MusicPlayerService.setHeadsetMediaButtonDisabled(activity, args.disabled)
        }
        invoke.resolve(resultRet(successResult()))
    }

    // -------------------------------------------------------------- helpers

    private fun successResult(): com.plugin.music_notification.core.CommandResult =
        com.plugin.music_notification.core.CommandResult(success = true)

    private fun resultRet(result: com.plugin.music_notification.core.CommandResult?): JSObject {
        val ret = JSObject()
        if (result == null) {
            return unavailableResult(ret)
        }
        ret.put("success", result.success)
        result.message?.let { ret.put("message", it) }
        return ret
    }
}
