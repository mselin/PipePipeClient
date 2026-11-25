package org.schabi.newpipe.enhancements.groupsort

/**
 * The values of the "Channel group order" setting, each with the string it is stored as.
 * Pure Kotlin, no Android.
 */
enum class GroupSortMode(val preferenceValue: String) {
    MANUAL("manual"),
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc");

    /** Whether group lists are sorted by name, so the manual order is not in effect. */
    val sortsByName: Boolean
        get() = this != MANUAL

    companion object {
        val DEFAULT: GroupSortMode = MANUAL

        /** The mode stored as exactly [value]; `null` and any other string give [MANUAL]. */
        fun fromPreferenceValue(value: String?): GroupSortMode =
            entries.firstOrNull { it.preferenceValue == value } ?: MANUAL
    }
}
