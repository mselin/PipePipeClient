package org.schabi.newpipe.enhancements.groupcreateinline

/** What the name prompt says about a name. */
enum class NameCheck { OK, EMPTY, DUPLICATE }

/** Which names the name prompt of the "Select groups" dialog accepts. Plain Kotlin, no Android. */
object GroupNameRules {

    /** The name as stored and shown: Kotlin `raw.trim()`. */
    fun clean(raw: String): String = raw.trim()

    /** Spec "same name": `clean(a).equals(clean(b), ignoreCase = true)`. */
    fun sameName(a: String, b: String): Boolean = clean(a).equals(clean(b), ignoreCase = true)

    /**
     * EMPTY if `clean(raw)` is empty; else DUPLICATE if `sameName(raw, t)` for a t in
     * [takenNames]; else OK.
     */
    fun check(raw: String, takenNames: Collection<String>): NameCheck = when {
        clean(raw).isEmpty() -> NameCheck.EMPTY
        takenNames.any { sameName(raw, it) } -> NameCheck.DUPLICATE
        else -> NameCheck.OK
    }
}
