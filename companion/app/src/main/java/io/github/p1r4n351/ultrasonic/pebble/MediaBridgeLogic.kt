// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import org.json.JSONObject

/**
 * Pure decisions for the loopback media bridge — no sockets, no Android — so
 * every rule is unit-testable on the JVM.
 *
 * The wire format is NOT invented here. It is the contract Even Hub glasses
 * apps already speak (`/media`, `/media/<action>`, `/health` on 127.0.0.1:8766),
 * so a pair of glasses that works with one provider works with this one
 * unchanged. Anything that drifts from it silently breaks a device the user
 * cannot debug, which is why the field names and the state vocabulary are
 * pinned by tests rather than left to the JSON builder.
 */
object MediaBridgeLogic {
  /** The port Even Hub companions look for. Not configurable on purpose: a
   *  configurable port is one the glasses app cannot guess. */
  const val PORT = 8766

  /** Exactly the actions the contract defines. An unknown action is refused
   *  rather than passed to the player — a bridge that forwards whatever it is
   *  handed is an open remote control for anything on the device. */
  val ACTIONS: Set<String> = setOf("play", "pause", "playpause", "next", "prev", "volup", "voldown")

  const val STATE_PLAYING = "playing"
  const val STATE_PAUSED = "paused"
  const val STATE_STOPPED = "stopped"
  const val STATE_BUFFERING = "buffering"
  const val STATE_NONE = "none"

  /**
   * What the bridge knows about the player right now. Assembled by each app's
   * adapter from whatever it already holds for the watch, so the bridge adds
   * no new player plumbing.
   *
   * Positions are MILLISECONDS, matching the contract. The watch wire format
   * in this same app carries SECONDS, and mixing them shows up as a progress
   * bar that is wrong by 1000x rather than as an error.
   */
  data class State(
    val hasItem: Boolean,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val positionMs: Long,
    val durationMs: Long,
    val packageName: String,
    val appLabel: String,
  )

  fun isAction(action: String?): Boolean = action != null && ACTIONS.contains(action)

  /** playing | paused | stopped | buffering | none */
  fun stateName(state: State?): String =
    when {
      state == null || !state.hasItem -> STATE_NONE
      state.isBuffering -> STATE_BUFFERING
      state.isPlaying -> STATE_PLAYING
      state.durationMs <= 0L && state.positionMs <= 0L -> STATE_STOPPED
      else -> STATE_PAUSED
    }

  /** The `/media` body while the bridge is on. */
  fun mediaJson(state: State?): JSONObject {
    val name = stateName(state)
    val json =
      JSONObject()
        .put("enabled", true)
        .put("playing", name == STATE_PLAYING)
        .put("state", name)
    if (state == null || !state.hasItem) {
      // Still answer every field: a glasses app that reads .title on a quiet
      // player should get an empty string, not undefined.
      return json
        .put("package", state?.packageName.orEmpty())
        .put("app", state?.appLabel.orEmpty())
        .put("title", "")
        .put("artist", "")
        .put("album", "")
        .put("position", 0)
        .put("duration", 0)
        .put("live", false)
    }
    return json
      .put("package", state.packageName)
      .put("app", state.appLabel)
      .put("title", state.title)
      .put("artist", state.artist)
      .put("album", state.album)
      .put("position", state.positionMs.coerceAtLeast(0L))
      .put("duration", state.durationMs.coerceAtLeast(0L))
      // A duration of zero with an item present is how a live or unbounded
      // stream presents itself through MediaSession.
      .put("live", state.durationMs <= 0L)
  }

  fun disabledJson(): JSONObject = JSONObject().put("enabled", false)

  fun actionJson(ok: Boolean, action: String): JSONObject = JSONObject().put("ok", ok).put("action", action)

  fun healthJson(enabled: Boolean): JSONObject = JSONObject().put("ok", true).put("media", enabled)
}
