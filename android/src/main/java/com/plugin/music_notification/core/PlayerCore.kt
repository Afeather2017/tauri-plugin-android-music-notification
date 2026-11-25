package com.plugin.music_notification.core

import android.util.Log
import java.util.Collections
import java.util.concurrent.Executor

/**
 * Playback orchestrator and the single owner of the engine instance.
 *
 * State policy (see docs/architecture.md):
 * - Transport state is never mirrored: isPlaying/position/duration are read
 *   from the engine at snapshot time. The only parked values are the
 *   position/duration remembered when a player is released or restored,
 *   which no player can report.
 * - The core owns what [android.media.MediaPlayer] cannot know: the queue,
 *   the selection, the play mode, and one in-flight playback request
 *   covering the resolve/prepare window.
 * - The [epoch] counter invalidates in-flight target resolutions. Engine
 *   callbacks are validated by engine identity instead (see
 *   [beginPlayback]): a prepared engine outlives the request that created
 *   it, so pausing or re-requesting the same track must not orphan its
 *   completion/error callbacks. Stale completions of either kind are
 *   dropped, which is what keeps a second engine from ever starting while
 *   the first is still audible.
 *
 * All methods must be called on the thread that owns the core (the Android
 * main thread in production); async work is marshalled back through
 * [poster].
 */
class PlayerCore(
    private val engineFactory: () -> PlaybackEngine,
    private val resolver: PlaybackTargetResolver,
    private val lufsClient: LufsClient,
    private val store: SessionStore,
    private val poster: Poster,
    private val background: Executor,
    /**
     * Runs LUFS precache polling. Polls sleep between attempts, so in
     * production this must not be the executor that resolves playback
     * targets or a skip would queue behind them.
     */
    private val lufsBackground: Executor = background,
    private val delayer: (Long) -> Unit = { Thread.sleep(it) }
) {

    interface Listener {
        /** Fired on every playback transition (never on position ticks). */
        fun onPlaybackChanged(reason: String, message: String?)

        /** Fired when a track actually begins (fast path or after preparation). */
        fun onTrackStarted(track: QueueSongInfo, target: PlaybackTarget)
    }

    private val listeners = mutableListOf<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    // --- queue domain -------------------------------------------------------

    var tracks: List<QueueSongInfo> = emptyList()
        private set
    var currentTrackIndex: Int = -1
        private set
    var playMode: String = "sequential"
        private set
    var normalizationConfig = NormalizationConfig()
        private set

    /** Last resolved target of the current selection, for artwork. */
    var currentTarget: PlaybackTarget? = null
        private set

    /**
     * Position/duration remembered across engine release or restore. Not a
     * mirror of live playback: only consulted while no engine exists.
     */
    private var parkedRuntime = PlaybackRuntimeSnapshot(false, 0L, 0L)

    // --- transport ----------------------------------------------------------

    private var engine: PlaybackEngine? = null

    /** In-flight request covering the resolve/prepare window, or null. */
    private var request: PlaybackRequest? = null

    /** Invalidates in-flight target resolutions; see class docs. */
    private var epoch: Long = 0L

    private val lufsRequestsInFlight = Collections.synchronizedSet(mutableSetOf<Long>())

    class PlaybackRequest(
        val epoch: Long,
        val index: Int,
        var playWhenReady: Boolean,
        var seekToMs: Long?
    ) {
        /** Engine preparing for this request; null while still resolving. */
        var engine: PlaybackEngine? = null
    }

    // --- derived views ------------------------------------------------------

    fun hasEngine(): Boolean = engine != null

    fun currentTrackOrNull(): QueueSongInfo? = tracks.getOrNull(currentTrackIndex)

    val status: String
        get() = when {
            request != null -> "loading"
            engine == null -> "idle"
            engine?.isInErrorState == true -> "error"
            engine?.isPlaying == true -> "playing"
            else -> "paused"
        }

    fun runtimeSnapshot(): PlaybackRuntimeSnapshot {
        engine?.let {
            return PlaybackRuntimeSnapshot(
                isPlaying = it.isPlaying,
                positionMs = it.currentPositionMs.toLong(),
                durationMs = it.durationMs.toLong()
            )
        }
        return parkedRuntime
    }

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        queue = PlayingQueueSnapshot(
            songs = tracks,
            currentIndex = if (currentTrackIndex in tracks.indices) currentTrackIndex else null
        ),
        runtime = runtimeSnapshot(),
        playMode = playMode,
        currentSongId = currentTrackOrNull()?.id
    )

    // --- commands -----------------------------------------------------------

    fun setQueue(songs: List<QueueSongInfo>, currentIndex: Int?, newPlayMode: String?): CommandResult {
        tracks = songs
        currentTrackIndex = when {
            tracks.isEmpty() -> -1
            currentIndex == null -> 0
            currentIndex < 0 -> 0
            currentIndex >= tracks.size -> tracks.lastIndex
            else -> currentIndex
        }
        playMode = normalizePlayMode(newPlayMode)
        notifyChanged("transition")
        return CommandResult(success = true)
    }

    fun setPlayMode(newPlayMode: String): CommandResult {
        playMode = normalizePlayMode(newPlayMode)
        notifyChanged("transition")
        return CommandResult(success = true)
    }

    /**
     * Switch to [index] without re-pushing the queue. Returns once the
     * request is accepted (resolution/preparation continue asynchronously
     * and are reported through [Listener.onPlaybackChanged]).
     */
    fun playIndex(index: Int, playWhenReady: Boolean, startAtMs: Long?): CommandResult {
        if (index !in tracks.indices) {
            return CommandResult(success = false, message = "index $index out of bounds for queue of ${tracks.size}")
        }
        currentTrackIndex = index
        requestPlayback(index, playWhenReady, startAtMs, direction = 1, remaining = tracks.size)
        return CommandResult(success = true)
    }

    /** Single-URL playback: replaces the queue with one temporary track. */
    fun playUrl(url: String, title: String?, coverUrl: String?): CommandResult {
        val fallbackTrack = QueueSongInfo(
            id = -1L,
            name = title ?: "Unknown Title",
            deviceId = null,
            sourceKind = "temporary",
            localUri = null,
            tempSongUrl = url,
            lufs = null,
            coverUrl = coverUrl
        )
        tracks = listOf(fallbackTrack)
        currentTrackIndex = 0
        playMode = "sequential"
        return playIndex(0, playWhenReady = true, startAtMs = null)
    }

    fun next(): CommandResult {
        if (tracks.isEmpty()) {
            return CommandResult(success = false, message = "No tracks in queue")
        }
        if (playMode == "loop" && currentTrackIndex in tracks.indices) {
            requestPlayback(currentTrackIndex, playWhenReady = true, startAtMs = null, direction = 1, remaining = tracks.size)
            return CommandResult(success = true)
        }
        currentTrackIndex = if (currentTrackIndex in tracks.indices) {
            (currentTrackIndex + 1) % tracks.size
        } else {
            0
        }
        requestPlayback(currentTrackIndex, playWhenReady = true, startAtMs = null, direction = 1, remaining = tracks.size)
        return CommandResult(success = true)
    }

    fun previous(): CommandResult {
        if (tracks.isEmpty()) {
            return CommandResult(success = false, message = "No tracks in queue")
        }
        if (playMode == "loop" && currentTrackIndex in tracks.indices) {
            requestPlayback(currentTrackIndex, playWhenReady = true, startAtMs = null, direction = -1, remaining = tracks.size)
            return CommandResult(success = true)
        }
        currentTrackIndex = if (currentTrackIndex in tracks.indices) {
            if (currentTrackIndex == 0) tracks.lastIndex else currentTrackIndex - 1
        } else {
            0
        }
        requestPlayback(currentTrackIndex, playWhenReady = true, startAtMs = null, direction = -1, remaining = tracks.size)
        return CommandResult(success = true)
    }

    /**
     * Pause during the loading window never lets the track start:
     * - while the target is still resolving, the request is cancelled (the
     *   epoch bump drops the eventual resolution);
     * - while the engine is preparing, the request is kept with
     *   playWhenReady cleared, so onPrepared still applies the pending seek,
     *   volume and metadata and leaves the track prepared-and-paused.
     *
     * Pausing a prepared engine must not touch [epoch] or the engine's
     * callbacks: its completion still has to advance the queue after resume.
     */
    fun pause(): CommandResult {
        val activeRequest = request
        if (activeRequest != null) {
            if (activeRequest.engine != null && activeRequest.engine === engine) {
                activeRequest.playWhenReady = false
            } else {
                epoch += 1
                request = null
            }
        }
        engine?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
        notifyChanged("transition")
        return CommandResult(success = true)
    }

    fun resume(): CommandResult {
        val activeRequest = request
        val activeEngine = engine
        when {
            activeRequest != null -> activeRequest.playWhenReady = true
            activeEngine != null -> if (activeEngine.isPrepared && !activeEngine.isPlaying) {
                activeEngine.start()
                notifyChanged("transition")
            }
            currentTrackIndex in tracks.indices -> {
                requestPlayback(
                    currentTrackIndex,
                    playWhenReady = true,
                    startAtMs = parkedRuntime.positionMs.takeIf { it > 0L },
                    direction = 1,
                    remaining = tracks.size
                )
            }
            else -> return CommandResult(success = false, message = "Nothing to resume")
        }
        return CommandResult(success = true)
    }

    /**
     * Seek the current track. Seeking never starts playback on its own;
     * pass [autoPlay] to start a paused/unloaded track after the seek lands.
     */
    fun seek(positionMs: Long, autoPlay: Boolean): CommandResult {
        val normalizedPosition = positionMs.coerceAtLeast(0L)
        val activeEngine = engine
        val activeRequest = request
        if (activeEngine != null) {
            if (activeEngine.isPrepared) {
                activeEngine.seekTo(clampToDuration(normalizedPosition, activeEngine))
                if (autoPlay && !activeEngine.isPlaying) {
                    resume()
                } else {
                    notifyChanged("transition")
                }
            } else {
                // Still preparing: park the target so onPrepared applies it.
                activeRequest?.seekToMs = normalizedPosition
                if (autoPlay) {
                    activeRequest?.playWhenReady = true
                }
            }
            return CommandResult(success = true)
        }

        parkedRuntime = parkedRuntime.copy(positionMs = normalizedPosition)
        if (autoPlay && currentTrackIndex in tracks.indices) {
            requestPlayback(
                currentTrackIndex,
                playWhenReady = true,
                startAtMs = normalizedPosition,
                direction = 1,
                remaining = tracks.size
            )
        } else {
            notifyChanged("transition")
        }
        return CommandResult(success = true)
    }

    fun stop(clearQueue: Boolean): CommandResult {
        epoch += 1
        request = null
        releaseEngine()
        parkedRuntime = PlaybackRuntimeSnapshot(false, 0L, 0L)
        if (clearQueue) {
            tracks = emptyList()
            currentTrackIndex = -1
        }
        notifyChanged("transition")
        return CommandResult(success = true)
    }

    fun setRawVolume(volume: Float): CommandResult {
        applyEngineVolume(volume)
        return CommandResult(success = true)
    }

    fun setNormalizationConfig(config: NormalizationConfig): CommandResult {
        normalizationConfig = config
        currentTrackOrNull()?.let { applyTrackVolume() }
        return CommandResult(success = true)
    }

    /**
     * Record a resolved LUFS value on its queue entry. Returns the updated
     * track, or null when the id is no longer in the queue.
     */
    fun patchTrackLufs(trackId: Long, lufs: Double): QueueSongInfo? {
        val queueIndex = tracks.indexOfFirst { it.id == trackId }
        if (queueIndex < 0) {
            return null
        }
        val updatedTrack = tracks[queueIndex].copy(lufs = lufs)
        tracks = tracks.toMutableList().also { it[queueIndex] = updatedTrack }
        if (queueIndex == currentTrackIndex) {
            persist()
        }
        return updatedTrack
    }

    /** Persist the current position (called from the service's 1 s cadence). */
    fun persist() {
        store.save(snapshot())
    }

    /**
     * Restore the persisted session. Restores never claim playback: the
     * runtime is parked paused so the reported status matches reality.
     */
    fun restore() {
        val restored = store.load()
        tracks = restored.queue.songs
        currentTrackIndex = when {
            tracks.isEmpty() -> -1
            restored.queue.currentIndex == null -> 0
            restored.queue.currentIndex < 0 -> 0
            restored.queue.currentIndex >= tracks.size -> tracks.lastIndex
            else -> restored.queue.currentIndex
        }
        playMode = normalizePlayMode(restored.playMode)
        parkedRuntime = restored.runtime.copy(isPlaying = false)
        notifyChanged("restore")
    }

    /** Park the current position, release the engine and persist. For teardown. */
    fun shutdown() {
        epoch += 1
        request = null
        parkedRuntime = runtimeSnapshot().copy(isPlaying = false)
        releaseEngine()
        persist()
    }

    // --- playback pipeline --------------------------------------------------

    private fun requestPlayback(
        index: Int,
        playWhenReady: Boolean,
        startAtMs: Long?,
        direction: Int,
        remaining: Int
    ) {
        if (tracks.isEmpty() || remaining <= 0) {
            Log.w(TAG, "No reachable tracks remain in queue")
            stop(clearQueue = false)
            return
        }
        val normalizedIndex = ((index % tracks.size) + tracks.size) % tracks.size
        val track = tracks[normalizedIndex]

        val requestEpoch = epoch + 1
        epoch = requestEpoch
        val playbackRequest = PlaybackRequest(
            epoch = requestEpoch,
            index = normalizedIndex,
            playWhenReady = playWhenReady,
            seekToMs = startAtMs?.coerceAtLeast(0L)
        )
        request = playbackRequest
        notifyChanged("transition")

        background.execute {
            val target = resolver.resolve(track)
            poster.post {
                if (epoch != requestEpoch) {
                    Log.d(
                        TAG,
                        "Discarding stale playback resolution: epoch=$requestEpoch current=$epoch"
                    )
                    return@post
                }
                if (target != null) {
                    currentTrackIndex = normalizedIndex
                    beginPlayback(playbackRequest, track, target)
                    return@post
                }
                Log.w(TAG, "Skipping unresolved track=${track.name} deviceId=${track.deviceId}")
                requestPlayback(
                    normalizedIndex + direction,
                    playWhenReady = playbackRequest.playWhenReady,
                    startAtMs = playbackRequest.seekToMs,
                    direction = direction,
                    remaining = remaining - 1
                )
            }
        }
    }

    private fun beginPlayback(playbackRequest: PlaybackRequest, track: QueueSongInfo, target: PlaybackTarget) {
        val activeEngine = engine
        currentTarget = target
        if (activeEngine != null &&
            activeEngine.sourceUrl == target.audioUrl &&
            activeEngine.isPrepared
        ) {
            // Same source already prepared: seek + honor playWhenReady.
            applyTrackVolume()
            playbackRequest.seekToMs?.let { pendingPosition ->
                activeEngine.seekTo(clampToDuration(pendingPosition, activeEngine))
                playbackRequest.seekToMs = null
            }
            request = null
            if (playbackRequest.playWhenReady) {
                if (!activeEngine.isPlaying) {
                    activeEngine.start()
                }
                notifyChanged("transition")
            } else {
                if (activeEngine.isPlaying) {
                    activeEngine.pause()
                }
                notifyChanged("transition")
            }
            listeners.forEach { it.onTrackStarted(track, target) }
            precacheQueueTracks()
            return
        }

        releaseEngine()
        val newEngine = engineFactory()
        engine = newEngine
        playbackRequest.engine = newEngine
        applyTrackVolume()
        val requestEpoch = playbackRequest.epoch
        // Callbacks stay valid for as long as this engine is the live one and
        // no newer request has superseded it. They are deliberately not tied
        // to [epoch]: pause/resume and same-source re-requests keep using
        // this engine, and its completion must still advance the queue.
        newEngine.prepare(
            target.audioUrl,
            object : EngineCallbacks {
                override fun onPrepared() {
                    if (engine !== newEngine || request !== playbackRequest) {
                        Log.d(TAG, "Ignoring stale onPrepared for epoch=$requestEpoch current=$epoch")
                        return
                    }
                    handleEnginePrepared(playbackRequest, track, target)
                }

                override fun onError(what: Int, extra: Int) {
                    val activeRequest = request
                    if (engine !== newEngine || (activeRequest != null && activeRequest !== playbackRequest)) {
                        Log.d(TAG, "Ignoring stale onError for epoch=$requestEpoch current=$epoch")
                        return
                    }
                    request = null
                    notifyChanged("error", "media_error_what=$what extra=$extra")
                }

                override fun onCompletion() {
                    // A pending request means a newer selection is already
                    // loading and will replace this engine itself.
                    if (engine !== newEngine || request != null) {
                        Log.d(TAG, "Ignoring stale onCompletion for epoch=$requestEpoch current=$epoch")
                        return
                    }
                    next()
                }
            }
        )
    }

    private fun handleEnginePrepared(
        playbackRequest: PlaybackRequest,
        track: QueueSongInfo,
        target: PlaybackTarget
    ) {
        val activeEngine = engine ?: return
        playbackRequest.seekToMs?.let { pendingPosition ->
            activeEngine.seekTo(clampToDuration(pendingPosition, activeEngine))
            playbackRequest.seekToMs = null
        }
        applyTrackVolume()
        request = null
        if (playbackRequest.playWhenReady) {
            activeEngine.start()
        }
        notifyChanged("transition")
        listeners.forEach { it.onTrackStarted(track, target) }
        precacheQueueTracks()
    }

    /**
     * Clamp a seek target to the track length. Streams that report no
     * duration (0 or -1) are left unclamped instead of seeking to <= 0.
     */
    private fun clampToDuration(positionMs: Long, target: PlaybackEngine): Int {
        val durationMs = target.durationMs.toLong()
        val clamped = if (durationMs > 0L) positionMs.coerceAtMost(durationMs) else positionMs
        return clamped.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun releaseEngine() {
        engine?.release()
        engine = null
    }

    private fun applyTrackVolume() {
        val track = currentTrackOrNull() ?: return
        applyEngineVolume(calculateTrackVolume(normalizationConfig, tracks, track))
    }

    /**
     * Scales the base volume by the configured per-channel gains and pushes
     * the stereo pair to the engine for application to decoded audio.
     */
    private fun applyEngineVolume(baseVolume: Float) {
        val left = (baseVolume * normalizeChannelVolume(normalizationConfig.leftChannelVolume))
            .coerceIn(0.0f, 1.0f)
        val right = (baseVolume * normalizeChannelVolume(normalizationConfig.rightChannelVolume))
            .coerceIn(0.0f, 1.0f)
        engine?.setVolume(left, right)
    }

    // --- LUFS precache --------------------------------------------------------

    fun precacheQueueTracks() {
        val precacheCount = normalizeLufsPrecacheCount(normalizationConfig.lufsPrecacheCount)
        if (precacheCount == 0) {
            return
        }
        if (tracks.isEmpty() || currentTrackIndex !in tracks.indices) {
            return
        }

        val startIndex = currentTrackIndex
        val currentTrack = tracks[startIndex]
        val maxPositions = if (playMode == "loop") 1 else tracks.size
        val requestedTrackKeys = mutableSetOf<String>()
        var queuedCount = 0

        for (offset in 0 until maxPositions) {
            val track = tracks[(startIndex + offset) % tracks.size]
            if (track.lufs != null) {
                continue
            }
            if (!requestedTrackKeys.add(trackIdentityKey(track))) {
                continue
            }
            val isCurrentTrack = trackIdentityKey(track) == trackIdentityKey(currentTrack)
            resolveTrackLufsAsync(
                track = track,
                retryUntilResolved = true,
                applyVolumeIfCurrent = isCurrentTrack
            )
            queuedCount += 1
            if (queuedCount >= precacheCount) {
                return
            }
        }
    }

    private fun resolveTrackLufsAsync(
        track: QueueSongInfo,
        retryUntilResolved: Boolean,
        applyVolumeIfCurrent: Boolean
    ) {
        if (track.id <= 0L || track.lufs != null) {
            return
        }
        if (!lufsRequestsInFlight.add(track.id)) {
            return
        }

        lufsBackground.execute {
            try {
                for (attempt in 0..LUFS_POLL_MAX_ATTEMPTS) {
                    val result = lufsClient.requestPrecache(track)
                    if (result?.success == true && result.lufs != null) {
                        poster.post {
                            val updatedTrack = patchTrackLufs(track.id, result.lufs)
                            if (applyVolumeIfCurrent &&
                                updatedTrack != null &&
                                currentTrackOrNull()?.id == updatedTrack.id &&
                                engine?.isPrepared == true
                            ) {
                                applyTrackVolume()
                            }
                        }
                        return@execute
                    }

                    if (!retryUntilResolved || result?.cached != false || attempt == LUFS_POLL_MAX_ATTEMPTS) {
                        return@execute
                    }

                    try {
                        delayer(LUFS_POLL_DELAY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                }
            } finally {
                lufsRequestsInFlight.remove(track.id)
            }
        }
    }

    // --- event funnel ---------------------------------------------------------

    private fun notifyChanged(reason: String, message: String? = null) {
        persist()
        listeners.forEach { it.onPlaybackChanged(reason, message) }
    }

    companion object {
        private const val TAG = "PlayerCore"
        private const val LUFS_POLL_DELAY_MS = 1000L
        private const val LUFS_POLL_MAX_ATTEMPTS = 8
    }
}

data class CommandResult(
    val success: Boolean,
    val message: String? = null
)
