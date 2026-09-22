package io.github.p1r4n351.ultrasonic.pebble

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drives the real NanoHTTPD server over a real loopback socket, which is how
 * the glasses will reach it. What a glasses app sees — status, headers, body —
 * is what is asserted; the player is a spy, so a transport failure cannot hide
 * behind a player failure.
 *
 * Plain JDK HTTP and plain JUnit: no Robolectric, no OkHttp. A public app's
 * test surface should not pull in a framework to make one GET.
 */
class MediaBridgeServerTest {
  private val port = 8899
  private var server: MediaBridgeServer? = null

  private var enabled = true
  private var state: MediaBridgeLogic.State? = null
  private val commands = mutableListOf<String>()
  private var commandResult = true
  private var commandThrows = false

  @Before
  fun setUp() {
    commands.clear()
    commandResult = true
    commandThrows = false
    enabled = true
    state =
      MediaBridgeLogic.State(
        hasItem = true,
        isPlaying = true,
        isBuffering = false,
        title = "Moonlight Sonata",
        artist = "Ludwig van Beethoven",
        album = "Piano Sonatas",
        positionMs = 61_000L,
        durationMs = 245_000L,
        packageName = "org.example.player",
        appLabel = "Player",
      )
    server =
      MediaBridgeServer(
        port,
        { enabled },
        { state },
        { action ->
          commands += action
          if (commandThrows) throw IllegalStateException("player is gone")
          commandResult
        },
      ).apply { start() }
  }

  @After
  fun tearDown() {
    server?.stop()
  }

  private data class Reply(val code: Int, val body: String, val headers: Map<String, String>)

  private fun get(path: String): Reply {
    val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
    conn.connectTimeout = 5_000
    conn.readTimeout = 5_000
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    val headers = HashMap<String, String>()
    for ((key, values) in conn.headerFields) {
      if (key != null) headers[key] = values.firstOrNull().orEmpty()
    }
    conn.disconnect()
    return Reply(code, body, headers)
  }

  @Test
  fun media_servesNowPlayingOverTheSocket() {
    val reply = get("/media")
    assertEquals(200, reply.code)
    val json = JSONObject(reply.body)
    assertTrue(json.getBoolean("enabled"))
    assertEquals("playing", json.getString("state"))
    assertEquals("Moonlight Sonata", json.getString("title"))
    assertEquals(61_000L, json.getLong("position"))
  }

  @Test
  fun everyResponseCarriesCorsAndNoStore() {
    // The glasses app is a WebView on a different origin; without these it
    // cannot read a single byte, and the failure appears as an empty lens.
    for (path in listOf("/media", "/health", "/media/next", "/nope")) {
      val reply = get(path)
      assertEquals(path, "*", reply.headers["Access-Control-Allow-Origin"])
      assertEquals(path, "no-store", reply.headers["Cache-Control"])
    }
  }

  @Test
  fun health_reportsTheSwitch() {
    enabled = false
    JSONObject(get("/health").body).let {
      assertTrue(it.getBoolean("ok"))
      assertFalse(it.getBoolean("media"))
    }
    enabled = true
    assertTrue(JSONObject(get("/health").body).getBoolean("media"))
  }

  @Test
  fun switchedOff_servesNothingAndRunsNothing() {
    enabled = false
    val media = JSONObject(get("/media").body)
    assertFalse(media.getBoolean("enabled"))
    assertFalse("a disabled bridge leaked now-playing", media.has("title"))
    val action = JSONObject(get("/media/next").body)
    assertFalse(action.getBoolean("ok"))
    assertTrue("a disabled bridge drove the player", commands.isEmpty())
  }

  @Test
  fun actions_reachThePlayerAndReportItsAnswer() {
    for (a in listOf("play", "pause", "playpause", "next", "prev", "volup", "voldown")) {
      val json = JSONObject(get("/media/$a").body)
      assertEquals(a, json.getString("action"))
      assertTrue(a, json.getBoolean("ok"))
    }
    assertEquals(7, commands.size)
    commandResult = false
    assertFalse(JSONObject(get("/media/next").body).getBoolean("ok"))
  }

  @Test
  fun unknownAction_isRefusedBeforeThePlayerSeesIt() {
    for (a in listOf("stop", "seek", "shutdown", "PLAY")) {
      val json = JSONObject(get("/media/$a").body)
      assertFalse("accepted $a", json.getBoolean("ok"))
    }
    assertTrue("an unknown action reached the player: $commands", commands.isEmpty())
  }

  @Test
  fun aPlayerThatThrows_isAFailedActionNotAFailedServer() {
    commandThrows = true
    val reply = get("/media/next")
    assertEquals(200, reply.code)
    assertFalse(JSONObject(reply.body).getBoolean("ok"))
  }

  @Test
  fun unknownPath_is404() {
    assertEquals(404, get("/nope").code)
  }

  @Test
  fun noPlayer_isAnAnswerNotACrash() {
    state = null
    val reply = get("/media")
    assertEquals(200, reply.code)
    assertEquals("none", JSONObject(reply.body).getString("state"))
  }
}
