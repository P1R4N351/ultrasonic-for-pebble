// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

/** Plain copy of the player fields the watch needs, so the mapping is testable off-device. */
data class PlayerSnapshot(
  val hasItem: Boolean,
  val title: String?,
  val artist: String?,
  val album: String?,
  val playbackState: Int,
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
  val heartOffOffered: Boolean,
  val heartOnOffered: Boolean,
  val metadataHeart: Boolean?,
  val shuffle: Boolean,
  val repeatMode: Int,
)

object NowPlayingMapper {
  // androidx.media3.common.Player.STATE_* values, duplicated so this file needs no Android.
  const val STATE_IDLE = 1
  const val STATE_BUFFERING = 2
  const val STATE_READY = 3
  const val STATE_ENDED = 4
  private const val TIME_UNSET = Long.MIN_VALUE + 1

  fun map(s: PlayerSnapshot, status: String = ""): NowPlaying {
    if (!s.hasItem) {
      return NowPlaying("Nothing playing", "Open Ultrasonic or browse", "", NpState.NONE,
        0, 0, loved = false, shuffle = s.shuffle, repeatMode = s.repeatMode, status = status)
    }
    return NowPlaying(
      title = s.title?.takeIf { it.isNotBlank() } ?: "Unknown title",
      artist = s.artist.orEmpty(),
      album = s.album.orEmpty(),
      state = state(s),
      positionSec = seconds(s.positionMs),
      durationSec = seconds(s.durationMs),
      loved = loved(s),
      shuffle = s.shuffle,
      repeatMode = s.repeatMode,
      status = status,
    )
  }

  private fun state(s: PlayerSnapshot): Int = when {
    s.playbackState == STATE_BUFFERING -> NpState.BUFFERING
    s.isPlaying -> NpState.PLAYING
    s.playbackState == STATE_ENDED -> NpState.ENDED
    else -> NpState.PAUSED
  }

  private fun seconds(ms: Long): Long = if (ms == TIME_UNSET || ms < 0) 0 else ms / 1000

  // Ultrasonic offers HEART_OFF only when the current track is starred, and HEART_ON only
  // when it is not; that button is its own authority on the state. Metadata is a fallback.
  private fun loved(s: PlayerSnapshot): Boolean = when {
    s.heartOffOffered -> true
    s.heartOnOffered -> false
    else -> s.metadataHeart ?: false
  }
}
