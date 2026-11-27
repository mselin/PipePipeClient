package org.schabi.newpipe.enhancements.playermemory

/**
 * A cache whose content never exceeds [maxBytes], as measured by [sizeOf] when a value is put.
 *
 * The least recently used entry goes first. The entry just put is never evicted, so one value
 * larger than the limit is kept alone until the next put. Every value that leaves the cache
 * (evicted, replaced or cleared) is handed to [onEvicted] exactly once.
 *
 * Not thread safe: use it from one thread.
 */
class ByteBoundedCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
    private val onEvicted: (V) -> Unit
) {
    private class Entry<V>(val value: V, val bytes: Long)

    // Access order: iteration starts at the least recently used entry.
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    /** The sum of the sizes of the values held. */
    var bytes: Long = 0
        private set

    /** A read-only view of the keys held. Looking at it does not count as a use of an entry. */
    val keys: Set<K> = java.util.Collections.unmodifiableSet(entries.keys)

    /** The value of [key], which becomes the most recently used entry, or null. */
    operator fun get(key: K): V? = entries[key]?.value

    /** Holds [value] under [key], then evicts least recently used entries down to the limit. */
    fun put(key: K, value: V) {
        val replaced = entries.remove(key)
        if (replaced != null) {
            bytes -= replaced.bytes
            if (replaced.value !== value) {
                onEvicted(replaced.value)
            }
        }
        val entry = Entry(value, sizeOf(value))
        entries[key] = entry
        bytes += entry.bytes

        val eldestFirst = entries.entries.iterator()
        while (bytes > maxBytes && entries.size > 1) {
            val eldest = eldestFirst.next().value
            eldestFirst.remove()
            bytes -= eldest.bytes
            onEvicted(eldest.value)
        }
    }

    /** Empties the cache; every value goes to the eviction callback. */
    fun clear() {
        val values = entries.values.map { it.value }
        entries.clear()
        bytes = 0
        values.forEach(onEvicted)
    }
}
