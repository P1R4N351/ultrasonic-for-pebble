// Copyright (c) 2026 PiSCES.
// SPDX-License-Identifier: MPL-2.0
package io.github.p1r4n351.ultrasonic.pebble

/**
 * Lists the watch is currently showing, keyed by a 16-bit id the watch echoes back.
 * The watch never holds media ids; it names an item by (list id, index). Bounded: the
 * oldest list is evicted past [capacity], and an evicted id simply resolves to null.
 */
class ListRegistry(private val capacity: Int = DEFAULT_CAPACITY) {
  private val lists = LinkedHashMap<Int, List<BrowseItem>>()
  private var nextId = 1

  init {
    require(capacity in 1..MAX_CAPACITY) { "capacity must be 1..$MAX_CAPACITY" }
  }

  fun register(items: List<BrowseItem>): Int {
    val id = nextId
    nextId = if (nextId >= MAX_ID) 1 else nextId + 1
    lists.remove(id)
    lists[id] = items.take(Limits.MAX_ITEMS)
    while (lists.size > capacity) {
      lists.remove(lists.keys.first())
    }
    return id
  }

  fun item(listId: Int, index: Int): BrowseItem? = lists[listId]?.getOrNull(index)

  fun size(): Int = lists.size

  companion object {
    const val DEFAULT_CAPACITY = 8
    const val MAX_CAPACITY = 64
    const val MAX_ID = 0xFFFF
  }
}
