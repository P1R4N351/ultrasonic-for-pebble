// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import android.content.Context
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialised AppMessage sender. The watch inbox takes one message at a time, so every send
 * waits for the previous one's result; list items therefore arrive in index order.
 */
class WatchLink(context: Context) : AutoCloseable {
  private val sender = DefaultPebbleSender(context)
  private val lock = Mutex()

  suspend fun send(watch: WatchIdentifier?, data: PebbleDictionary): Boolean = lock.withLock {
    val results = sender.sendDataToPebble(APP_UUID, data, watch?.let { listOf(it) })
    if (results == null) {
      Log.w(TAG, "Pebble app unreachable (not installed, or not selected)")
      return@withLock false
    }
    val failures = results.filterValues { it != TransmissionResult.Success }
    failures.forEach { (w, r) -> Log.w(TAG, "send to ${w.value}: $r") }
    results.isNotEmpty() && failures.isEmpty()
  }

  override fun close() = sender.close()

  companion object {
    private const val TAG = "WatchLink"
    val APP_UUID: UUID = UUID.fromString("37f76f96-ec7a-432b-b558-9e7d6585d671")
  }
}
