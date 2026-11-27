package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.fail
import java.io.File

/** The choices of the global setting, in seconds (`res/values/settings_keys.xml`, `feed_update_threshold_values`). */
internal val CHOICES = listOf(0, 300, 900, 3600, 21600, 43200, 86400)
internal val ALLOWED: Set<Int> = CHOICES.toSet()

/** Plan 4.3: "`now` = 1 000 000 000", in milliseconds like every stored time. */
internal const val NOW = 1_000_000_000L

/** The id of the "All" feed (`FeedGroupEntity.GROUP_ALL_ID`). */
internal const val ALL = -1L

/** The owner's data: about 1600 subscriptions in 40 groups. */
internal const val SUBSCRIPTIONS = 1600
internal const val GROUPS = 40

/** Stands for `SubscriptionEntity`: what the loader knows of a channel (plan section 13: "test data classes `(id, serviceId, url)`"). */
internal data class Channel(val id: Long, val serviceId: Int = 0, val url: String? = "https://example.invalid/channel/$id")

internal fun keyOf(channel: Channel): String = CooldownValues.subscriptionKey(channel.serviceId, channel.url)

/** Values as the two UI places build them: one `with` per entry. */
internal fun values(vararg entries: Pair<String, Int>): CooldownValues {
    var result = CooldownValues.EMPTY
    for ((key, seconds) in entries) {
        val next = result.with(key, seconds)
        if (next[key] != seconds) fail("the test's own key `$key` was not accepted by CooldownValues.with")
        result = next
    }
    return result
}

/**
 * The hand-written stand-in for the database and the two preferences (plan section 4:
 * "Each function is one query or one preference read"). It counts the calls of each function;
 * [queries] is the number of calls that are database queries in `RoomCooldownData`.
 */
internal class FakeData(
    var last: Map<Long, Long> = emptyMap(),
    var memberships: List<Membership> = emptyList(),
    var groups: List<GroupName> = emptyList(),
    var own: CooldownValues = CooldownValues.EMPTY,
    var groupValues: CooldownValues = CooldownValues.EMPTY
) : CooldownData {
    var lastUpdatedCalls = 0
    var membershipCalls = 0
    var groupCalls = 0
    var ownValueCalls = 0
    var groupValueCalls = 0

    val queries: Int get() = lastUpdatedCalls + membershipCalls + groupCalls
    val calls: List<Int> get() = listOf(lastUpdatedCalls, membershipCalls, groupCalls, ownValueCalls, groupValueCalls)

    override fun lastUpdatedMillis(): Map<Long, Long> { lastUpdatedCalls++; return last }
    override fun memberships(): List<Membership> { membershipCalls++; return memberships }
    override fun groups(): List<GroupName> { groupCalls++; return groups }
    override fun ownValues(): CooldownValues { ownValueCalls++; return own }
    override fun groupValues(): CooldownValues { groupValueCalls++; return groupValues }
}

/** `CooldownRules.select` as `CooldownRefresh` calls it (plan 5.1), for [Channel]s. */
internal fun select(scope: List<Channel>, data: CooldownData, globalSeconds: Int, nowMillis: Long = NOW): Selection<Channel> =
    CooldownRules.select(scope, { it.id }, { keyOf(it) }, data, globalSeconds, nowMillis)

/** A list that counts how often an element is read. */
internal class CountingList<T>(private val backing: List<T>) : AbstractList<T>() {
    var reads = 0L
    override val size: Int get() = backing.size
    override fun get(index: Int): T { reads++; return backing[index] }
}

/** A map that counts its lookups by key and how often it is walked through as a whole. */
internal class CountingMap<K, V>(private val backing: Map<K, V>) : AbstractMap<K, V>() {
    var lookups = 0L
    var walks = 0L
    override val entries: Set<Map.Entry<K, V>> get() { walks++; return backing.entries }
    override val size: Int get() = backing.size
    override fun get(key: K): V? { lookups++; return backing[key] }
    override fun containsKey(key: K): Boolean { lookups++; return backing.containsKey(key) }
}

/** Files of the module, read as text by the wiring, source and resource tests. */
internal object Sources {
    const val MARKER = "ENH:refresh-cooldown"
    const val SCALE_MARKER = "ENH:subscription-scale"
    const val LIVE_MARKER = "ENH:feed-live-update"

    /** `<path>` when run by Gradle (working directory `app/`), else `app/<path>`. */
    fun moduleFile(path: String): File {
        val inModule = File(path)
        return if (inModule.exists() || File("src/main").isDirectory) inModule else File("app/$path")
    }

    fun main(path: String): File = moduleFile("src/main/$path")

    fun lines(ac: String, path: String): List<String> {
        val file = main(path)
        if (!file.isFile) fail("$ac: app/src/main/$path does not exist")
        return file.readLines()
    }

    fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    /** How often [snippet] occurs in [text], whitespace ignored in both. */
    fun count(text: String, snippet: String): Int {
        val wanted = squeeze(snippet)
        return squeeze(text).windowed(wanted.length).count { it == wanted }
    }

    /**
     * Whether [line] carries exactly the marker [marker]: `ENH:refresh-cooldown-2` or
     * `ENH:refresh-cooldowns` is another feature's line, not this one's.
     */
    fun carries(line: String, marker: String): Boolean = Regex(Regex.escape(marker) + "(?![\\w-])").containsMatchIn(line)

    /** Every `ENH:<name>` on [line]. */
    fun markers(line: String): List<String> = Regex("ENH:[\\w-]+").findAll(line).map { it.value }.toList()
}

/** Resource files of `app/src/main/res`, read as XML. */
internal object Res {
    fun document(ac: String, path: String): org.w3c.dom.Document {
        val file = Sources.main("res/$path")
        if (!file.isFile) fail("$ac: app/src/main/res/$path does not exist")
        return javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    fun elements(parent: org.w3c.dom.Node, tag: String): List<org.w3c.dom.Element> {
        val result = ArrayList<org.w3c.dom.Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is org.w3c.dom.Element && child.tagName == tag) result.add(child)
        }
        return result
    }

    /** The items of `<string-array name="[name]">`, as written in the file. */
    fun stringArray(ac: String, path: String, name: String): List<String> {
        val arrays = elements(document(ac, path).documentElement, "string-array").filter { it.getAttribute("name") == name }
        if (arrays.size != 1) fail("$ac: app/src/main/res/$path must have one <string-array name=\"$name\">")
        return elements(arrays[0], "item").map { it.textContent }
    }

    /** The text of `<string name="[name]">`, or `null`. */
    fun string(ac: String, path: String, name: String): String? =
        elements(document(ac, path).documentElement, "string").singleOrNull { it.getAttribute("name") == name }?.textContent
}
