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
                                         PlaybackEngine (Media3PlaybackEngine)
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
2. **Engine-internal bookkeeping lives in the engine adapter.**
   `Media3PlaybackEngine` tracks preparation and errors through player callbacks.
   Its audio processor applies channel gains to decoded PCM before output.
3. **The core owns Kaulan's playback state**: the queue, the
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

## Concurrency: the epoch and engine identity

All core methods run on the Android main thread. Asynchronous work runs on
background executors and marshals results back through the main-thread
`Poster`. Target resolution and artwork share one executor; LUFS precache
polling has its own (`lufsBackground`), because each poll sleeps between
attempts and would otherwise delay the resolution behind a skip.

Two rules decide whether an asynchronous completion is still wanted:

- **Target resolutions** carry the `epoch` of their request. Any new
  request or stop bumps it, as does a pause that arrives while the target
  is still resolving; a resolution with a stale epoch is dropped.
- **Engine callbacks** are checked against engine identity, not the epoch.
  `onPrepared` is honoured while its engine is the live one and its request
  is still the pending one; `onCompletion` while its engine is the live one
  and no newer request is pending. A prepared engine outlives the request
  that created it — pause/resume and same-source re-requests (single-track
  loop) keep using it — so its completion must keep advancing the queue.

Pausing while an engine is still preparing keeps the request and clears
`playWhenReady`, so the pending seek, volume and track metadata are still
applied when preparation finishes and the track is left paused.

Double playback stays impossible because an engine can only be created at
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
