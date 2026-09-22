// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.net.BindException

/**
 * Owns the loopback server's lifecycle and the switch that gates it.
 *
 * Default OFF, and it stays off until the user turns it on. While it is on,
 * any app on this phone can read what is playing and drive transport over
 * loopback; that is a reasonable thing to want for a pair of glasses and an
 * unreasonable thing to enable on someone's behalf.
 *
 * PORT COLLISION IS REPORTED, NOT SWALLOWED. Only one process can hold 8766.
 * If another installed app already serves this contract, this bind fails, and
 * a bridge that failed to bind while showing an "on" switch is the worst
 * outcome: the glasses still work, the user credits the wrong app, and nothing
 * on screen says which one is answering.
 * [lastError] carries that sentence to the status screen.
 */
object MediaBridgeController {
  private const val TAG = "MediaBridge"
  private const val PREFS = "media-bridge"
  private const val KEY_ENABLED = "enabled"

  private val lock = Any()
  private var server: MediaBridgeServer? = null

  @Volatile
  var lastError: String? = null
    private set

  val isRunning: Boolean
    get() = synchronized(lock) { server != null }

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

  /** Persist the switch and bring the server up or down to match. */
  fun setEnabled(
    context: Context,
    enabled: Boolean,
    host: MediaBridgeHost,
  ) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    if (enabled) start(context, host) else stop()
  }

  /**
   * Start the server if the switch is on and it is not already up.
   * @return true if it is running afterwards.
   */
  fun start(
    context: Context,
    host: MediaBridgeHost,
  ): Boolean =
    synchronized(lock) {
      if (!isEnabled(context)) return false
      if (server != null) return true
      lastError = null
      return try {
        server =
          MediaBridgeServer(
            MediaBridgeLogic.PORT,
            { isEnabled(context) },
            { host.state() },
            { action -> host.command(action) },
          ).apply { start() }
        true
      } catch (e: BindException) {
        Log.w(TAG, "port ${MediaBridgeLogic.PORT} is already held", e)
        lastError =
          "Port ${MediaBridgeLogic.PORT} is already in use by another app on this phone. " +
            "Glasses apps will be talking to that one, not this."
        server = null
        false
      } catch (e: Exception) {
        Log.e(TAG, "media bridge failed to start", e)
        lastError = "Could not start on port ${MediaBridgeLogic.PORT} (${e.message ?: e.javaClass.simpleName})."
        server = null
        false
      }
    }

  fun stop() =
    synchronized(lock) {
      server?.stop()
      server = null
    }

  /** One line for the status screen: what a user can act on. */
  fun statusLine(context: Context): String =
    when {
      !isEnabled(context) -> "Glasses bridge: off"
      isRunning -> "Glasses bridge: serving 127.0.0.1:${MediaBridgeLogic.PORT}"
      else -> "Glasses bridge: ON but not serving. ${lastError ?: "Not started yet."}"
    }
}

/** What the bridge needs from the app that hosts it. */
interface MediaBridgeHost {
  /** The player's current state, or null when there is no player. */
  fun state(): MediaBridgeLogic.State?

  /** Run one contract action; false when the player cannot do it. */
  fun command(action: String): Boolean
}
