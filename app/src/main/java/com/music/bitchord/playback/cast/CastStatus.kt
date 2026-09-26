package com.music.bitchord.playback.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where playback is going, as far as Google Cast is concerned — read by the
 * player's output line and the output sheet, written by the service.
 *
 * In-process state rather than something carried over the session: the UI and
 * [com.music.bitchord.playback.PlaybackService] share a process, the same way
 * [com.music.bitchord.playback.AudioOutputStatus] is shared.
 */
object CastStatus {

    enum class Phase {
        /** Playing on this phone. */
        IDLE,

        /** A cast device was picked and the session is being set up. */
        CONNECTING,

        /** The cast device is playing; this phone is the remote. */
        CASTING,
    }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        /** The receiver's own name, e.g. "Living Room TV". */
        val deviceName: String? = null,
    ) {
        val isCasting: Boolean get() = phase == Phase.CASTING
        val isActive: Boolean get() = phase != Phase.IDLE
    }

    private val state = MutableStateFlow(Snapshot())
    val current: StateFlow<Snapshot> = state.asStateFlow()

    internal fun publish(snapshot: Snapshot) {
        state.value = snapshot
    }

    /**
     * A one-line message for the listener, e.g. why a track was skipped on the
     * receiver. Consumed by the UI with [consumeNotice].
     */
    private val notice = MutableStateFlow<Int?>(null)
    val pendingNotice: StateFlow<Int?> = notice.asStateFlow()

    internal fun showNotice(messageRes: Int) {
        notice.value = messageRes
    }

    fun consumeNotice() {
        notice.value = null
    }
}
