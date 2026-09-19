package io.github.p1r4n351.ultrasonic.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
  private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

  @Test
  fun asciiTruncatesAtTheByteBudget() {
    assertEquals("abcde", Protocol.utf8Truncate("abcdefgh", 5))
    assertEquals("abc", Protocol.utf8Truncate("abc", 5))
    assertEquals("", Protocol.utf8Truncate("abc", 0))
  }

  @Test
  fun multibyteIsNeverSplit() {
    // "Rahmān": ā is 2 bytes; a 5-byte budget must stop before it, not cut it in half.
    assertEquals("Rahm", Protocol.utf8Truncate("Rahmān", 5))
    assertEquals("Rahmā", Protocol.utf8Truncate("Rahmān", 6))
    // Em dash is 3 bytes.
    assertEquals("a", Protocol.utf8Truncate("a—b", 3))
    assertEquals("a—", Protocol.utf8Truncate("a—b", 4))
  }

  @Test
  fun surrogatePairsStayWhole() {
    val s = "x🎵y" // x, U+1F3B5 (4 bytes), y
    assertEquals("x", Protocol.utf8Truncate(s, 4))
    assertEquals("x🎵", Protocol.utf8Truncate(s, 5))
  }

  @Test
  fun truncationNeverExceedsBudgetAcrossWidths() {
    val s = "aā—🎵".repeat(20)
    for (budget in 0..64) {
      val out = Protocol.utf8Truncate(s, budget)
      assertTrue("budget $budget -> ${bytes(out)}", bytes(out) <= budget)
      assertTrue(s.startsWith(out))
    }
  }

  @Test
  fun nowPlayingFieldsAreClampedAndTruncated() {
    val np = NowPlaying("T".repeat(200), "A".repeat(200), "B", NpState.PLAYING,
      positionSec = -5, durationSec = 1L shl 40, loved = true, shuffle = true,
      repeatMode = 2, status = "")
    val d = Protocol.nowPlaying(np)
    assertEquals(Limits.NP_TITLE, bytes((d[Keys.NP_TITLE.toUInt()] as PebbleDictionaryItem.Text).value))
    assertEquals(Limits.NP_LINE, bytes((d[Keys.NP_ARTIST.toUInt()] as PebbleDictionaryItem.Text).value))
    assertEquals(0u, (d[Keys.NP_POS.toUInt()] as PebbleDictionaryItem.UInt32).value)
    assertEquals(0xFFFF_FFFFu, (d[Keys.NP_DUR.toUInt()] as PebbleDictionaryItem.UInt32).value)
    assertEquals((0x01 or 0x02 or (2 shl 2)).toUByte(), (d[Keys.NP_FLAGS.toUInt()] as PebbleDictionaryItem.UInt8).value)
  }

  @Test
  fun flagsEncodeEachRepeatMode() {
    val base = NowPlaying("t", "a", "b", 0, 0, 0, false, false, 0, "")
    assertEquals(0, Protocol.flags(base))
    assertEquals(1 shl 2, Protocol.flags(base.copy(repeatMode = 1)))
    assertEquals(2 shl 2, Protocol.flags(base.copy(repeatMode = 2)))
    assertEquals(2 shl 2, Protocol.flags(base.copy(repeatMode = 9)))
  }

  @Test
  fun listItemFlags() {
    val d = Protocol.listItem(3, 7, BrowseItem("id", "Guru", "A. R. Rahman", true, true))
    assertEquals(3.toUShort(), (d[Keys.LIST_ID.toUInt()] as PebbleDictionaryItem.UInt16).value)
    assertEquals(7.toUShort(), (d[Keys.ITEM_INDEX.toUInt()] as PebbleDictionaryItem.UInt16).value)
    assertEquals(3.toUByte(), (d[Keys.ITEM_FLAGS.toUInt()] as PebbleDictionaryItem.UInt8).value)
  }

  @Test
  fun listHeaderCapsCountAtWatchCapacity() {
    val d = Protocol.listHeader(1, 9, "Albums", 100)
    assertEquals(Limits.MAX_ITEMS.toUShort(), (d[Keys.LIST_COUNT.toUInt()] as PebbleDictionaryItem.UInt16).value)
  }

  @Test
  fun readIntAcceptsWhatTheWatchSends() {
    val d = mapOf(Keys.CMD.toUInt() to PebbleDictionaryItem.UInt32(12u),
      Keys.ARG.toUInt() to PebbleDictionaryItem.Int32(4))
    assertEquals(12L, Protocol.readInt(d, Keys.CMD))
    assertEquals(4L, Protocol.readInt(d, Keys.ARG))
    assertNull(Protocol.readInt(d, Keys.REQ))
  }
}
