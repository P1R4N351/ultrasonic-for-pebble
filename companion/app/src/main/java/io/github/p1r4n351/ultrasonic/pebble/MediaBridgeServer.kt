// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import fi.iki.elonen.NanoHTTPD

/**
 * Serves now-playing on http://127.0.0.1:8766 for Even Hub glasses apps.
 *
 *   GET /media           -> {"enabled":true,"playing":..,"state":..,"package":..,"app":..,
 *                            "title":..,"artist":..,"album":..,"position":ms,"duration":ms,"live":..}
 *                           or {"enabled":false} while the switch is off
 *   GET /media/<action>  -> {"ok":..,"action":..}   play|pause|playpause|next|prev|volup|voldown
 *   GET /health          -> {"ok":true,"media":..}
 *
 * Bound to 127.0.0.1, so it is never reachable off the phone: another device on
 * the same Wi-Fi cannot see it, and no INTERNET traffic leaves the handset.
 * CORS "*" and Cache-Control: no-store on every response, because the glasses
 * app is a WebView fetching from a different origin.
 *
 * Everything is injected: the server holds no player, no preferences and no
 * Android context, which is what lets the whole surface be exercised over a
 * real socket in a unit test.
 */
class MediaBridgeServer(
  port: Int,
  private val enabledProvider: () -> Boolean,
  private val stateProvider: () -> MediaBridgeLogic.State?,
  private val commandRunner: (String) -> Boolean,
) : NanoHTTPD("127.0.0.1", port) {
  override fun serve(session: IHTTPSession): Response {
    if (session.method == Method.OPTIONS) {
      return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
    }
    val uri = session.uri
    return when {
      uri == "/media" ->
        if (!enabledProvider()) {
          json(MediaBridgeLogic.disabledJson().toString())
        } else {
          json(MediaBridgeLogic.mediaJson(stateProvider()).toString())
        }

      uri.startsWith("/media/") -> commandRoute(uri)

      uri == "/health" -> json(MediaBridgeLogic.healthJson(enabledProvider()).toString())

      else ->
        cors(
          newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "application/json",
            "{\"error\":\"not found\"}",
          ),
        )
    }
  }

  private fun commandRoute(uri: String): Response {
    if (!enabledProvider()) return json("{\"ok\":false,\"error\":\"media disabled\"}")
    val action = uri.substringAfterLast('/')
    // Refused here, before the player sees it. The contract's seven actions are
    // the whole vocabulary; anything else is reported as a failed action rather
    // than as a 404, because the caller asked a well-formed question and the
    // answer is no.
    if (!MediaBridgeLogic.isAction(action)) {
      return json(MediaBridgeLogic.actionJson(false, action).toString())
    }
    val ok =
      try {
        commandRunner(action)
      } catch (e: Exception) {
        false
      }
    return json(MediaBridgeLogic.actionJson(ok, action).toString())
  }

  private fun json(text: String): Response = cors(newFixedLengthResponse(Response.Status.OK, "application/json", text))

  private fun cors(r: Response): Response {
    r.addHeader("Access-Control-Allow-Origin", "*")
    r.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
    r.addHeader("Access-Control-Allow-Headers", "*")
    r.addHeader("Cache-Control", "no-store")
    return r
  }
}
