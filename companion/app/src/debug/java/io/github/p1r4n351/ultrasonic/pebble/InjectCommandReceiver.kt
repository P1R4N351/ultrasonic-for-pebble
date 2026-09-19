// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DEBUG BUILDS ONLY. Feeds a watch command into the same CommandProcessor the watch uses,
 * so every phone-side path can be exercised on hardware from adb:
 *   adb shell am broadcast -n io.github.p1r4n351.ultrasonic.pebble/.InjectCommandReceiver \
 *     --ei cmd 10 --ei req 5
 * Guarded by android.permission.DUMP in the debug manifest: adb's shell holds it, apps do not.
 */
class InjectCommandReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val cmd = intent.getIntExtra("cmd", -1)
    if (cmd < 0) {
      Log.w(TAG, "inject: missing --ei cmd")
      return
    }
    Log.i(TAG, "inject cmd=$cmd")
    CommandProcessor.get(context).submit(
      cmd = cmd,
      arg = intent.getIntExtra("arg", 0),
      listId = intent.getIntExtra("list", 0),
      req = intent.getIntExtra("req", 0),
      from = null,
    )
  }

  companion object {
    private const val TAG = "UltrasonicPebble"
  }
}
