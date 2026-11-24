package com.plugin.music_notification.core

import android.os.Handler
import android.os.Looper

/**
 * Marshals work onto the thread that owns the playback core. The production
 * implementation posts to the main looper; tests inject a manual or
 * synchronous poster for deterministic scheduling.
 */
fun interface Poster {
    fun post(block: () -> Unit)
}

class HandlerPoster(handler: Handler = Handler(Looper.getMainLooper())) : Poster {
    private val handlerRef = handler
    override fun post(block: () -> Unit) {
        handlerRef.post(block)
    }
}

/** Runs blocks inline on the calling thread — for tests and synchronous flows. */
class SynchronousPoster : Poster {
    override fun post(block: () -> Unit) {
        block()
    }
}
