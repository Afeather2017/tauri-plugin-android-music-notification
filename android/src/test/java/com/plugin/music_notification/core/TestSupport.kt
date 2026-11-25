package com.plugin.music_notification.core

import java.util.concurrent.Executor

/** Deterministic test doubles for [PlayerCore]. */

class FakeEngine : PlaybackEngine {
    val events = mutableListOf<String>()
    var prepared = false
        private set
    var error = false
        private set
    var playing = false
        private set
    var position = 0
        private set
    var leftVolume = 1f
        private set
    var rightVolume = 1f
        private set
    var url: String? = null
        private set
    var released = false
        private set
    var callbacks: EngineCallbacks? = null

    /** Duration reported once prepared; set to -1 to mimic a live stream. */
    var reportedDurationMs = DURATION_MS

    override fun prepare(url: String, callbacks: EngineCallbacks) {
        events += "prepare:$url"
        this.url = url
        this.callbacks = callbacks
        released = false
        prepared = false
        error = false
    }

    override fun start() {
        events += "start"
        playing = true
    }

    override fun pause() {
        events += "pause"
        playing = false
    }

    override fun seekTo(positionMs: Int) {
        events += "seekTo:$positionMs"
        position = positionMs
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        events += "setVolume:$leftVolume:$rightVolume"
        this.leftVolume = leftVolume
        this.rightVolume = rightVolume
    }

    override fun release() {
        events += "release"
        released = true
        prepared = false
        playing = false
    }

    override val isPrepared: Boolean get() = prepared
    override val isInErrorState: Boolean get() = error
    override val isPlaying: Boolean get() = playing
    override val currentPositionMs: Int get() = position
    override val durationMs: Int get() = if (prepared) reportedDurationMs else 0
    override val sourceUrl: String? get() = url

    fun firePrepared() {
        prepared = true
        callbacks?.onPrepared()
    }

    fun fireError(what: Int = 1, extra: Int = 0) {
        error = true
        prepared = false
        callbacks?.onError(what, extra)
    }

    fun fireCompletion() {
        callbacks?.onCompletion()
    }

    /** Simulate playback progress for snapshot assertions. */
    fun advanceTo(positionMs: Int) {
        position = positionMs
    }

    companion object {
        const val DURATION_MS = 180_000
    }
}

/** Queues posted blocks for manual, deterministic draining. */
class ManualPoster : Poster {
    private val queue = ArrayDeque<() -> Unit>()

    override fun post(block: () -> Unit) {
        queue.addLast(block)
    }

    val pending: Int get() = queue.size

    fun runNext() {
        require(queue.isNotEmpty()) { "no pending blocks" }
        queue.removeFirst()()
    }

    fun runAll() {
        while (queue.isNotEmpty()) {
            queue.removeFirst()()
        }
    }
}

/** Queues background tasks for manual, deterministic execution. */
class ManualExecutor : Executor {
    private val queue = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        queue.addLast(command)
    }

    val pending: Int get() = queue.size

    fun runNext() {
        require(queue.isNotEmpty()) { "no pending tasks" }
        queue.removeFirst().run()
    }

    fun runAll() {
        while (queue.isNotEmpty()) {
            queue.removeFirst().run()
        }
    }
}

class FakeResolver : PlaybackTargetResolver {
    val resolved = mutableListOf<QueueSongInfo>()
    val unresolvableIds = mutableSetOf<Long>()

    override fun resolve(track: QueueSongInfo): PlaybackTarget? {
        resolved += track
        if (track.id in unresolvableIds) {
            return null
        }
        return PlaybackTarget(audioUrl = "https://example.test/audio/${track.id}", coverUrl = track.coverUrl)
    }
}

class InMemorySessionStore : SessionStore {
    val saved = mutableListOf<SessionSnapshot>()

    override fun load(): SessionSnapshot =
        saved.lastOrNull() ?: JsonSessionCodec.emptySessionSnapshot()

    override fun save(snapshot: SessionSnapshot) {
        saved += snapshot
    }
}

class FakeLufsClient : LufsClient {
    val requests = mutableListOf<QueueSongInfo>()
    val results = ArrayDeque<PrecacheLufsResult?>()

    override fun requestPrecache(track: QueueSongInfo): PrecacheLufsResult? {
        requests += track
        return results.removeFirstOrNull() ?: PrecacheLufsResult(success = true, lufs = -14.0, cached = true)
    }
}

/** Wires a [PlayerCore] with manual scheduling everywhere. */
class CoreHarness {
    val engines = mutableListOf<FakeEngine>()
    val poster = ManualPoster()
    val background = ManualExecutor()
    val resolver = FakeResolver()
    val store = InMemorySessionStore()
    val lufsClient = FakeLufsClient()

    val core = PlayerCore(
        engineFactory = { FakeEngine().also { engines += it } },
        resolver = resolver,
        lufsClient = lufsClient,
        store = store,
        poster = poster,
        background = background,
        delayer = { }
    )

    /** Run one resolve cycle: background resolution + delivery to the core. */
    fun stepResolve() {
        background.runNext()
        poster.runAll()
    }

    /**
     * Drain every interleaved background/poster cycle until both queues are
     * empty (LUFS precache tasks queue behind playback resolutions).
     */
    fun runUntilIdle(maxCycles: Int = 50) {
        var cycles = 0
        while ((background.pending > 0 || poster.pending > 0) && cycles < maxCycles) {
            if (background.pending > 0) {
                background.runNext()
            }
            poster.runAll()
            cycles += 1
        }
    }

    /**
     * Asserts the double-playback invariant over the recorded engine events:
     * at most one engine is unreleased at any point in the sequence, and no
     * engine receives any call after its release.
     */
    fun assertSingleEngineOwnership(context: String) {
        var unreleased = 0
        for ((index, engine) in engines.withIndex()) {
            var thisReleased = false
            for (event in engine.events) {
                if (thisReleased) {
                    throw AssertionError("engine#$index received '$event' after release ($context)")
                }
                if (event == "prepare") {
                    unreleased += 1
                    org.junit.Assert.assertEquals(
                        "two engines alive simultaneously ($context)",
                        1,
                        unreleased
                    )
                }
                if (event == "release") {
                    unreleased -= 1
                    thisReleased = true
                }
            }
        }
    }
}

fun song(
    id: Long,
    kind: String = "temporary",
    lufs: Double? = null,
    name: String = "Song $id"
): QueueSongInfo = QueueSongInfo(
    id = id,
    name = name,
    deviceId = if (kind == "kaulan") "device-$id" else null,
    sourceKind = kind,
    localUri = if (kind == "local_raw") "content://media/$id" else null,
    tempSongUrl = if (kind == "temporary") "https://example.test/$id.mp3" else null,
    lufs = lufs,
    coverUrl = null
)
