package com.prakash.pmeeting.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import androidx.core.net.toUri
import com.prakash.pmeeting.data.model.Recording
import com.prakash.pmeeting.data.model.StorageKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlayerState(
    val isPrepared: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
    /**
     * Video width / height reported by the platform, or 0 for audio (and for
     * containers that do not expose it). Used to aspect-fit full-screen video
     * without stretching.
     */
    val videoAspectRatio: Float = 0f
)

/**
 * Platform [MediaPlayer] controller used by the detail screen for both audio
 * and video playback. Keeps zero external playback dependencies; errors are
 * surfaced through [PlayerState.error] (this also covers imported files that
 * have become inaccessible).
 */
class MediaPlayback(private val context: Context) {

    private var player: MediaPlayer? = null
    private var loadedRecording: Recording? = null
    private var videoSurface: Surface? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * Attaches the surface used for video. The same MediaPlayer instance is
     * kept while switching between the normal preview and the full-screen
     * player, so playback never restarts and the position is preserved.
     */
    fun setVideoSurface(surface: Surface) {
        videoSurface = surface
        if (surface.isValid) {
            runCatching { player?.setSurface(surface) }
        }
    }

    /**
     * Detaches [surface] only if it is still the surface in use. When switching
     * between two SurfaceViews the new surface can be created before the old
     * one reports destruction; ignoring the stale callback keeps video visible.
     */
    fun clearVideoSurface(surface: Surface) {
        if (videoSurface !== surface) return
        videoSurface = null
        runCatching { player?.setSurface(null) }
    }

    /**
     * Prepares (first call) and toggles play/pause. Positions reported by the
     * platform are authoritative; the fallback [Recording.durationMs] is used
     * to drive the UI when the container does not expose a duration.
     */
    fun togglePlayPause(recording: Recording) {
        val current = player
        if (current != null && loadedRecording == recording) {
            if (_state.value.error != null) return
            // While prepareAsync() is in flight the player is in an invalid
            // state: start()/pause() throw IllegalStateException and
            // currentPosition throws too. Ignore the toggle until prepared;
            // onPrepared starts playback automatically, so no tap is lost.
            if (!_state.value.isPrepared) return
            val playing = runCatching {
                if (current.isPlaying) {
                    current.pause()
                    false
                } else {
                    current.start()
                    true
                }
            }.getOrElse { t ->
                _state.value = _state.value.copy(
                    error = "Playback failed: ${t.message ?: "invalid player state"}"
                )
                return
            }
            _state.update { it.copy(isPlaying = playing) }
            return
        }
        release()
        _state.value = PlayerState()
        try {
            val newPlayer = MediaPlayer()
            newPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(newPlayer, recording)
            if (recording.mediaType == com.prakash.pmeeting.data.model.MediaType.VIDEO) {
                videoSurface?.let { if (it.isValid) newPlayer.setSurface(it) }
            }
            newPlayer.setOnPreparedListener { prepared ->
                prepared.isLooping = false
                val duration = recording.durationMs.takeIf { it > 0L }
                    ?: prepared.duration.takeIf { it > 0L }?.toLong() ?: 0L
                val aspectRatio = if (prepared.videoWidth > 0 && prepared.videoHeight > 0) {
                    prepared.videoWidth.toFloat() / prepared.videoHeight.toFloat()
                } else {
                    0f
                }
                runCatching { prepared.start() }
                _state.update {
                    it.copy(
                        isPrepared = true,
                        isPlaying = true,
                        durationMs = duration,
                        videoAspectRatio = aspectRatio,
                        error = null
                    )
                }
            }
            newPlayer.setOnErrorListener { _, what, extra ->
                _state.value = PlayerState(
                    error = "Playback failed (code $what/$extra). The file may be missing or in an unsupported format."
                )
                true
            }
            newPlayer.setOnCompletionListener {
                _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
            }
            newPlayer.prepareAsync()
            player = newPlayer
            loadedRecording = recording
        } catch (t: Exception) {
            _state.value = PlayerState(
                error = "This recording could not be opened: ${t.message ?: "unknown error"}"
            )
            runCatching { player?.release() }
            player = null
            loadedRecording = null
        }
    }

    fun seekTo(positionMs: Long) {
        val current = player ?: return
        // seekTo is only valid once prepared; guard the invalid state.
        if (!_state.value.isPrepared) return
        runCatching { current.seekTo(positionMs.toInt()) }
        _state.update { it.copy(positionMs = positionMs) }
    }

    /** Poll current position; called periodically from the UI layer. */
    fun refreshPosition() {
        val current = player ?: return
        if (!_state.value.isPrepared) return
        runCatching {
            _state.update {
                it.copy(
                    positionMs = current.currentPosition.toLong().coerceAtLeast(0L),
                    isPlaying = current.isPlaying
                )
            }
        }
    }

    fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        loadedRecording = null
        _state.value = PlayerState()
    }

    private fun setDataSource(player: MediaPlayer, recording: Recording) {
        if (recording.storageKind == StorageKind.INTERNAL_FILE) {
            player.setDataSource(recording.fileUri)
        } else {
            player.setDataSource(context, recording.fileUri.toUri())
        }
    }
}
