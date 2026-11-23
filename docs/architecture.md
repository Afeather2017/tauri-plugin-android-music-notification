# Architecture

## Layering

```
JS (guest-js)
  │  invoke commands (unchanged contract)          playback:event snapshots
  ▼                                                   ▲
Rust commands.rs / mobile.rs  ── forward ──►  MusicNotificationPlugin (Kotlin)
                                                  │  bound ServiceConnection,
                                                  │  synchronous commands
                                                  ▼
                                          MusicPlayerService (host)
                                            notification · media session ·
                                            pause-after · artwork · JNI emit
                                                  │
                                                  ▼
                                              PlayerCore
                              queue · selection · play mode · in-flight request
                                                  │ single engine ownership
                                                  ▼
                                         PlaybackEngine (MediaPlayerEngine)
```

`MusicPlayerService` and `MusicNotificationPlugin` present state; `PlayerCore`
decides it. Both entry channels — binder commands from the webview and routed
intents (notification buttons, media buttons) — converge on the same core
methods.

## State policy

The guiding rule: **the platform player already has state — query it, don't
mirror it.**

1. **Transport state is never stored.** `isPlaying`, position and duration
   are read from the engine whenever a snapshot is taken. The historical
   `musicPlayerActive` / `cachedRuntime` mirrors and their drift bugs are
   gone by construction.
2. **Engine-internal bookkeeping lives in the engine adapter.** MediaPlayer
   has no `isPrepared` query, so `MediaPlayerEngine` tracks prepared/error
   flags — updated only by MediaPlayer's own callbacks.
3. **The core owns only what MediaPlayer cannot know**: the queue, the
   selection, the play mode, and one in-flight request
   (`{epoch, index, playWhenReady, seekToMs}`) covering the resolve/prepare
   window where no player is loaded yet. `playWhenReady` is passed
   explicitly per request — there is no sticky default.
4. **Reported status is derived, not stored**:
   `request != null → "loading"`, `engine == null → "idle"`,
   `engine.isInErrorState → "error"`, `engine.isPlaying → "playing"`,
   else `"paused"`.

The only parked values are position/duration remembered across engine
release or process restore (no player exists to ask), and restores always
park **paused**.

## Concurrency and the single epoch

All core methods run on the Android main thread. Asynchronous work (target
resolution, LUFS precache, artwork) runs on a background executor and
marshals results back through the main-thread `Poster`.

A single `epoch` counter invalidates every asynchronous completion: any
new request, pause or stop bumps it, and completions carrying a stale epoch
are dropped. This replaces the four historical generation counters and is
what makes double playback impossible — an engine can only be created at
one point in the core, and the previous engine is always released first
(see `PlayerCoreLifecycleTest.rapidSecondRequest_releasesFirstEngineBeforePreparingSecond`).

## Persistence

`SessionStore` abstracts persistence; `JsonSessionCodec` is pure and
byte-compatible with sessions written by pre-refactor versions. Durable
snapshots drop `temporary` tracks (ephemeral URLs) and remap the selection.

## Testing

Pure JVM unit tests (`gradlew test`) drive `PlayerCore` with a `FakeEngine`
that records its own lifecycle events, plus `ManualPoster`/`ManualExecutor`
for deterministic scheduling. Covered: queue navigation in every play mode,
`playWhenReady=false`, pending seeks, stale-callback dropping, pause during
the loading window, unresolvable-track skipping, restore semantics, LUFS
volume application, and the single-engine-ownership invariant.
