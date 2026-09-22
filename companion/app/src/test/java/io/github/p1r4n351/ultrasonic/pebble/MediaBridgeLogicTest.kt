package io.github.p1r4n351.ultrasonic.pebble

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format is a contract with software this repo does not ship — Even
 * Hub glasses apps that already speak it. So these tests pin the field names
 * and the state vocabulary, not merely "some JSON comes out": a rename here is
 * silent on this side and fatal on a device the user cannot debug.
 */
class MediaBridgeLogicTest {
  private fun state(
    hasItem: Boolean = true,
    playing: Boolean = true,
    buffering: Boolean = false,
    position: Long = 61_000L,
    duration: Long = 245_000L,
  ) = MediaBridgeLogic.State(
    hasItem = hasItem,
    isPlaying = playing,
    isBuffering = buffering,
    title = "Moonlight Sonata",
    artist = "Ludwig van Beethoven",
    album = "Piano Sonatas",
    positionMs = position,
    durationMs = duration,
    packageName = "org.example.player",
    appLabel = "Player",
  )

  @Test
  fun mediaJson_carriesEveryFieldTheContractNames() {
    val json = MediaBridgeLogic.mediaJson(state())
    for (key in listOf(
      "enabled", "playing", "state", "package", "app",
      "title", "artist", "album", "position", "duration", "live",
    )) {
      assertTrue("contract field missing: $key", json.has(key))
    }
    assertTrue(json.getBoolean("enabled"))
    assertTrue(json.getBoolean("playing"))
    assertEquals("playing", json.getString("state"))
    assertEquals("Moonlight Sonata", json.getString("title"))
    assertEquals("Ludwig van Beethoven", json.getString("artist"))
    assertEquals("Piano Sonatas", json.getString("album"))
    assertEquals("org.example.player", json.getString("package"))
    assertEquals("Player", json.getString("app"))
  }

  @Test
  fun positionsAreMilliseconds_notTheWatchsSeconds() {
    // The watch wire format in this same app carries seconds. Mixing them is a
    // progress bar wrong by 1000x rather than an error, so it is pinned.
    val json = MediaBridgeLogic.mediaJson(state(position = 61_000L, duration = 245_000L))
    assertEquals(61_000L, json.getLong("position"))
    assertEquals(245_000L, json.getLong("duration"))
  }

  @Test
  fun stateVocabulary_isExactlyTheContracts() {
    assertEquals("playing", MediaBridgeLogic.stateName(state(playing = true)))
    assertEquals("paused", MediaBridgeLogic.stateName(state(playing = false)))
    assertEquals("buffering", MediaBridgeLogic.stateName(state(playing = false, buffering = true)))
    assertEquals("stopped", MediaBridgeLogic.stateName(state(playing = false, position = 0, duration = 0)))
    assertEquals("none", MediaBridgeLogic.stateName(state(hasItem = false)))
    assertEquals("none", MediaBridgeLogic.stateName(null))
  }

  @Test
  fun aQuietPlayerStillAnswersEveryField() {
    // A glasses app reading .title on a stopped player should get "", never
    // undefined — an undefined renders as the word "undefined" on a lens.
    val json = MediaBridgeLogic.mediaJson(state(hasItem = false))
    assertEquals("none", json.getString("state"))
    assertFalse(json.getBoolean("playing"))
    assertEquals("", json.getString("title"))
    assertEquals("", json.getString("artist"))
    assertEquals("", json.getString("album"))
    assertEquals(0L, json.getLong("position"))
    assertEquals(0L, json.getLong("duration"))
    assertFalse(json.getBoolean("live"))
  }

  @Test
  fun liveIsAnItemWithNoDuration() {
    assertTrue(MediaBridgeLogic.mediaJson(state(duration = 0L)).getBoolean("live"))
    assertFalse(MediaBridgeLogic.mediaJson(state(duration = 1L)).getBoolean("live"))
  }

  @Test
  fun actionAllowlist_isTheContractsSeven() {
    assertEquals(
      setOf("play", "pause", "playpause", "next", "prev", "volup", "voldown"),
      MediaBridgeLogic.ACTIONS,
    )
    for (a in MediaBridgeLogic.ACTIONS) assertTrue(a, MediaBridgeLogic.isAction(a))
    for (a in listOf("stop", "seek", "../media", "", "PLAY", "playpause ")) {
      assertFalse("accepted '$a'", MediaBridgeLogic.isAction(a))
    }
    assertFalse(MediaBridgeLogic.isAction(null))
  }

  @Test
  fun disabledAndHealth_matchTheBridgeConvention() {
    val disabled = MediaBridgeLogic.disabledJson()
    assertFalse(disabled.getBoolean("enabled"))
    assertFalse("a disabled body must not carry a screenful of state", disabled.has("title"))
    assertTrue(MediaBridgeLogic.healthJson(true).getBoolean("media"))
    assertFalse(MediaBridgeLogic.healthJson(false).getBoolean("media"))
    assertTrue(MediaBridgeLogic.healthJson(false).getBoolean("ok"))
  }

  @Test
  fun port_isTheOneGlassesAppsLookFor() {
    // Not configurable on purpose: a configurable port is one the glasses app
    // cannot guess.
    assertEquals(8766, MediaBridgeLogic.PORT)
  }

  @Test
  fun actionJson_reportsTheActionItWasAsked() {
    val json: JSONObject = MediaBridgeLogic.actionJson(false, "next")
    assertFalse(json.getBoolean("ok"))
    assertEquals("next", json.getString("action"))
  }
}
