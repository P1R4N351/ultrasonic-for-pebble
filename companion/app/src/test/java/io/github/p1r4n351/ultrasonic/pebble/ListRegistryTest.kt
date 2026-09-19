package io.github.p1r4n351.ultrasonic.pebble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListRegistryTest {
  private fun items(n: Int) = (0 until n).map { BrowseItem("id$it", "L$it", "", false, true) }

  @Test
  fun resolvesByListAndIndex() {
    val r = ListRegistry()
    val a = r.register(items(3))
    val b = r.register(items(2))
    assertNotEquals(a, b)
    assertEquals("id2", r.item(a, 2)?.mediaId)
    assertEquals("id1", r.item(b, 1)?.mediaId)
    assertNull(r.item(b, 2))
    assertNull(r.item(999, 0))
  }

  @Test
  fun evictsOldestPastCapacity() {
    val r = ListRegistry(capacity = 2)
    val first = r.register(items(1))
    val second = r.register(items(1))
    val third = r.register(items(1))
    assertEquals(2, r.size())
    assertNull(r.item(first, 0))
    assertEquals("id0", r.item(second, 0)?.mediaId)
    assertEquals("id0", r.item(third, 0)?.mediaId)
  }

  @Test
  fun capsItemsAtWatchCapacity() {
    val r = ListRegistry()
    val id = r.register(items(100))
    assertEquals("id39", r.item(id, Limits.MAX_ITEMS - 1)?.mediaId)
    assertNull(r.item(id, Limits.MAX_ITEMS))
  }

  @Test
  fun idsWrapWithoutEverIssuingZero() {
    val r = ListRegistry(capacity = 1)
    var last = 0
    for (i in 0 until ListRegistry.MAX_ID + 2) {
      last = r.register(items(1))
      assertNotEquals("id 0 is reserved as 'no list' by the watch", 0, last)
    }
    assertEquals(2, last)
  }
}
