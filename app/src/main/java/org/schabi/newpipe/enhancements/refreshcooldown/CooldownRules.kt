package org.schabi.newpipe.enhancements.refreshcooldown

data class GroupName(val id: Long, val name: String)

data class Membership(val groupId: Long, val subscriptionId: Long)

/** What a refresh reads. Each function is one query or one preference read. */
interface CooldownData {
    /** Subscription id to time; no entry: never or failed. */
    fun lastUpdatedMillis(): Map<Long, Long>
    fun memberships(): List<Membership>
    fun groups(): List<GroupName>
    fun ownValues(): CooldownValues
    fun groupValues(): CooldownValues
}

class Selection<T>(val load: List<T>, val skipped: List<Long>)

object CooldownRules {
    fun effective(own: Int?, groupValues: Collection<Int>, global: Int): Int = own ?: groupValues.minOrNull() ?: global

    fun inCooldown(lastUpdatedMillis: Long?, cooldownSeconds: Int, nowMillis: Long): Boolean =
        lastUpdatedMillis != null && lastUpdatedMillis >= nowMillis - cooldownSeconds * 1000L

    fun <T> select(
        scope: List<T>,
        idOf: (T) -> Long,
        keyOf: (T) -> String,
        data: CooldownData,
        globalSeconds: Int,
        nowMillis: Long
    ): Selection<T> {
        val last = data.lastUpdatedMillis()
        val own = data.ownValues()
        val groupValues = data.groupValues()
        val bySubscription = HashMap<Long, MutableList<Int>>()
        if (groupValues.size != 0) {
            val secondsOfGroup = HashMap<Long, Int>()
            for (g in data.groups()) groupValues[CooldownValues.groupKey(g.id, g.name)]?.let { secondsOfGroup[g.id] = it }
            for (m in data.memberships()) {
                val seconds = secondsOfGroup[m.groupId] ?: continue
                bySubscription.getOrPut(m.subscriptionId) { ArrayList() }.add(seconds)
            }
        }
        val load = ArrayList<T>()
        val skipped = ArrayList<Long>()
        for (t in scope) {
            val id = idOf(t)
            val e = effective(own[keyOf(t)], bySubscription[id] ?: emptyList(), globalSeconds)
            if (inCooldown(last[id], e, nowMillis)) skipped.add(id) else load.add(t)
        }
        return Selection(load, skipped)
    }

    fun <T> forced(scope: List<T>, idOf: (T) -> Long, forcedIds: LongArray): Selection<T> {
        val ids = forcedIds.toHashSet()
        return Selection(scope.filter { idOf(it) in ids }, emptyList())
    }
}
