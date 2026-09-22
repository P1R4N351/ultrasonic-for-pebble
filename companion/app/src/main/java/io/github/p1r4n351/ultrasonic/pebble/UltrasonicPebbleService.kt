// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/** Bound by the Pebble phone app while the watchapp is open; hands commands to the processor. */
class UltrasonicPebbleService : BasePebbleListenerService() {
  private val processor by lazy { CommandProcessor.get(this) }
  private val bridgeHost by lazy { UltrasonicBridgeHost(applicationContext) }

  /* The glasses bridge rides along with the watch session: this app has no
   * foreground service of its own, so while the watchapp is open is when the
   * process is reliably alive. It is a no-op when the switch is off. */
  override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
    MediaBridgeController.start(applicationContext, bridgeHost)
    if (watchappUUID != WatchLink.APP_UUID) return
    Log.i(TAG, "watchapp opened on ${watch.value}")
  }

  override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
    if (watchappUUID != WatchLink.APP_UUID) return
    Log.i(TAG, "watchapp closed on ${watch.value}")
    processor.release()
  }

  override fun onDestroy() {
    processor.release()
    super.onDestroy()
  }

  override suspend fun onMessageReceived(
    watchappUUID: UUID,
    data: PebbleDictionary,
    watch: WatchIdentifier,
  ): ReceiveResult {
    if (watchappUUID != WatchLink.APP_UUID) return ReceiveResult.Nack
    val cmd = Protocol.readInt(data, Keys.CMD)?.toInt() ?: return ReceiveResult.Nack
    // Ack first: the watch's outbox waits on this; replies travel as separate messages.
    processor.submit(
      cmd = cmd,
      arg = Protocol.readInt(data, Keys.ARG)?.toInt() ?: 0,
      listId = Protocol.readInt(data, Keys.LIST_ID)?.toInt() ?: 0,
      req = Protocol.readInt(data, Keys.REQ)?.toInt() ?: 0,
      from = watch,
    )
    return ReceiveResult.Ack
  }

  companion object {
    private const val TAG = "UltrasonicPebble"
  }
}
