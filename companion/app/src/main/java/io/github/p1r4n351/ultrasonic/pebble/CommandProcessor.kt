// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One per process: the watch's list ids must resolve the same way whichever entry point
 * delivered the command. Commands run one at a time, in arrival order, on the main thread.
 */
class CommandProcessor private constructor(context: Context) {
  private val scope = MainScope()
  private val bridge = UltrasonicBridge(context)
  private val link = WatchLink(context)
  private val registry = ListRegistry()
  private val handleLock = Mutex()
  private var watch: WatchIdentifier? = null
  private var pushJob: Job? = null

  private val playerListener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) = schedulePush()
  }

  fun submit(cmd: Int, arg: Int, listId: Int, req: Int, from: WatchIdentifier?) {
    if (from != null) watch = from
    scope.launch { handleLock.withLock { handle(cmd, arg, listId, req) } }
  }

  fun release() {
    pushJob?.cancel()
    bridge.release()
  }

  private suspend fun handle(cmd: Int, arg: Int, listId: Int, req: Int) {
    Log.i(TAG, "cmd=$cmd arg=$arg list=$listId req=$req")
    if (!bridge.connect(playerListener)) {
      link.send(watch, Protocol.nowPlaying(unreachable()))
      if (req != 0) link.send(watch, Protocol.listHeader(ERROR_LIST, req, "Unavailable", 0))
      return
    }
    val ok = dispatch(cmd, arg, listId, req)
    Log.i(TAG, "cmd=$cmd req=$req result=$ok")
    if (!ok) {
      link.send(watch, Protocol.status("Ultrasonic refused that"))
    } else if (cmd == Cmd.HELLO || cmd == Cmd.LOVE || cmd == Cmd.REPEAT) {
      pushNowPlaying()
    }
  }

  private suspend fun dispatch(cmd: Int, arg: Int, listId: Int, req: Int): Boolean = when (cmd) {
    Cmd.HELLO -> true
    Cmd.PLAY_PAUSE -> bridge.playPause()
    Cmd.NEXT -> bridge.next()
    Cmd.PREV -> bridge.previous()
    Cmd.LOVE -> bridge.toggleLove()
    Cmd.SHUFFLE -> bridge.shuffleQueue()
    Cmd.REPEAT -> bridge.cycleRepeat()
    Cmd.VOL_UP -> bridge.adjustVolume(up = true)
    Cmd.VOL_DOWN -> bridge.adjustVolume(up = false)
    Cmd.BROWSE_ROOT -> sendList(null, "Ultrasonic", req)
    Cmd.OPEN -> open(listId, arg, req)
    Cmd.PLAY_ITEM -> playItem(listId, arg)
    else -> false.also { Log.w(TAG, "unknown cmd $cmd") }
  }

  private suspend fun open(listId: Int, index: Int, req: Int): Boolean {
    val item = registry.item(listId, index)
    if (item == null) {
      link.send(watch, Protocol.listHeader(ERROR_LIST, req, "List expired", 0))
      return false
    }
    return sendList(item.mediaId, item.label, req)
  }

  private suspend fun sendList(parentId: String?, title: String, req: Int): Boolean {
    val items = bridge.children(parentId)
    if (items == null) {
      link.send(watch, Protocol.listHeader(ERROR_LIST, req, "Browse failed", 0))
      return false
    }
    val id = registry.register(items)
    val shown = items.take(Limits.MAX_ITEMS)
    val label = if (items.size > shown.size) "$title (${shown.size}+)" else title
    Log.i(TAG, "list $id req=$req '$label': ${shown.size} of ${items.size} -> " +
      shown.take(LOG_ITEMS).joinToString {
        "${it.label}[${if (it.browsable) "b" else ""}${if (it.playable) "p" else ""}]" +
          "{${UltrasonicIds.kind(it.mediaId)}}"
      })
    if (!link.send(watch, Protocol.listHeader(id, req, label, shown.size))) return false
    shown.forEachIndexed { i, item ->
      if (!link.send(watch, Protocol.listItem(id, i, item))) return false
    }
    return true
  }

  private fun playItem(listId: Int, index: Int): Boolean {
    val item = registry.item(listId, index) ?: return false
    Log.i(TAG, "play '${item.label}' (${item.mediaId.substringBefore('|')})")
    return bridge.play(item)
  }

  private fun unreachable() = NowPlaying("Ultrasonic", "Not reachable on this phone", "",
    NpState.NONE, 0, 0, loved = false, shuffle = false, repeatMode = 0,
    status = "Is Ultrasonic installed?")

  private fun schedulePush() {
    pushJob?.cancel()
    pushJob = scope.launch {
      delay(PUSH_DEBOUNCE_MS)
      pushNowPlaying()
    }
  }

  private suspend fun pushNowPlaying() {
    val snap = bridge.snapshot() ?: return
    val np = NowPlayingMapper.map(snap)
    Log.i(TAG, "push np '${np.title}' state=${np.state} pos=${np.positionSec}/${np.durationSec} " +
      "loved=${np.loved} repeat=${np.repeatMode} shuffle=${np.shuffle}")
    link.send(watch, Protocol.nowPlaying(np))
  }

  companion object {
    private const val TAG = "UltrasonicPebble"
    private const val PUSH_DEBOUNCE_MS = 250L
    private const val LOG_ITEMS = 6
    // Error headers need a non-zero id: the watch treats list id 0 as "no list".
    private const val ERROR_LIST = 0xFFFF

    @Volatile private var instance: CommandProcessor? = null

    fun get(context: Context): CommandProcessor = instance ?: synchronized(this) {
      instance ?: CommandProcessor(context.applicationContext).also { instance = it }
    }
  }
}
