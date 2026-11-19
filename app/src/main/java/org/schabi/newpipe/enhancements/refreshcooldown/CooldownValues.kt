package org.schabi.newpipe.enhancements.refreshcooldown

import java.net.URLEncoder

/** The stored values of one preference. Immutable. */
class CooldownValues private constructor(private val entries: Map<String, Int>) {
    val size: Int get() = entries.size

    operator fun get(key: String): Int? = entries[key]

    /** `null` removes; an invalid key: returns this. */
    fun with(key: String, seconds: Int?): CooldownValues {
        if (!isValidKey(key)) return this
        val next = HashMap(entries)
        if (seconds == null) next.remove(key) else next[key] = seconds
        return CooldownValues(next)
    }

    /** `""` when empty. */
    fun format(): String = entries.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }

    override fun equals(other: Any?): Boolean = other is CooldownValues && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "CooldownValues(${format()})"

    companion object {
        private val KEY = Regex("-?[0-9]+:[A-Za-z0-9.*_+%-]*")
        private val SECONDS = Regex("[0-9]{1,9}")

        val EMPTY: CooldownValues = CooldownValues(emptyMap())

        fun parse(text: String?, allowedSeconds: Set<Int>): CooldownValues {
            if (text.isNullOrEmpty()) return EMPTY
            val entries = HashMap<String, Int>()
            for (raw in text.split(';')) {
                val entry = raw.trim()
                val at = entry.lastIndexOf('=')
                if (at <= 0) continue
                val key = entry.substring(0, at)
                val seconds = entry.substring(at + 1)
                if (!isValidKey(key) || !SECONDS.matches(seconds)) continue
                val value = seconds.toInt()
                if (value !in allowedSeconds) continue
                entries[key] = value
            }
            return if (entries.isEmpty()) EMPTY else CooldownValues(entries)
        }

        fun groupKey(groupId: Long, name: String): String =
            "$groupId:" + Integer.toHexString(name.hashCode()).padStart(8, '0')

        fun subscriptionKey(serviceId: Int, url: String?): String =
            "$serviceId:" + URLEncoder.encode(url ?: "", "UTF-8")

        fun isValidKey(key: String): Boolean = KEY.matches(key)

        /** What the group dialog's save stores. */
        fun groupSaved(values: CooldownValues, groupId: Long, oldName: String, newName: String, seconds: Int?): CooldownValues =
            values.with(groupKey(groupId, oldName), null).with(groupKey(groupId, newName), seconds)
    }
}
