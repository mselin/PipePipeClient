package org.schabi.newpipe.enhancements.backupsafety

/** The rule for a plugin repository address that comes from the database, and how it is shown. */
object RepositoryAddress {
    data class Offer(val pluginName: String, val repositoryUrl: String)
    data class Split(val allowed: List<Offer>, val refused: List<Offer>)

    /**
     * The rule of the plugin settings (https; plain http only when [allowPlainHttp]), and no
     * whitespace or control character anywhere: the address is shown to the user, and such a
     * character could push a part of it out of sight.
     */
    fun isAllowed(url: String, allowPlainHttp: Boolean): Boolean {
        if (url.any { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            return false
        }
        return url.startsWith("https://") || (allowPlainHttp && url.startsWith("http://"))
    }

    fun split(offers: List<Offer>, allowPlainHttp: Boolean): Split {
        val (allowed, refused) = offers.partition { isAllowed(it.repositoryUrl, allowPlainHttp) }
        return Split(allowed, refused)
    }

    /** one "name\nfull address" block per offer, in order */
    fun lines(offers: List<Offer>): List<String> = offers.map { it.pluginName + "\n" + it.repositoryUrl }
}
