// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Diagnostic screen: is each end of the chain present, and can we reach Ultrasonic's session? */
class StatusActivity : Activity() {
  private val scope = MainScope()
  private lateinit var report: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val pad = (16 * resources.displayMetrics.density).toInt()
    report = TextView(this).apply { textSize = 16f }
    val test = Button(this).apply {
      text = "Test Ultrasonic connection"
      setOnClickListener { runCheck() }
    }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad * 3, pad, pad)
      addView(report)
      addView(test)
    })
    runCheck()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun versionOf(pkg: String): String = try {
    packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
  } catch (e: PackageManager.NameNotFoundException) {
    "NOT INSTALLED"
  }

  private fun runCheck() {
    scope.launch {
      val picker = DefaultPebbleAndroidAppPicker.getInstance(this@StatusActivity)
      val lines = mutableListOf(
        "Ultrasonic: ${versionOf(UltrasonicBridge.ULTRASONIC_PACKAGE)}",
        "Pebble apps: ${picker.getAllEligibleApps().ifEmpty { listOf("NONE") }.joinToString()}",
        "Selected: ${picker.getCurrentlySelectedApp() ?: "NONE"}",
      )
      report.text = lines.joinToString("\n") + "\n\nConnecting to Ultrasonic..."
      val bridge = UltrasonicBridge(this@StatusActivity)
      val connected = bridge.connect(object : Player.Listener {})
      lines += if (connected) {
        val np = bridge.snapshot()?.let { NowPlayingMapper.map(it) }
        "Session: connected\nNow: ${np?.title ?: "?"} - ${np?.artist ?: "?"}\nLoved: ${np?.loved}"
      } else {
        "Session: NOT reachable"
      }
      bridge.release()
      report.text = lines.joinToString("\n")
    }
  }
}
