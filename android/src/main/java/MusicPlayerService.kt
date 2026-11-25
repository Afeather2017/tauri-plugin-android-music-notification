package com.plugin.music_notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.plugin.music_notification.core.CommandResult
import com.plugin.music_notification.core.HandlerPoster
import com.plugin.music_notification.core.HttpLufsClient
import com.plugin.music_notification.core.HttpPlaybackTargetResolver
import com.plugin.music_notification.core.MediaPlayerEngine
import com.plugin.music_notification.core.NormalizationConfig
import com.plugin.music_notification.core.NormalizationConfigCodec
import com.plugin.music_notification.core.PauseAfterMath
import com.plugin.music_notification.core.PlayerCore
import com.plugin.music_notification.core.PlaybackTarget
import com.plugin.music_notification.core.PrefsSessionStore
import com.plugin.music_notification.core.QueueSongInfo
import com.plugin.music_notification.core.SessionSnapshot
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Host for [PlayerCore]: owns the foreground notification, the media
 * session, the pause-after timer and the JNI bridge. All playback decisions
 * flow through the core; this class only presents them.
 *
 * Commands arrive through two channels that converge on the same core:
 *  - binder calls from [MusicNotificationPlugin] (reliable, synchronous), and
 *  - routed intents (notification buttons, media buttons, START/STOP_SERVICE).
 */
class MusicPlayerService : Service() {

    companion object {
        private const val TAG = "MusicPlayerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "MusicPlayerChannel"
        private const val PREFS_NAME = "music_notification"
        private const val PREF_DISABLE_HEADSET_MEDIA_BUTTON = "disable_headset_media_button"
        private const val PREF_PAUSE_AFTER_DEADLINE = "pause_after_deadline"

        const val ACTION_PLAY = "com.plugin.music_notification.PLAY"
        const val ACTION_PAUSE = "com.plugin.music_notification.PAUSE"
        const val ACTION_PAUSE_AFTER = "com.plugin.music_notification.PAUSE_AFTER"
        const val ACTION_RESUME = "com.plugin.music_notification.RESUME"
        const val ACTION_STOP = "com.plugin.music_notification.STOP"
        const val ACTION_NEXT = "com.plugin.music_notification.NEXT"
        const val ACTION_PREVIOUS = "com.plugin.music_notification.PREVIOUS"
        const val ACTION_SEEK = "com.plugin.music_notification.SEEK"
        const val ACTION_SEEK_AND_PLAY = "com.plugin.music_notification.SEEK_AND_PLAY"
        const val ACTION_PLAY_TRACK_AT_INDEX = "com.plugin.music_notification.PLAY_TRACK_AT_INDEX"
        const val ACTION_START_SERVICE = "com.plugin.music_notification.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.plugin.music_notification.STOP_SERVICE"
        const val ACTION_SET_VOLUME = "com.plugin.music_notification.SET_VOLUME"
        const val ACTION_SET_NORMALIZATION_CONFIG = "com.plugin.music_notification.SET_NORMALIZATION_CONFIG"

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_COVER_URL = "coverUrl"
        const val EXTRA_POSITION = "position"
        const val EXTRA_AUTO_PLAY = "autoPlay"
        const val EXTRA_INDEX = "index"
        const val EXTRA_START_AT_MS = "startAtMs"
        const val EXTRA_DELAY_MS = "delayMs"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_NORMALIZATION_MODE = "normalizationMode"
        const val EXTRA_MANUAL_VOLUME = "manualVolume"
        const val EXTRA_FIXED_LUFS = "fixedLufs"
        const val EXTRA_LUFS_PRECACHE_COUNT = "lufsPrecacheCount"

        var instance: MusicPlayerService? = null

        fun loadServerLibrary(context: Context): String? {
            val libName = MusicNotificationPlugin.getServerLibName(context)
                ?: "musicnotification_lib"

            try {
                System.loadLibrary(libName)
                return libName
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load server library $libName", e)
                try {
                    val appClassLoader = context.applicationContext.classLoader
                    val loadLibraryMethod = Class.forName("java.lang.System").getDeclaredMethod(
                        "loadLibrary",
                        String::class.java,
                        ClassLoader::class.java
                    )
                    loadLibraryMethod.invoke(null, libName, appClassLoader)
                    return libName
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to load server library $libName via classloader", ex)
                    return null
                }
            }
        }

        fun isHeadsetMediaButtonDisabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_DISABLE_HEADSET_MEDIA_BUTTON, false)
        }

        fun setHeadsetMediaButtonDisabled(context: Context, disabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_DISABLE_HEADSET_MEDIA_BUTTON, disabled).apply()
        }

        fun loadPersistedPauseAfterDeadline(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(PREF_PAUSE_AFTER_DEADLINE, 0L)
        }

        fun savePersistedPauseAfterDeadline(context: Context, deadlineMs: Long) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(PREF_PAUSE_AFTER_DEADLINE, deadlineMs).apply()
        }
    }

    private external fun serverStart(): Int
    private external fun serverStop(): Int

    // Implemented in the plugin's Rust cdylib; forwards the snapshot JSON to the
    // webview through a global Tauri event. Returns false when unavailable.
    private external fun emitPlaybackEvent(payload: String): Boolean

    private lateinit var handler: Handler
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var core: PlayerCore
    private lateinit var backgroundExecutor: ExecutorService
    // LUFS polls sleep between attempts; they get their own pool so they can
    // never starve target resolution (a skip) or artwork loading.
    private lateinit var lufsExecutor: ExecutorService
    private lateinit var progressRunnable: Runnable

    private var httpServerRunning = false
    private var foregroundActive = false
    // Lifetime decisions wait until onCreate finished (server start, restore)
    // so a half-initialized service never stopSelf()s prematurely.
    private var ready = false
    private var headsetMediaButtonDisabled = false
    private var pauseAfterRunnable: Runnable? = null
    private var pauseAfterDeadlineMs = 0L
    private var currentArtworkBitmap: Bitmap? = null
    private var artworkGeneration = 0L
    private var progressTickCounter = 0L

    // Presentation extras supplied with ACTION_PLAY (QueueSongInfo carries no
    // artist/album of its own).
    private var metadataArtist: String? = null
    private var metadataAlbum: String? = null

    // ------------------------------------------------------------------ setup

    override fun onCreate() {
        super.onCreate()
        instance = this
        handler = Handler(Looper.getMainLooper())
        // The channel must exist before anything can post a notification
        // (restore below already fires a playback-changed event).
        createNotificationChannel()
        backgroundExecutor = Executors.newFixedThreadPool(3)
        lufsExecutor = Executors.newFixedThreadPool(2)
        core = PlayerCore(
            engineFactory = { MediaPlayerEngine(this) },
            resolver = HttpPlaybackTargetResolver(),
            lufsClient = HttpLufsClient(HttpPlaybackTargetResolver()),
            store = PrefsSessionStore(this),
            poster = HandlerPoster(handler),
            background = backgroundExecutor,
            lufsBackground = lufsExecutor
        )
        normalizationConfig = NormalizationConfigCodec.load(this)
        core.setNormalizationConfig(normalizationConfig)
        headsetMediaButtonDisabled = isHeadsetMediaButtonDisabled(this)

        mediaSession = MediaSessionCompat(this, "MusicPlayerService")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (launchIntent != null) {
            val sessionActivity = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mediaSession.setSessionActivity(sessionActivity)
        } else {
            Log.w(TAG, "Failed to resolve launch intent for package $packageName")
        }

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (headsetMediaButtonDisabled && keyEvent?.action == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "MediaSession callback: ignoring media button, headset buttons disabled")
                    return true
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }

            override fun onPlay() {
                core.resume()
            }

            override fun onPause() {
                core.pause()
            }

            override fun onSkipToNext() {
                core.next()
            }

            override fun onSkipToPrevious() {
                core.previous()
            }

            override fun onStop() {
                core.stop(clearQueue = false)
            }

            override fun onSeekTo(pos: Long) {
                core.seek(pos, autoPlay = false)
            }
        })
        mediaSession.isActive = true

        progressRunnable = object : Runnable {
            override fun run() {
                if (core.status == "playing") {
                    progressTickCounter += 1
                    emitPlaybackSnapshot("tick")
                    if (progressTickCounter % 4L == 0L) {
                        core.persist()
                        updatePlaybackState()
                        updateNotification()
                    }
                    handler.postDelayed(this, 250)
                }
            }
        }

        core.addListener(object : PlayerCore.Listener {
            override fun onPlaybackChanged(reason: String, message: String?) {
                updatePlaybackState()
                updateNotification()
                emitPlaybackSnapshot(reason, message)
                manageTicker()
                if (core.status == "loading" || core.status == "playing") {
                    ensureForeground()
                }
                updateServiceLifetime()
            }

            override fun onTrackStarted(track: QueueSongInfo, target: PlaybackTarget) {
                val artist = metadataArtist ?: "Unknown Artist"
                val album = metadataAlbum ?: "Unknown Album"
                metadataArtist = null
                metadataAlbum = null
                currentArtworkBitmap = null
                artworkGeneration += 1
                val durationMs = core.runtimeSnapshot().durationMs
                applyTrackMetadata(track, artist, album, durationMs, null)
                loadArtworkForTrackAsync(track, artist, album, durationMs, target.coverUrl, target.audioUrl)
            }
        })

        Log.d(TAG, "onCreate: restoring persisted session")
        core.restore()
        restorePauseAfterSchedule()

        val loadedLib = loadServerLibrary(this)
        if (loadedLib == null) {
            Log.e(TAG, "No server library could be loaded, JNI call will likely fail")
        }
        startEmbeddedServer()
        ready = true
    }

    private fun startEmbeddedServer() {
        val result = try {
            serverStart()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "serverStart() JNI symbol missing; continuing without embedded server", e)
            -1
        } catch (e: Exception) {
            Log.e(TAG, "serverStart() threw; continuing without embedded server", e)
            -1
        }
        httpServerRunning = result == 0
        if (!httpServerRunning) {
            Log.e(TAG, "Embedded server not running (code: $result)")
        }
    }

    var normalizationConfig = NormalizationConfig()
        private set

    // --------------------------------------------------------------- commands

    inner class MusicPlayerBinder : Binder() {
        val service: MusicPlayerService get() = this@MusicPlayerService
    }

    override fun onBind(intent: Intent?): IBinder? = MusicPlayerBinder()

    fun commandPlay(
        url: String,
        title: String?,
        artist: String?,
        album: String?,
        coverUrl: String?
    ): CommandResult {
        metadataArtist = artist
        metadataAlbum = album
        return core.playUrl(url, title, coverUrl)
    }

    fun commandPause(): CommandResult = core.pause()

    fun commandPauseAfter(delayMs: Long): CommandResult {
        schedulePauseAfter(delayMs)
        return CommandResult(success = true)
    }

    fun commandResume(): CommandResult = core.resume()

    fun commandStop(): CommandResult = core.stop(clearQueue = false)

    fun commandNext(): CommandResult = core.next()

    fun commandPrevious(): CommandResult = core.previous()

    fun commandSeek(positionMs: Long, autoPlay: Boolean): CommandResult =
        core.seek(positionMs, autoPlay)

    fun commandSeekAndPlay(positionMs: Long): CommandResult = core.seek(positionMs, autoPlay = true)

    fun commandPlayTrackAtIndex(index: Int, autoPlay: Boolean, startAtMs: Long?): CommandResult =
        core.playIndex(index, playWhenReady = autoPlay, startAtMs = startAtMs)

    fun commandSetQueue(songs: List<QueueSongInfo>, currentIndex: Int?, playMode: String?): CommandResult =
        core.setQueue(songs, currentIndex, playMode)

    fun commandClearQueue(): CommandResult = core.stop(clearQueue = true)

    fun commandSetPlayMode(playMode: String): CommandResult = core.setPlayMode(playMode)

    fun commandSetVolume(volume: Float): CommandResult = core.setRawVolume(volume)

    fun commandSetNormalizationConfig(config: NormalizationConfig): CommandResult {
        normalizationConfig = config
        NormalizationConfigCodec.save(this, config)
        return core.setNormalizationConfig(config)
    }

    fun commandSetHeadsetMediaButtonDisabled(disabled: Boolean) {
        headsetMediaButtonDisabled = disabled
        setHeadsetMediaButtonDisabled(this, disabled)
    }

    fun getSession(): SessionSnapshot = core.snapshot()

    fun coreRuntime() = core.runtimeSnapshot()

    fun nativeStatus(): String = core.status

    // Wrappers kept for [SafeMediaButtonReceiver].
    fun hasMediaPlayer(): Boolean = core.hasEngine()

    fun queueSize(): Int = core.tracks.size

    fun playCurrentTrack() {
        core.playIndex(core.currentTrackIndex, playWhenReady = true, startAtMs = null)
    }

    fun resumeMusic() {
        core.resume()
    }

    fun pauseMusic() {
        core.pause()
    }

    fun playNextTrack() {
        core.next()
    }

    fun playPreviousTrack() {
        core.previous()
    }

    fun stopFromNotification() {
        core.stop(clearQueue = false)
    }

    fun updateHeadsetMediaButtonSetting(disabled: Boolean) {
        commandSetHeadsetMediaButtonDisabled(disabled)
    }

    // ------------------------------------------------------------ intent route

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            if (headsetMediaButtonDisabled) {
                Log.d(TAG, "onStartCommand: ignoring MEDIA_BUTTON, headset buttons disabled")
                return START_STICKY
            }
            MediaButtonReceiver.handleIntent(mediaSession, intent)
        }

        intent?.let {
            when (it.action) {
                ACTION_START_SERVICE -> {
                    Log.d(TAG, "Action: START_SERVICE")
                    // The binder keeps this instance alive across
                    // stopService(), so onCreate will not run again to
                    // bring the server back.
                    if (!httpServerRunning) {
                        startEmbeddedServer()
                    }
                }
                ACTION_STOP_SERVICE -> {
                    Log.d(TAG, "Action: STOP_SERVICE")
                    val result = try {
                        serverStop()
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "serverStop() JNI symbol missing", e)
                        -1
                    }
                    if (result != 0) {
                        Log.e(TAG, "Failed to stop server, code: $result")
                    }
                    // Stop was requested: the server no longer keeps the
                    // service alive, whatever serverStop() reported.
                    httpServerRunning = false
                    updateServiceLifetime()
                }
                ACTION_PLAY -> {
                    val url = it.getStringExtra(EXTRA_URL) ?: run {
                        Log.e(TAG, "URL is null, returning")
                        return START_STICKY
                    }
                    commandPlay(
                        url = url,
                        title = it.getStringExtra(EXTRA_TITLE) ?: "Unknown Title",
                        artist = it.getStringExtra(EXTRA_ARTIST) ?: "Unknown Artist",
                        album = it.getStringExtra(EXTRA_ALBUM) ?: "Unknown Album",
                        coverUrl = it.getStringExtra(EXTRA_COVER_URL)
                    )
                }
                ACTION_PAUSE -> core.pause()
                ACTION_PAUSE_AFTER -> schedulePauseAfter(it.getLongExtra(EXTRA_DELAY_MS, 0L))
                ACTION_RESUME -> core.resume()
                ACTION_STOP -> core.stop(clearQueue = false)
                ACTION_NEXT -> core.next()
                ACTION_PREVIOUS -> core.previous()
                ACTION_SEEK -> core.seek(
                    it.getLongExtra(EXTRA_POSITION, 0),
                    it.getBooleanExtra(EXTRA_AUTO_PLAY, false)
                )
                ACTION_SEEK_AND_PLAY -> core.seek(it.getLongExtra(EXTRA_POSITION, 0), autoPlay = true)
                ACTION_PLAY_TRACK_AT_INDEX -> {
                    val index = it.getIntExtra(EXTRA_INDEX, -1)
                    val autoPlay = it.getBooleanExtra(EXTRA_AUTO_PLAY, true)
                    val startAtMs =
                        if (it.hasExtra(EXTRA_START_AT_MS)) it.getLongExtra(EXTRA_START_AT_MS, 0L) else null
                    core.playIndex(index, playWhenReady = autoPlay, startAtMs = startAtMs)
                }
                ACTION_SET_VOLUME -> core.setRawVolume(it.getFloatExtra(EXTRA_VOLUME, 1.0f))
                ACTION_SET_NORMALIZATION_CONFIG -> commandSetNormalizationConfig(
                    NormalizationConfig(
                        mode = com.plugin.music_notification.core.normalizeNormalizationMode(
                            it.getStringExtra(EXTRA_NORMALIZATION_MODE)
                        ),
                        manualVolume = it.getFloatExtra(EXTRA_MANUAL_VOLUME, 0.5f),
                        fixedLufs = it.getDoubleExtra(EXTRA_FIXED_LUFS, -27.0),
                        lufsPrecacheCount = com.plugin.music_notification.core.normalizeLufsPrecacheCount(
                            it.getIntExtra(EXTRA_LUFS_PRECACHE_COUNT, normalizationConfig.lufsPrecacheCount)
                        )
                    )
                )
                else -> Log.w(TAG, "Unknown action: ${it.action}")
            }
        } ?: Log.w(TAG, "Intent is null")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        foregroundActive = true
        return START_STICKY
    }

    // ----------------------------------------------------------- pause-after

    private fun clearPauseAfterRunnable() {
        pauseAfterRunnable?.let(handler::removeCallbacks)
        pauseAfterRunnable = null
    }

    private fun clearPauseAfterState() {
        clearPauseAfterRunnable()
        pauseAfterDeadlineMs = 0L
        savePersistedPauseAfterDeadline(this, 0L)
    }

    private fun triggerTimedPause(deadlineMs: Long) {
        Log.d(TAG, "Timed pause fired at deadlineMs=$deadlineMs")
        clearPauseAfterState()
        core.pause()
    }

    private fun schedulePauseAfterAt(deadlineMs: Long) {
        clearPauseAfterRunnable()

        if (deadlineMs <= 0L) {
            clearPauseAfterState()
            Log.d(TAG, "schedulePauseAfterAt: cleared pending timed pause")
            updateServiceLifetime()
            return
        }

        pauseAfterDeadlineMs = deadlineMs
        savePersistedPauseAfterDeadline(this, deadlineMs)

        val remainingDelayMs = PauseAfterMath.computeRemainingPauseAfterDelay(
            deadlineMs,
            System.currentTimeMillis()
        )
        if (remainingDelayMs <= 0L) {
            Log.d(TAG, "schedulePauseAfterAt: deadline already expired, triggering pause immediately")
            handler.post {
                if (pauseAfterDeadlineMs == deadlineMs) {
                    triggerTimedPause(deadlineMs)
                }
            }
            return
        }

        val runnable = Runnable {
            if (pauseAfterDeadlineMs != deadlineMs) {
                Log.d(TAG, "Ignoring stale timed pause callback for deadlineMs=$deadlineMs")
                return@Runnable
            }
            Log.d(TAG, "Timed pause fired after ${remainingDelayMs}ms")
            triggerTimedPause(deadlineMs)
        }
        pauseAfterRunnable = runnable
        handler.postDelayed(runnable, remainingDelayMs)
    }

    private fun schedulePauseAfter(delayMs: Long) {
        val deadlineMs = PauseAfterMath.computePauseAfterDeadline(delayMs, System.currentTimeMillis())
        schedulePauseAfterAt(deadlineMs)
    }

    private fun restorePauseAfterSchedule() {
        val persistedDeadlineMs = loadPersistedPauseAfterDeadline(this)
        if (persistedDeadlineMs <= 0L) {
            pauseAfterDeadlineMs = 0L
            return
        }
        schedulePauseAfterAt(persistedDeadlineMs)
    }

    // -------------------------------------------------------------- lifetime

    private fun ensureForeground() {
        if (!foregroundActive) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            foregroundActive = true
        }
    }

    private fun updateServiceLifetime() {
        if (!ready) {
            return
        }
        if (!httpServerRunning && !core.hasEngine() && pauseAfterDeadlineMs <= 0L) {
            Log.d(TAG, "Server, player and timers done, leaving foreground")
            handler.removeCallbacks(progressRunnable)
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
            stopSelf()
        }
    }

    private fun manageTicker() {
        handler.removeCallbacks(progressRunnable)
        if (core.status == "playing") {
            handler.post(progressRunnable)
        }
    }

    // ------------------------------------------------------------ JS emitter

    /**
     * Push a playback snapshot to JS. Emitted on every state transition plus a
     * 250 ms position tick while playing; payload shape is a stable contract
     * with the webview frontend.
     */
    private fun emitPlaybackSnapshot(reason: String, message: String? = null) {
        try {
            val runtime = core.runtimeSnapshot()
            val json = org.json.JSONObject()
            json.put("type", "snapshot")
            json.put("reason", reason)
            json.put("status", core.status)
            json.put("index", if (core.currentTrackIndex in core.tracks.indices) core.currentTrackIndex else -1)
            json.put("songId", core.currentTrackOrNull()?.id ?: org.json.JSONObject.NULL)
            json.put("positionMs", runtime.positionMs)
            json.put("durationMs", runtime.durationMs)
            json.put("positionAtMs", System.currentTimeMillis())
            json.put("playMode", core.playMode)
            if (message != null) json.put("message", message)
            emitPlaybackEvent(json.toString())
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "emitPlaybackEvent unavailable (server library not loaded)", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to emit playback snapshot", e)
        }
    }

    // --------------------------------------------------------------- artwork

    private fun applyTrackMetadata(
        track: QueueSongInfo,
        artist: String,
        album: String,
        durationMs: Long,
        artwork: Bitmap?
    ) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

        artwork?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }

        mediaSession.setMetadata(metadataBuilder.build())
        updateNotification()
    }

    private fun loadArtworkForTrackAsync(
        track: QueueSongInfo,
        artist: String,
        album: String,
        durationMs: Long,
        coverUrlOverride: String?,
        expectedPlaybackUrl: String?
    ) {
        val coverUrl = coverUrlOverride?.trim()
        if (coverUrl.isNullOrEmpty()) {
            currentArtworkBitmap = null
            applyTrackMetadata(track, artist, album, durationMs, null)
            return
        }

        val generation = artworkGeneration
        backgroundExecutor.execute {
            val artwork = try {
                loadArtworkBitmap(coverUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load artwork for track=${track.name} coverUrl=$coverUrl", e)
                null
            }

            handler.post {
                val isCurrentPlayback = expectedPlaybackUrl == null ||
                    core.currentTarget?.audioUrl == expectedPlaybackUrl
                if (generation != artworkGeneration || !isCurrentPlayback) {
                    Log.d(TAG, "Ignoring stale artwork result for track=${track.name}")
                    return@post
                }

                currentArtworkBitmap = artwork
                applyTrackMetadata(track, artist, album, durationMs, artwork)
            }
        }
    }

    private fun loadArtworkBitmap(coverUrl: String): Bitmap? {
        val parsed = Uri.parse(coverUrl)
        val scheme = parsed.scheme?.lowercase()

        val bitmap = when (scheme) {
            "http", "https" -> loadArtworkBitmapFromNetwork(coverUrl)
            "content" -> contentResolver.openInputStream(parsed)?.use(::decodeArtworkBitmap)
            "file" -> {
                val filePath = parsed.path ?: return null
                FileInputStream(filePath).use(::decodeArtworkBitmap)
            }
            else -> {
                Log.w(TAG, "Unsupported artwork URI scheme: ${scheme ?: "none"} url=$coverUrl")
                null
            }
        }

        return bitmap?.let(::resizeArtworkBitmap)
    }

    private fun loadArtworkBitmapFromNetwork(coverUrl: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(coverUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.doInput = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Artwork request failed for url=$coverUrl status=${connection.responseCode}")
                null
            } else {
                connection.inputStream.use(::decodeArtworkBitmap)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun decodeArtworkBitmap(stream: InputStream): Bitmap? {
        return BitmapFactory.decodeStream(stream)
    }

    private fun resizeArtworkBitmap(bitmap: Bitmap): Bitmap {
        val maxDimension = 512
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= maxDimension) {
            return bitmap
        }

        val scale = maxDimension.toFloat() / largestDimension.toFloat()
        val resizedWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val resizedHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
    }

    // ----------------------------------------------------------- notification

    private fun createNotification(): Notification {
        val controller = mediaSession.controller
        val mediaMetadata = controller.metadata
        val description = mediaMetadata?.description
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)

        val isPlaying = core.status == "playing"
        val title = if (core.hasEngine()) {
            description?.title ?: "Music Player"
        } else if (core.currentTrackIndex in core.tracks.indices) {
            core.tracks[core.currentTrackIndex].name
        } else {
            "No song is playing"
        }
        val playPauseIntent = if (isPlaying) {
            createServiceActionPendingIntent(ACTION_PAUSE, 2)
        } else {
            createServiceActionPendingIntent(ACTION_RESUME, 3)
        }

        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            playPauseIntent
        )

        val previousAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            createServiceActionPendingIntent(ACTION_PREVIOUS, 1)
        )

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            createServiceActionPendingIntent(ACTION_NEXT, 4)
        )

        builder.setContentTitle(title)
            .setContentText(description?.subtitle ?: "")
            .setSubText(description?.description ?: "")
            .setContentIntent(controller.sessionActivity)
            .setDeleteIntent(createServiceActionPendingIntent(ACTION_STOP, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(currentArtworkBitmap)
            .setOngoing(isPlaying)
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        return builder.build()
    }

    private fun createServiceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlayerService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        // Only a foreground service owns the notification; posting earlier
        // (e.g. on restore right after bind) would show a stray one.
        if (!foregroundActive) {
            return
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun updatePlaybackState() {
        val availableActions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO

        val runtime = core.runtimeSnapshot()
        val playbackState = if (core.hasEngine()) {
            val state = if (runtime.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            PlaybackStateCompat.Builder()
                .setActions(availableActions)
                .setState(state, runtime.positionMs, 1.0f)
                .build()
        } else {
            PlaybackStateCompat.Builder()
                .setActions(availableActions)
                .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                .build()
        }
        mediaSession.setPlaybackState(playbackState)
    }

    // ------------------------------------------------------------- teardown

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        instance = null
        handler.removeCallbacks(progressRunnable)
        clearPauseAfterRunnable()
        mediaSession.release()
        core.shutdown()
        backgroundExecutor.shutdown()
        lufsExecutor.shutdownNow()
        Log.d(TAG, "onDestroy complete")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Player Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
