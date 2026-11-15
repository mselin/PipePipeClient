package org.schabi.newpipe.enhancements.subscriptionscale

/**
 * A map that holds at most [maxEntries] entries and drops the least recently used one (read or
 * written) when a new key would exceed that. Every method is synchronized, so it may be shared
 * between threads.
 */
class BoundedCache<K : Any, V : Any>(private val maxEntries: Int) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    // accessOrder = true: iteration order is least recently used first, and get() counts as use.
    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
    }

    val size: Int
        @Synchronized get() = entries.size
}
