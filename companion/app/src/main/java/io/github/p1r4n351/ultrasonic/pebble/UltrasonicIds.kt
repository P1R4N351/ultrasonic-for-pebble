// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

/**
 * Ultrasonic's media-id grammar: "<KIND>|<id>|<name>...". Measured on hardware: album entries
 * reach a controller with isBrowsable/isPlayable unset, so metadata alone would leave them
 * inert on the watch. The kind prefix is authoritative instead; both tables are copied from
 * Ultrasonic 4.8.0 MediaLibrarySessionCallback (onLoadChildren / onAddLegacyAutoItems).
 */
object UltrasonicIds {
  private val BROWSABLE = setOf(
    "MEDIA_ROOT_ID", "MEDIA_LIBRARY_ID", "MEDIA_ARTIST_ID", "MEDIA_ARTIST_SECTION",
    "MEDIA_ALBUM_ID", "MEDIA_ALBUM_PAGE_ID", "MEDIA_PLAYLIST_ID", "MEDIA_ALBUM_FREQUENT_ID",
    "MEDIA_ALBUM_NEWEST_ID", "MEDIA_ALBUM_RECENT_ID", "MEDIA_ALBUM_RANDOM_ID",
    "MEDIA_ALBUM_STARRED_ID", "MEDIA_SONG_RANDOM_ID", "MEDIA_SONG_STARRED_ID", "MEDIA_SHARE_ID",
    "MEDIA_BOOKMARK_ID", "MEDIA_PODCAST_ID", "MEDIA_PLAYLIST_ITEM", "MEDIA_ARTIST_ITEM",
    "MEDIA_ALBUM_ITEM", "MEDIA_SHARE_ITEM", "MEDIA_PODCAST_ITEM",
  )

  private val PLAYABLE = setOf(
    "MEDIA_PLAYLIST_ITEM", "MEDIA_PLAYLIST_SONG_ITEM", "MEDIA_ALBUM_ITEM", "MEDIA_ALBUM_SONG_ITEM",
    "MEDIA_SONG_STARRED_ID", "MEDIA_SONG_STARRED_ITEM", "MEDIA_SONG_RANDOM_ID",
    "MEDIA_SONG_RANDOM_ITEM", "MEDIA_SHARE_ITEM", "MEDIA_SHARE_SONG_ITEM", "MEDIA_BOOKMARK_ITEM",
    "MEDIA_PODCAST_ITEM", "MEDIA_PODCAST_EPISODE_ITEM", "MEDIA_SEARCH_SONG_ITEM",
  )

  fun kind(mediaId: String): String = mediaId.substringBefore('|')

  /**
   * (browsable, playable). Metadata wins whenever it asserts anything: "Play All" shares the
   * album's MEDIA_ALBUM_ITEM kind but is flagged playable-only, and must play on SELECT
   * rather than re-open the album. Only when metadata is silent (album rows) does the kind
   * decide.
   */
  fun flags(mediaId: String, mdBrowsable: Boolean?, mdPlayable: Boolean?): Pair<Boolean, Boolean> {
    if (mdBrowsable == true || mdPlayable == true) {
      return (mdBrowsable == true) to (mdPlayable == true)
    }
    val k = kind(mediaId)
    return (k in BROWSABLE) to (k in PLAYABLE)
  }
}
