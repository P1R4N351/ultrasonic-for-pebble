package io.github.p1r4n351.ultrasonic.pebble

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch and the companion share a wire contract; these fail if either side drifts. */
class ContractMatchesWatchappTest {
  private val packageJson = File(System.getProperty("watchappPackageJson")
    ?: error("watchappPackageJson system property not set"))
  private val protocolH = File(packageJson.parentFile, "src/c/protocol.h")

  private fun cDefines(): Map<String, Int> {
    val text = protocolH.readText()
    val enumEntry = Regex("""^\s*([A-Z][A-Z0-9_]+)\s*=\s*(\d+)\s*,""", RegexOption.MULTILINE)
    val define = Regex("""^#define\s+([A-Z][A-Z0-9_]+)\s+(\d+)\s*$""", RegexOption.MULTILINE)
    return (enumEntry.findAll(text) + define.findAll(text))
      .associate { it.groupValues[1] to it.groupValues[2].toInt() }
  }

  @Test
  fun messageKeysMatchPackageJson() {
    assertTrue("missing $packageJson", packageJson.isFile)
    val keys = Json.parseToJsonElement(packageJson.readText())
      .jsonObject["pebble"]!!.jsonObject["messageKeys"]!!.jsonObject
      .mapValues { it.value.jsonPrimitive.int }
    assertEquals(keys, Keys.ALL)
  }

  @Test
  fun commandsMatchProtocolH() {
    val c = cDefines()
    val kotlin = mapOf(
      "CMD_HELLO" to Cmd.HELLO, "CMD_PLAY_PAUSE" to Cmd.PLAY_PAUSE, "CMD_NEXT" to Cmd.NEXT,
      "CMD_PREV" to Cmd.PREV, "CMD_LOVE" to Cmd.LOVE, "CMD_SHUFFLE" to Cmd.SHUFFLE,
      "CMD_REPEAT" to Cmd.REPEAT, "CMD_VOL_UP" to Cmd.VOL_UP, "CMD_VOL_DOWN" to Cmd.VOL_DOWN,
      "CMD_BROWSE_ROOT" to Cmd.BROWSE_ROOT, "CMD_OPEN" to Cmd.OPEN, "CMD_PLAY_ITEM" to Cmd.PLAY_ITEM,
    )
    assertEquals(c.filterKeys { it.startsWith("CMD_") }, kotlin)
  }

  @Test
  fun statesMatchProtocolH() {
    val c = cDefines()
    val kotlin = mapOf(
      "NP_STATE_NONE" to NpState.NONE, "NP_STATE_PAUSED" to NpState.PAUSED,
      "NP_STATE_PLAYING" to NpState.PLAYING, "NP_STATE_BUFFERING" to NpState.BUFFERING,
      "NP_STATE_ENDED" to NpState.ENDED,
    )
    assertEquals(c.filterKeys { it.startsWith("NP_STATE_") }, kotlin)
  }

  @Test
  fun byteBudgetsLeaveRoomForTheWatchNul() {
    val c = cDefines()
    assertEquals(c["NP_TITLE_SIZE"]!! - 1, Limits.NP_TITLE)
    assertEquals(c["NP_LINE_SIZE"]!! - 1, Limits.NP_LINE)
    assertEquals(c["STATUS_SIZE"]!! - 1, Limits.STATUS)
    assertEquals(c["ITEM_LABEL_SIZE"]!! - 1, Limits.ITEM_LABEL)
    assertEquals(c["ITEM_SUB_SIZE"]!! - 1, Limits.ITEM_SUB)
    assertEquals(c["LIST_TITLE_SIZE"]!! - 1, Limits.LIST_TITLE)
    assertEquals(c["MAX_ITEMS"]!!, Limits.MAX_ITEMS)
  }

  @Test
  fun companionIsWhitelistedByTheWatchapp() {
    val pebble = Json.parseToJsonElement(packageJson.readText()).jsonObject["pebble"]!!.jsonObject
    assertEquals(WatchLink.APP_UUID.toString(), pebble["uuid"]!!.jsonPrimitive.content)
    val apps = pebble["companionApp"]!!.jsonObject["android"]!!.jsonObject["apps"].toString()
    assertTrue(apps, apps.contains("\"io.github.p1r4n351.ultrasonic.pebble\""))
  }
}
