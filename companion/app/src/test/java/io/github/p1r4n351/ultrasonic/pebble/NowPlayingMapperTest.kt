package io.github.p1r4n351.ultrasonic.pebble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingMapperTest {
  private val base = PlayerSnapshot(
    hasItem = true, title = "Moonlight Sonata", artist = "Ludwig van Beethoven", album = "Piano Sonatas",
    playbackState = NowPlayingMapper.STATE_READY, isPlaying = true, positionMs = 83_999,
    durationMs = 245_000, heartOffOffered = false, heartOnOffered = true, metadataHeart = null,
    shuffle = false, repeatMode = 0,
  )

  @Test
  fun playingTrackMapsFieldsAndFloorsSeconds() {
    val np = NowPlayingMapper.map(base)
    assertEquals("Moonlight Sonata", np.title)
    assertEquals(NpState.PLAYING, np.state)
    assertEquals(83L, np.positionSec)
    assertEquals(245L, np.durationSec)
  }

  @Test
  fun ultrasonicsHeartButtonOutranksMetadata() {
    assertTrue(NowPlayingMapper.map(base.copy(heartOffOffered = true, heartOnOffered = false,
      metadataHeart = false)).loved)
    assertFalse(NowPlayingMapper.map(base.copy(heartOffOffered = false, heartOnOffered = true,
      metadataHeart = true)).loved)
    assertTrue(NowPlayingMapper.map(base.copy(heartOffOffered = false, heartOnOffered = false,
      metadataHeart = true)).loved)
  }

  @Test
  fun stateMapping() {
    assertEquals(NpState.BUFFERING, NowPlayingMapper.map(base.copy(
      playbackState = NowPlayingMapper.STATE_BUFFERING, isPlaying = false)).state)
    assertEquals(NpState.PAUSED, NowPlayingMapper.map(base.copy(isPlaying = false)).state)
    assertEquals(NpState.ENDED, NowPlayingMapper.map(base.copy(
      playbackState = NowPlayingMapper.STATE_ENDED, isPlaying = false)).state)
  }

  @Test
  fun unknownDurationIsZeroNotGarbage() {
    val np = NowPlayingMapper.map(base.copy(durationMs = Long.MIN_VALUE + 1))
    assertEquals(0L, np.durationSec)
  }

  @Test
  fun noItemIsNone() {
    val np = NowPlayingMapper.map(base.copy(hasItem = false))
    assertEquals(NpState.NONE, np.state)
    assertEquals("Nothing playing", np.title)
  }
}
