package io.github.p1r4n351.ultrasonic.pebble

import org.junit.Assert.assertEquals
import org.junit.Test

class UltrasonicIdsTest {
  private val both = true to true
  private val browseOnly = true to false
  private val playOnly = false to true
  private val neither = false to false

  @Test
  fun albumWithSilentMetadataIsBrowsableAndPlayable() {
    // Hardware shape, 2026-09-18: album rows carry neither flag.
    assertEquals(both, UltrasonicIds.flags("MEDIA_ALBUM_ITEM|al-123|Guru", null, null))
    assertEquals(both, UltrasonicIds.flags("MEDIA_ALBUM_ITEM|al-123|Guru", false, false))
  }

  @Test
  fun playAllRowPlaysOnSelectDespiteSharingTheAlbumKind() {
    // Hardware shape: "Play All" is MEDIA_ALBUM_ITEM but metadata says playable only.
    assertEquals(playOnly, UltrasonicIds.flags("MEDIA_ALBUM_ITEM|al-123|Guru", false, true))
    assertEquals(playOnly, UltrasonicIds.flags("MEDIA_ALBUM_ITEM|al-123|Guru", null, true))
  }

  @Test
  fun songWithSilentMetadataIsPlayableOnly() {
    assertEquals(playOnly, UltrasonicIds.flags("MEDIA_ALBUM_SONG_ITEM|al|Guru|tr-9", null, null))
  }

  @Test
  fun foldersWithSilentMetadataAreBrowsableOnly() {
    assertEquals(browseOnly, UltrasonicIds.flags("MEDIA_ALBUM_NEWEST_ID", null, null))
    assertEquals(browseOnly, UltrasonicIds.flags("MEDIA_ARTIST_SECTION|A", null, null))
  }

  @Test
  fun explicitMetadataIsTrustedOverTheKind() {
    assertEquals(browseOnly, UltrasonicIds.flags("MEDIA_LIBRARY_ID", true, false))
    assertEquals(browseOnly, UltrasonicIds.flags("MEDIA_ALBUM_ITEM|x|y", true, null))
  }

  @Test
  fun unknownKindWithSilentMetadataIsInert() {
    assertEquals(neither, UltrasonicIds.flags("SOMETHING_NEW|x", null, null))
    assertEquals(both, UltrasonicIds.flags("SOMETHING_NEW|x", true, true))
  }
}
