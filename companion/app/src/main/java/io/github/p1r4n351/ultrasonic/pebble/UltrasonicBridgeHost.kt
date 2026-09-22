// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import android.content.Context
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapts Ultrasonic's media session into the loopback bridge's contract, so a
 * pair of glasses reads exactly what the watch reads.
 *
 * Every call hops to the main thread, because MediaBrowser is main-thread-only
 * while NanoHTTPD answers each request on its own worker. The hop is bounded:
 * a player that stops answering must fail this request rather than hold a
 * socket open until the glasses give up.
 *
 * The connection is opened on first use and kept, because a glasses app polls
 * now-playing and reconnecting per request would be both slow and rude to the
 * player.
 */
class UltrasonicBridgeHost(
  private val context: Context,
) : MediaBridgeHost {
  private val callTimeoutMs = 2_000L
  private var bridge: UltrasonicBridge? = null

  override fun state(): MediaBridgeLogic.State? = onMain { stateOf(ensure()?.snapshot()) }

  override fun command(action: String): Boolean =
    onMain {
      val b = ensure() ?: return@onMain false
      when (action) {
        // play and pause both map to the toggle: this app drives a
        // MediaBrowser, whose transport IS a toggle, and pretending otherwise
        // would let "pause" start playback on a stopped player.
        "play", "pause", "playpause" -> b.playPause()
        "next" -> b.next()
        "prev" -> b.previous()
        "volup" -> b.adjustVolume(true)
        "voldown" -> b.adjustVolume(false)
        else -> false
      }
    } ?: false

  /** Release the player connection. Called when the bridge is switched off. */
  fun release() {
    val b = bridge ?: return
    bridge = null
    runBlocking { withTimeoutOrNull(callTimeoutMs) { withContext(Dispatchers.Main) { b.release() } } }
  }

  private suspend fun ensure(): UltrasonicBridge? {
    val existing = bridge
    if (existing != null && existing.isConnected) return existing
    val fresh = existing ?: UltrasonicBridge(context.applicationContext)
    bridge = fresh
    return if (fresh.connect(object : Player.Listener {})) fresh else null
  }

  private fun stateOf(s: PlayerSnapshot?): MediaBridgeLogic.State? {
    if (s == null) return null
    return MediaBridgeLogic.State(
      hasItem = s.hasItem,
      isPlaying = s.isPlaying,
      isBuffering = s.playbackState == Player.STATE_BUFFERING,
      title = s.title.orEmpty(),
      artist = s.artist.orEmpty(),
      album = s.album.orEmpty(),
      positionMs = s.positionMs,
      durationMs = s.durationMs,
      packageName = UltrasonicBridge.ULTRASONIC_PACKAGE,
      appLabel = TARGET_LABEL,
    )
  }

  private fun <T> onMain(block: suspend () -> T): T? =
    runBlocking {
      withTimeoutOrNull(callTimeoutMs) {
        withContext(Dispatchers.Main) { block() }
      }
    }

  companion object {
    const val TARGET_LABEL = "Ultrasonic"
  }
}
