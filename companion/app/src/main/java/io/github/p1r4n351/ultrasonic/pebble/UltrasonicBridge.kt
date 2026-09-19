// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A media3 controller connected to Ultrasonic's own PlaybackService. Everything the watch
 * does goes through Ultrasonic's session, so Ultrasonic keeps owning the server, the
 * credentials, the queue and the stars. Must be used from the main thread.
 */
class UltrasonicBridge(private val context: Context) {
  private var browser: MediaBrowser? = null

  val isConnected: Boolean get() = browser?.isConnected == true

  suspend fun connect(listener: Player.Listener): Boolean {
    if (isConnected) return true
    val token = SessionToken(context, ComponentName(ULTRASONIC_PACKAGE, PLAYBACK_SERVICE))
    val built = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
      runCatching { MediaBrowser.Builder(context, token).buildAsync().await() }
        .onFailure { Log.w(TAG, "connect to Ultrasonic failed", it) }
        .getOrNull()
    }
    if (built == null) {
      Log.w(TAG, "Ultrasonic session unavailable (not installed, or refused)")
      return false
    }
    built.addListener(listener)
    browser = built
    return true
  }

  fun release() {
    browser?.release()
    browser = null
  }

  fun snapshot(): PlayerSnapshot? {
    val b = browser ?: return null
    val md = b.mediaMetadata
    val offered = b.customLayout.mapNotNull { it.sessionCommand?.customAction }.toSet()
    return PlayerSnapshot(
      hasItem = b.currentMediaItem != null,
      title = md.title?.toString(),
      artist = (md.artist ?: md.albumArtist)?.toString(),
      album = md.albumTitle?.toString(),
      playbackState = b.playbackState,
      isPlaying = b.isPlaying,
      positionMs = b.currentPosition,
      durationMs = b.duration,
      heartOffOffered = HEART_OFF in offered,
      heartOnOffered = HEART_ON in offered,
      metadataHeart = (md.userRating as? HeartRating)?.takeIf { it.isRated }?.isHeart,
      shuffle = b.shuffleModeEnabled,
      repeatMode = b.repeatMode,
    )
  }

  fun playPause(): Boolean {
    val b = browser ?: return false
    if (b.isPlaying) {
      b.pause()
    } else {
      if (b.playbackState == Player.STATE_IDLE) b.prepare()
      b.play()
    }
    return true
  }

  fun next(): Boolean = browser?.let { it.seekToNext(); true } ?: false

  fun previous(): Boolean = browser?.let { it.seekToPrevious(); true } ?: false

  suspend fun toggleLove(): Boolean {
    val loved = snapshot()?.let { NowPlayingMapper.map(it).loved } ?: return false
    return custom(if (loved) HEART_OFF else HEART_ON)
  }

  suspend fun shuffleQueue(): Boolean = custom(SHUFFLE)

  suspend fun cycleRepeat(): Boolean = custom(REPEAT_MODE)

  private suspend fun custom(action: String): Boolean {
    val b = browser ?: return false
    val result = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
      runCatching { b.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY).await() }
        .onFailure { Log.w(TAG, "custom command $action failed", it) }
        .getOrNull()
    }
    return result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS
  }

  fun adjustVolume(up: Boolean): Boolean {
    val audio = context.getSystemService(AudioManager::class.java) ?: return false
    val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    return true
  }

  /** Children of [parentId], or of the library root when null. Null on failure. */
  suspend fun children(parentId: String?): List<BrowseItem>? {
    val b = browser ?: return null
    return withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
      runCatching {
        val parent = parentId ?: b.getLibraryRoot(null).await().value?.mediaId ?: return@runCatching null
        val result = b.getChildren(parent, 0, Limits.MAX_ITEMS, null).await()
        if (result.resultCode != LibraryResult.RESULT_SUCCESS) null else result.value?.map(::toItem)
      }.onFailure { Log.w(TAG, "browse $parentId failed", it) }.getOrNull()
    }
  }

  fun play(item: BrowseItem): Boolean {
    val b = browser ?: return false
    b.setMediaItem(MediaItem.Builder().setMediaId(item.mediaId).build())
    b.prepare()
    b.play()
    return true
  }

  private fun toItem(m: MediaItem): BrowseItem {
    val md = m.mediaMetadata
    val sub = md.artist ?: md.subtitle ?: md.albumTitle
    val (browsable, playable) = UltrasonicIds.flags(m.mediaId, md.isBrowsable, md.isPlayable)
    return BrowseItem(
      mediaId = m.mediaId,
      label = md.title?.toString()?.takeIf { it.isNotBlank() } ?: "(untitled)",
      sub = sub?.toString().orEmpty(),
      browsable = browsable,
      playable = playable,
    )
  }

  companion object {
    private const val TAG = "UltrasonicBridge"
    const val ULTRASONIC_PACKAGE = "org.moire.ultrasonic"
    const val PLAYBACK_SERVICE = "org.moire.ultrasonic.service.PlaybackService"
    // Ultrasonic 4.8.0 PlaybackService.CUSTOM_COMMAND_* (read from its source, tag 4.8.0).
    const val HEART_ON = "org.moire.ultrasonic.HEART_ON"
    const val HEART_OFF = "org.moire.ultrasonic.HEART_OFF"
    const val SHUFFLE = "org.moire.ultrasonic.SHUFFLE"
    const val REPEAT_MODE = "org.moire.ultrasonic.REPEAT_MODE"
    // Headroom for a cold Ultrasonic start (service launch + library init). Unmeasured: the
    // single 8 s timeout seen on hardware was this process being frozen, not Ultrasonic.
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val COMMAND_TIMEOUT_MS = 5_000L
    private const val BROWSE_TIMEOUT_MS = 20_000L
  }
}
