package org.schabi.newpipe.enhancements.subscriptionscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * AC-2 (finding J1, plan section 4): the small memory of watched states that lets a row drawn
 * before show its bar at once. `BoundedCache(maxEntries)`: least recently used entry out first,
 * so the memory it holds does not grow with the length of the lists scrolled through.
 */
class BoundedCacheTest {

    @Test
    fun ac2_miss_isNull() {
        val cache = BoundedCache<String, String>(3)

        assertNull("AC-2: an unknown key has no value", cache.get("0|https://example.invalid/a"))
        assertEquals(0, cache.size)
    }

    @Test
    fun ac2_hit_returnsWhatWasPut() {
        val cache = BoundedCache<String, String>(3)
        cache.put("a", "state of a")
        cache.put("b", "state of b")

        assertEquals("state of a", cache.get("a"))
        assertEquals("state of b", cache.get("b"))
        assertEquals(2, cache.size)
    }

    @Test
    fun ac2_overwrite_replacesTheValueAndDoesNotGrow() {
        val cache = BoundedCache<String, String>(3)
        cache.put("a", "half watched")
        cache.put("a", "fully watched")

        assertEquals("AC-2: the newer value is kept", "fully watched", cache.get("a"))
        assertEquals("AC-2: one key, one entry", 1, cache.size)
    }

    @Test
    fun ac2_atMaxEntries_theOldestEntryIsEvicted() {
        val cache = BoundedCache<String, Int>(3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        assertEquals("AC-2: maxEntries entries fit", 3, cache.size)

        cache.put("d", 4)

        assertEquals("AC-2: never more than maxEntries", 3, cache.size)
        assertNull("AC-2: the entry not used for longest is gone", cache.get("a"))
        assertEquals(2, cache.get("b"))
        assertEquals(3, cache.get("c"))
        assertEquals(4, cache.get("d"))
    }

    /** "Least recently used", not "oldest put": a row drawn again keeps its state. */
    @Test
    fun ac2_aReadEntry_countsAsUsed() {
        val cache = BoundedCache<String, Int>(3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)

        cache.get("a")
        cache.put("d", 4)

        assertEquals("AC-2: the entry just read stays", 1, cache.get("a"))
        assertNull("AC-2: the least recently used entry is evicted", cache.get("b"))
        assertEquals(3, cache.size)
    }

    @Test
    fun ac2_anOverwrittenEntry_countsAsUsed() {
        val cache = BoundedCache<String, Int>(3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)

        cache.put("a", 10)
        cache.put("d", 4)

        assertEquals("AC-2: the entry just written stays", 10, cache.get("a"))
        assertNull("AC-2: the least recently used entry is evicted", cache.get("b"))
    }

    @Test
    fun ac2_overwritingAtMaxEntries_evictsNothing() {
        val cache = BoundedCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)

        cache.put("b", 20)

        assertEquals(1, cache.get("a"))
        assertEquals(20, cache.get("b"))
        assertEquals(2, cache.size)
    }

    @Test
    fun ac2_aCacheOfOne_keepsTheLastEntry() {
        val cache = BoundedCache<String, Int>(1)
        cache.put("a", 1)
        cache.put("b", 2)

        assertNull(cache.get("a"))
        assertEquals(2, cache.get("b"))
        assertEquals(1, cache.size)
    }

    // ---- at the owner's scale ----

    /** The plan's cache has 500 entries. Scrolling past 1600 videos leaves the last 500. */
    @Test
    fun ac2_scale_1600VideosThroughACacheOf500_holdsTheLast500() {
        val cache = BoundedCache<String, Int>(500)
        var largest = 0

        for (i in 1..SUBSCRIPTIONS) {
            cache.put("0|https://example.invalid/watch/$i", i)
            largest = maxOf(largest, cache.size)
        }

        assertEquals("AC-2: the cache never held more than maxEntries", 500, largest)
        assertEquals(500, cache.size)
        assertEquals(
            "AC-2: the 1100 least recently used entries are gone",
            0,
            (1..1100).count { cache.get("0|https://example.invalid/watch/$it") != null }
        )
        assertEquals(
            "AC-2: the 500 most recent entries are there",
            500,
            (1101..SUBSCRIPTIONS).count { cache.get("0|https://example.invalid/watch/$it") == it }
        )
    }

    /** Scrolling back and forth over the same 300 videos never evicts one of them. */
    @Test
    fun ac2_scale_rereadingFewerVideosThanMaxEntries_evictsNone() {
        val cache = BoundedCache<Int, Int>(500)
        repeat(10) {
            for (i in 1..300) {
                if (cache.get(i) == null) cache.put(i, i)
            }
        }

        assertEquals(300, cache.size)
        assertEquals(300, (1..300).count { cache.get(it) == it })
    }

    /**
     * Plan section 2: "least recently used, synchronized". The cache is process-wide: results
     * are put from row callbacks while other rows read. Eight threads at once must neither throw
     * nor leave it larger than maxEntries. (A smoke test: it cannot prove the absence of a race.)
     */
    @Test
    fun ac2_usedFromSeveralThreads_staysBoundedAndDoesNotThrow() {
        val cache = BoundedCache<Int, Int>(50)
        val failures = CopyOnWriteArrayList<Throwable>()
        val go = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            thread {
                try {
                    go.await()
                    for (i in 0 until 20_000) {
                        val key = (i * 7 + t * 13) % 400
                        cache.put(key, key)
                        val read = cache.get((key + t) % 400)
                        if (read != null && read != (key + t) % 400) throw AssertionError("wrong value $read")
                        if (cache.size > 50) throw AssertionError("size ${cache.size} > 50")
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }
        }
        go.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(60)) }

        assertTrue("every thread finished", threads.none { it.isAlive })
        assertEquals("AC-2: no thread failed", emptyList<Throwable>(), failures)
        assertEquals(50, cache.size)
    }
}
