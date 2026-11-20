package org.schabi.newpipe.enhancements.refreshcooldown

/** The global setting's choices, in its order. */
class CooldownChoices(val seconds: List<Int>, val labels: List<String>) {
    val allowed: Set<Int> = seconds.toSet()

    /** `null` or not a choice: [useDefault]. */
    fun label(value: Int?, useDefault: String): String {
        val at = if (value == null) -1 else seconds.indexOf(value)
        return if (at < 0) useDefault else labels[at]
    }

    fun items(useDefault: String): List<String> = listOf(useDefault) + labels

    fun indexOf(value: Int?): Int = if (value == null) 0 else seconds.indexOf(value) + 1

    fun valueAt(index: Int): Int? = if (index <= 0) null else seconds.getOrNull(index - 1)

    companion object {
        fun of(values: Array<String>, options: Array<String>, localize: (Int) -> String): CooldownChoices {
            val seconds = ArrayList<Int>()
            val labels = ArrayList<String>()
            for (i in values.indices) {
                val s = values[i].toIntOrNull() ?: continue
                seconds.add(s)
                labels.add(if (s <= 0) options.getOrNull(i) ?: s.toString() else localize(s))
            }
            return CooldownChoices(seconds, labels)
        }
    }
}
