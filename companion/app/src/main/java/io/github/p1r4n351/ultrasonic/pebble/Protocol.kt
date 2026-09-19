// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

/** AppMessage keys. Must equal watchapp/package.json messageKeys (KeysMatchWatchappTest). */
object Keys {
  const val CMD = 1
  const val ARG = 2
  const val LIST_ID = 3
  const val REQ = 4
  const val NP_TITLE = 10
  const val NP_ARTIST = 11
  const val NP_ALBUM = 12
  const val NP_STATE = 13
  const val NP_POS = 14
  const val NP_DUR = 15
  const val NP_FLAGS = 16
  const val STATUS = 17
  const val LIST_TITLE = 20
  const val LIST_COUNT = 21
  const val ITEM_INDEX = 22
  const val ITEM_LABEL = 23
  const val ITEM_SUB = 24
  const val ITEM_FLAGS = 25

  val ALL: Map<String, Int> = mapOf(
    "CMD" to CMD, "ARG" to ARG, "LIST_ID" to LIST_ID, "REQ" to REQ,
    "NP_TITLE" to NP_TITLE, "NP_ARTIST" to NP_ARTIST, "NP_ALBUM" to NP_ALBUM,
    "NP_STATE" to NP_STATE, "NP_POS" to NP_POS, "NP_DUR" to NP_DUR, "NP_FLAGS" to NP_FLAGS,
    "STATUS" to STATUS, "LIST_TITLE" to LIST_TITLE, "LIST_COUNT" to LIST_COUNT,
    "ITEM_INDEX" to ITEM_INDEX, "ITEM_LABEL" to ITEM_LABEL, "ITEM_SUB" to ITEM_SUB,
    "ITEM_FLAGS" to ITEM_FLAGS,
  )
}

object Cmd {
  const val HELLO = 1
  const val PLAY_PAUSE = 2
  const val NEXT = 3
  const val PREV = 4
  const val LOVE = 5
  const val SHUFFLE = 6
  const val REPEAT = 7
  const val VOL_UP = 8
  const val VOL_DOWN = 9
  const val BROWSE_ROOT = 10
  const val OPEN = 11
  const val PLAY_ITEM = 12
}

object NpState {
  const val NONE = 0
  const val PAUSED = 1
  const val PLAYING = 2
  const val BUFFERING = 3
  const val ENDED = 4
}

/** Byte budgets = watch buffer size - 1 (NUL). Mirrors watchapp/src/c/protocol.h. */
object Limits {
  const val NP_TITLE = 63
  const val NP_LINE = 47
  const val STATUS = 63
  const val ITEM_LABEL = 39
  const val ITEM_SUB = 31
  const val LIST_TITLE = 31
  const val MAX_ITEMS = 40
}

data class NowPlaying(
  val title: String,
  val artist: String,
  val album: String,
  val state: Int,
  val positionSec: Long,
  val durationSec: Long,
  val loved: Boolean,
  val shuffle: Boolean,
  val repeatMode: Int,
  val status: String,
)

data class BrowseItem(
  val mediaId: String,
  val label: String,
  val sub: String,
  val browsable: Boolean,
  val playable: Boolean,
)

object Protocol {
  private const val FLAG_LOVED = 0x01
  private const val FLAG_SHUFFLE = 0x02
  private const val REPEAT_SHIFT = 2
  private const val ITEM_BROWSABLE = 0x01
  private const val ITEM_PLAYABLE = 0x02
  private const val U32_MAX = 0xFFFF_FFFFL

  /** Longest prefix of [s] whose UTF-8 encoding fits [maxBytes]; never splits a code point. */
  fun utf8Truncate(s: String, maxBytes: Int): String {
    require(maxBytes >= 0) { "maxBytes must be >= 0" }
    var bytes = 0
    var end = 0
    while (end < s.length) {
      val cp = s.codePointAt(end)
      val width = utf8Width(cp)
      if (bytes + width > maxBytes) break
      bytes += width
      end += Character.charCount(cp)
    }
    return s.substring(0, end)
  }

  private fun utf8Width(cp: Int): Int = when {
    cp < 0x80 -> 1
    cp < 0x800 -> 2
    cp < 0x10000 -> 3
    else -> 4
  }

  private fun text(s: String, max: Int) = PebbleDictionaryItem.Text(utf8Truncate(s, max))

  private fun u8(v: Int) = PebbleDictionaryItem.UInt8(v.coerceIn(0, 255).toUByte())

  private fun u16(v: Int) = PebbleDictionaryItem.UInt16(v.coerceIn(0, 0xFFFF).toUShort())

  private fun u32(v: Long) = PebbleDictionaryItem.UInt32(v.coerceIn(0, U32_MAX).toUInt())

  fun flags(np: NowPlaying): Int {
    val repeat = np.repeatMode.coerceIn(0, 2) shl REPEAT_SHIFT
    return (if (np.loved) FLAG_LOVED else 0) or (if (np.shuffle) FLAG_SHUFFLE else 0) or repeat
  }

  fun nowPlaying(np: NowPlaying): PebbleDictionary = mapOf(
    Keys.NP_TITLE.toUInt() to text(np.title, Limits.NP_TITLE),
    Keys.NP_ARTIST.toUInt() to text(np.artist, Limits.NP_LINE),
    Keys.NP_ALBUM.toUInt() to text(np.album, Limits.NP_LINE),
    Keys.NP_STATE.toUInt() to u8(np.state),
    Keys.NP_POS.toUInt() to u32(np.positionSec),
    Keys.NP_DUR.toUInt() to u32(np.durationSec),
    Keys.NP_FLAGS.toUInt() to u8(flags(np)),
    Keys.STATUS.toUInt() to text(np.status, Limits.STATUS),
  )

  fun status(message: String): PebbleDictionary =
    mapOf(Keys.STATUS.toUInt() to text(message, Limits.STATUS))

  fun listHeader(listId: Int, req: Int, title: String, count: Int): PebbleDictionary = mapOf(
    Keys.LIST_ID.toUInt() to u16(listId),
    Keys.REQ.toUInt() to u8(req),
    Keys.LIST_TITLE.toUInt() to text(title, Limits.LIST_TITLE),
    Keys.LIST_COUNT.toUInt() to u16(count.coerceAtMost(Limits.MAX_ITEMS)),
  )

  fun listItem(listId: Int, index: Int, item: BrowseItem): PebbleDictionary {
    val itemFlags = (if (item.browsable) ITEM_BROWSABLE else 0) or
      (if (item.playable) ITEM_PLAYABLE else 0)
    return mapOf(
      Keys.LIST_ID.toUInt() to u16(listId),
      Keys.ITEM_INDEX.toUInt() to u16(index),
      Keys.ITEM_LABEL.toUInt() to text(item.label, Limits.ITEM_LABEL),
      Keys.ITEM_SUB.toUInt() to text(item.sub, Limits.ITEM_SUB),
      Keys.ITEM_FLAGS.toUInt() to u8(itemFlags),
    )
  }

  /** Integer value of [key] in a received dictionary, or null. Watch ints arrive as (U)Int32. */
  fun readInt(data: PebbleDictionary, key: Int): Long? = when (val v = data[key.toUInt()]) {
    is PebbleDictionaryItem.UInt32 -> v.value.toLong()
    is PebbleDictionaryItem.Int32 -> v.value.toLong()
    is PebbleDictionaryItem.UInt16 -> v.value.toLong()
    is PebbleDictionaryItem.UInt8 -> v.value.toLong()
    else -> null
  }
}
