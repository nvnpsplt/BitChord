package com.music.bitchord.playback.cast

/**
 * A connection to one device that plays what the phone hands it — a Cast
 * receiver ([CastSession]) or a DLNA renderer ([DlnaSession]). The bridge
 * drives either the same way; the device's state comes back as a
 * [CastProtocol.MediaStatus], which a DLNA session synthesises from polling.
 *
 * Commands are called on the main thread, and every listener call arrives on
 * it, like everything else that touches the players.
 */
internal interface RemoteSession {

    interface Listener {
        fun onReady()
        fun onMediaStatus(status: CastProtocol.MediaStatus?)
        fun onVolume(level: Double, muted: Boolean)
        fun onLoadFailed(reason: String)
        fun onCustomMessage(namespace: String, payload: String) = Unit
        fun onClosed(error: String?)
    }

    /** The latest state of the one track the device has, or null when it has none. */
    val mediaStatus: CastProtocol.MediaStatus?

    /** The device's queue, in order; always empty where [supportsQueue] is false. */
    val queueItemIds: List<Int>

    /** Whether a next track can wait behind the current one ([queueNext], [queueSkip]). */
    val supportsQueue: Boolean

    /** Whether there is a receiver app for [sendCustom] to reach. */
    val supportsCustomMessages: Boolean

    val volumeLevel: Double
    val muted: Boolean
    val isReady: Boolean

    fun open()

    /** Ends the session. [stopApp] also stops the device playing. */
    fun close(stopApp: Boolean)

    fun load(request: CastProtocol.LoadRequest)
    fun queueNext(request: CastProtocol.LoadRequest)
    fun queueRemove(itemIds: List<Int>)
    fun queueSkip()
    fun play()
    fun pause()
    fun stopMedia()
    fun seek(positionMs: Long)
    fun setPlaybackRate(rate: Double)
    fun setVolume(level: Double)
    fun setMuted(muted: Boolean)
    fun sendCustom(namespace: String, payload: String)

    /** Where the device is now: its last report, run forward while it plays. */
    fun currentPositionMs(): Long
}
