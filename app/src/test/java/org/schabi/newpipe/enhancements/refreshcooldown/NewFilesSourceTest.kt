package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new files of plan sections 4 to 7, read as text: four plain Kotlin files (no Android
 * import) and the Android glue around them. The plain files' behaviour is what the other tests
 * of this package run; the glue needs a `Context`, Room, a `Fragment` or a view and cannot run
 * on the JVM, so these tests pin the few things in it that the criteria stand on and that the
 * marked lines of the upstream files call.
 *
 * The plan counts "six Android glue files" and "ten files" but names seven and eleven (Q8 in
 * `questions.md`); the test expects the eleven it names.
 *
 * Until the Code layer writes the files, every test here fails with "does not exist".
 */
class NewFilesSourceTest {

    private val dir = "java/org/schabi/newpipe/enhancements/refreshcooldown"

    private val plain = listOf("CooldownValues.kt", "CooldownChoices.kt", "CooldownRules.kt", "SkipReports.kt")
    private val glue = listOf(
        "CooldownRefresh.kt", "RoomCooldownData.kt", "CooldownStore.kt", "SkippedMessageUi.kt", "CooldownChooser.kt",
        "GroupCooldownUi.kt", "SubscriptionCooldownUi.kt"
    )

    private fun source(ac: String, name: String): String = Sources.lines(ac, "$dir/$name").joinToString("\n")

    /** The file without its comments: KDoc and line comments may name anything. */
    private fun code(ac: String, name: String): String =
        Sources.lines(ac, "$dir/$name").filterNot { it.trim().startsWith("*") || it.trim().startsWith("/") }.joinToString("\n")

    private fun assertMentions(ac: String, name: String, snippet: String, why: String) {
        assertTrue("$ac: app/src/main/$dir/$name must contain `$snippet`: $why", Sources.count(code(ac, name), snippet) > 0)
    }

    private fun assertMentionsOnce(ac: String, name: String, snippet: String, why: String) {
        assertEquals("$ac: app/src/main/$dir/$name must contain `$snippet` exactly once: $why", 1, Sources.count(code(ac, name), snippet))
    }

    /** Plan sections 4 to 7 and 15: the package holds the named files and no other source file. */
    @Test
    fun thePackageHasThePlansFiles() {
        for (name in plain + glue) Sources.lines("plan sections 4-7", "$dir/$name")
        val files = Sources.main(dir).listFiles()!!.filter { it.isFile }.map { it.name }.sorted()

        assertEquals((plain + glue).sorted(), files)
        assertEquals("no sub-package", emptyList<String>(), Sources.main(dir).listFiles()!!.filter { it.isDirectory }.map { it.name })
    }

    /**
     * PROCESS.md and plan section 4: decision logic is plain Kotlin: "No `android.`,
     * `androidx.` or `.rxjava3.android.` import; no Room entity." The four logic files import
     * none, name no Android class in full, and do not know the database's classes.
     */
    @Test
    fun theFourPlainFilesHaveNoAndroidImports() {
        for (name in plain) {
            val text = source("plan section 4", name)
            val imports = text.lines().filter { it.trim().startsWith("import ") }
            assertEquals(
                "app/src/main/$dir/$name must not import Android classes",
                emptyList<String>(),
                imports.filter { it.contains("import android.") || it.contains("import androidx.") || it.contains(".rxjava3.android.") }
            )
            assertEquals(
                "app/src/main/$dir/$name must not import the app's database, its resources or its Android classes",
                emptyList<String>(),
                imports.filter { it.contains("org.schabi.newpipe.") && !it.contains("org.schabi.newpipe.enhancements.refreshcooldown.") }
            )
            assertFalse("app/src/main/$dir/$name must not use android.util.Log", text.contains("android.util.Log"))
            assertFalse("app/src/main/$dir/$name must not name Android classes in full either", Regex("""\bandroidx?\.\w+\.\w""").containsMatchIn(code("plan section 4", name)))
            assertFalse("app/src/main/$dir/$name must not name the app's database classes in full", code("plan section 4", name).contains("org.schabi.newpipe.database"))
        }
    }

    /** Each file is in the package the marked lines write out. */
    @Test
    fun theFilesAreInThePlansPackage() {
        for (name in plain + glue) {
            assertTrue(
                "app/src/main/$dir/$name: package org.schabi.newpipe.enhancements.refreshcooldown",
                source("plan sections 4-7", name).lines().any { it.trim() == "package org.schabi.newpipe.enhancements.refreshcooldown" }
            )
        }
    }

    /** AC-11, plan 4.2: the decision is the negation of upstream's SQL on the same integers: one comparison, in `Long`. */
    @Test
    fun ac11_cooldownRules_noClockAndNoTimeZoneInTheDecision() {
        val text = code("AC-11", "CooldownRules.kt")
        for (clock in listOf("System.currentTimeMillis", "OffsetDateTime", "Instant.now", "Date(", "Calendar", "nanoTime")) {
            assertEquals("AC-11: CooldownRules.kt takes `now` from its caller and must not use `$clock`", 0, Sources.count(text, clock))
        }
    }

    /**
     * AC-5, AC-7, AC-9, AC-11, plan 5.1: `CooldownRefresh`, as the loader's marked lines call
     * it: the three functions with the plan's signatures; a whole-scope threshold; the three
     * ways of `select` (notification check, forced, cooldown); the report cleared at the start
     * and published at the end, neither of them for the notification check.
     *
     * A text test: it shows that the pieces are there, not that the branches are right. That
     * the notification check loads everything and reports nothing is device step 7.
     */
    @Test
    fun ac7_cooldownRefresh_hasThePlansSignaturesAndPieces() {
        val name = "CooldownRefresh.kt"
        assertMentions("AC-5", name, "class CooldownRefresh(", "plan 5.1")
        for (parameter in listOf("context: Context", "groupId: Long", "ignore: Boolean", "forced: LongArray?")) {
            assertMentions("AC-5", name, parameter, "plan 5.1: a constructor parameter, in the order the marked line passes them")
        }
        assertMentions("AC-5", name, "fun queryThreshold(now: OffsetDateTime, globalSeconds: Int): OffsetDateTime", "plan 5.1")
        assertMentions("AC-5", name, "fun select(scope: List<SubscriptionEntity>): List<SubscriptionEntity>", "plan 5.1")
        assertMentions("AC-8", name, "fun done()", "plan 5.1")
        assertMentionsOnce("AC-5", name, "plusYears(100)", "plan 5.1: with that threshold upstream's query returns the whole scope")
        assertMentions("AC-11", name, "toEpochMilli()", "plan 5.1: `now` in milliseconds, as the stored times")
        assertMentionsOnce("AC-8", name, "SkipReports.app.clear()", "plan 6.1: the start of a manual refresh clears the earlier report")
        assertMentionsOnce("AC-8", name, "SkipReports.app.publish(", "plan 6.1: `done()` publishes the skipped ids")
        assertMentions("AC-8", name, "SystemClock.elapsedRealtime()", "plan 5.1: the report's time")
        assertMentionsOnce("AC-9", name, "CooldownRules.forced(", "plan 5.1: \"Refresh anyway\"")
        assertMentionsOnce("AC-5", name, "CooldownRules.select(", "plan 5.1: the cooldown decision")
        assertMentionsOnce("AC-5", name, "RoomCooldownData(", "plan 5.1: the data of the decision")
        assertMentions("AC-3", name, "CooldownValues.subscriptionKey(", "plan 5.1: a subscription's value is found by service and address")
        assertTrue(
            "AC-7: `ignore` is asked in the constructor, in select and in done (plan 5.3)",
            Regex("""\bignore\b""").findAll(code("AC-7", name)).count() >= 4
        )
        assertEquals("AC-7: the refresh does not read the clock itself: `now` is upstream's", 0, Sources.count(code("AC-7", name), "OffsetDateTime.now("))
    }

    /** AC-5, plan 5.1 and section 10: `RoomCooldownData` implements the data interface, "each function with exactly one call". */
    @Test
    fun ac5_roomCooldownData_callsEachQueryOnce() {
        val name = "RoomCooldownData.kt"
        assertMentions("AC-5", name, "CooldownData", "plan 5.1: it implements the plain interface")
        for (query in listOf("cooldownLastUpdated()", "cooldownMemberships()", "cooldownGroups()")) {
            assertMentionsOnce("AC-5", name, query, "plan 5.1: one query per function")
        }
        assertMentionsOnce("AC-3", name, "CooldownStore.subscriptions(", "plan 5.1: one preference read")
        assertMentionsOnce("AC-2", name, "CooldownStore.groups(", "plan 5.1: one preference read")
        for (loop in listOf("getSubscription(", "subscriptionDAO()", "feedGroupDAO()", "blockingGet", "blockingFirst")) {
            assertEquals("plan section 10: \"Nothing per channel\": RoomCooldownData.kt must not use `$loop`", 0, Sources.count(code("AC-5", name), loop))
        }
    }

    /** AC-2, AC-3, AC-4, plan 5.1: `CooldownStore` reads and writes the two preferences by their resource keys, removes an empty one, and never throws on a foreign value. */
    @Test
    fun ac4_cooldownStore_readsAndWritesTheTwoPreferences() {
        val name = "CooldownStore.kt"
        assertMentions("AC-4", name, "R.string.enh_refresh_cooldown_groups_key", "plan 3.1")
        assertMentions("AC-4", name, "R.string.enh_refresh_cooldown_subscriptions_key", "plan 3.1")
        assertMentions("AC-1", name, "R.array.feed_update_threshold_values", "plan 5.1: the choices are the global setting's")
        assertMentions("AC-1", name, "R.array.feed_update_threshold_options", "plan 5.1")
        assertMentions("AC-1", name, "Localization.localizeDuration", "plan 5.1: the labels as the setting shows them")
        assertMentions("AC-4", name, "CooldownValues.parse(", "plan 5.1")
        assertMentions("AC-4", name, "runCatching", "plan 5.1: \"a value of another type gives empty\"")
        assertMentions("AC-4", name, "putString(", "plan 5.1")
        assertMentions("AC-4", name, "remove(", "plan 3.1: \"With no entry at all the preference is removed\"")
        assertMentions("AC-4", name, "apply()", "plan 5.1")
        assertMentions("AC-2", name, "CooldownValues.groupSaved(", "plan 5.1: the group dialog's save")
        for (function in listOf("fun choices(", "fun subscriptions(", "fun groups(", "fun setSubscription(", "fun saveGroup(")) {
            assertMentions("AC-4", name, function, "plan 5.1")
        }
    }

    /**
     * AC-8, AC-9, plan 6.2 and 6.3: `SkippedMessageUi.offer`, as `FeedFragment`'s marked line
     * calls it; the report taken when the message is shown; the wait after an error message;
     * "Refresh anyway" as a start of the feed service with the feed and the report's ids.
     */
    @Test
    fun ac8_skippedMessageUi_hasThePlansSignatureAndPieces() {
        val name = "SkippedMessageUi.kt"
        assertMentions("AC-8", name, "object SkippedMessageUi", "plan 6.2")
        assertMentions("AC-8", name, "fun offer(fragment: Fragment, groupId: Long, afterErrors: Boolean)", "plan 6.2")
        assertMentionsOnce("AC-8", name, "SkipReports.app.take(", "plan 6.2: taken once, when the message is shown")
        assertMentions("AC-8", name, "postDelayed", "plan 6.2: after upstream's error message")
        assertMentions("AC-8", name, "SkipReports.ERROR_MESSAGE_WAIT_MILLIS", "plan 6.2")
        assertMentions("AC-8", name, "SkipReports.MESSAGE_MILLIS", "plan 6.2: the message goes away by itself")
        assertMentions("AC-8", name, "isResumed", "plan 6.2: only the page on screen takes the report")
        assertMentions("AC-8", name, "Snackbar.make(", "plan 6.2: a message that does not block the feed")
        assertMentions("AC-8", name, "R.plurals.enh_refresh_cooldown_skipped", "plan 6.2")
        assertMentions("AC-8", name, "getQuantityString(", "plan 6.2")
        assertMentions("AC-8", name, "R.string.enh_refresh_cooldown_refresh_anyway", "plan 6.2")
        assertMentions("AC-9", name, "FeedLoadService::class.java", "plan 6.3")
        assertMentions("AC-9", name, "FeedLoadService.EXTRA_GROUP_ID", "plan 6.3: the same feed")
        assertMentions("AC-9", name, "SkipReports.EXTRA_FORCED", "plan 6.3: exactly the skipped ids")
        assertMentions("AC-9", name, "toLongArray()", "plan 6.3")
        assertMentions("AC-9", name, "startService(", "plan 6.3: what reloadContent() does")
        val squeezed = Sources.squeeze(code("AC-8", name))
        assertTrue(
            "AC-8: the report is taken only after the page was found on screen (`isResumed` before `take`)",
            squeezed.indexOf("isResumed") in 0 until squeezed.indexOf("SkipReports.app.take(")
        )
    }

    /** AC-2, AC-3, plan section 7: the chooser both places use, with the plan's signature and the choices' own items. */
    @Test
    fun ac2_cooldownChooser_hasThePlansSignatureAndPieces() {
        val name = "CooldownChooser.kt"
        assertMentions("AC-2", name, "object CooldownChooser", "plan section 7")
        assertMentions("AC-2", name, "fun show(context: Context, current: Int?, onChosen: (Int?) -> Unit): AlertDialog", "plan section 7")
        assertMentions("AC-2", name, "R.string.enh_refresh_cooldown_title", "plan section 7")
        assertMentions("AC-2", name, "R.string.enh_refresh_cooldown_use_default", "plan section 7")
        assertMentions("AC-2", name, "R.string.cancel", "plan section 7")
        assertMentions("AC-2", name, "setSingleChoiceItems(", "plan section 7: single choice, the current value checked")
        assertMentions("AC-2", name, "indexOf(current)", "plan section 7: \"shows the current value\"")
        assertMentions("AC-2", name, "valueAt(", "plan section 7")
        assertMentions("AC-2", name, "CooldownStore.choices(", "plan section 7: the global setting's choices")
    }

    /** AC-2, plan 7.1: the group dialog's row, as `FeedGroupDialog`'s two marked lines call it. */
    @Test
    fun ac2_groupCooldownUi_hasThePlansSignaturesAndPieces() {
        val name = "GroupCooldownUi.kt"
        assertMentions("AC-2", name, "object GroupCooldownUi", "plan 7.1")
        assertMentions("AC-2", name, "fun attach(optionsRoot: ViewGroup, group: FeedGroupEntity?)", "plan 7.1")
        assertMentions("AC-2", name, "fun save(optionsRoot: ViewGroup, groupId: Long, newName: String)", "plan 7.1")
        assertMentions("AC-2", name, "R.layout.enhancements_refresh_cooldown_group_row", "plan 7.1")
        assertMentions("AC-2", name, "R.id.enh_refresh_cooldown_group_button", "plan 7.1")
        assertMentions("AC-2", name, "R.id.select_channel_button", "plan 7.1: \"Select subscriptions\" moves below the row")
        assertMentions("AC-2", name, "topToBottom", "plan 7.1")
        assertMentions("AC-2", name, "R.string.enh_refresh_cooldown_entry", "plan 7.1: \"Refresh cooldown: <value>\"")
        assertMentions("AC-2", name, "CooldownValues.groupKey(", "plan 7.1: the shown value is the one stored for this id and name")
        assertMentions("AC-2", name, "CooldownStore.groups(", "plan 7.1")
        assertMentionsOnce("AC-2", name, "CooldownStore.saveGroup(", "plan 7.1: stored by `save` only: cancel stores nothing")
        assertMentions("AC-2", name, "CooldownChooser.show(", "plan 7.1")
        assertMentions("AC-2", name, "addOnAttachStateChangeListener", "plan 7.1: the chooser goes with the dialog")
        assertEquals("AC-2: a choice is not stored before the dialog is confirmed", 0, Sources.count(code("AC-2", name), "setSubscription("))
    }

    /** AC-3, plan 7.2: the long-press menu's entry and action, as `SubscriptionFragment`'s two marked lines call them. */
    @Test
    fun ac3_subscriptionCooldownUi_hasThePlansSignaturesAndPieces() {
        val name = "SubscriptionCooldownUi.kt"
        assertMentions("AC-3", name, "object SubscriptionCooldownUi", "plan 7.2")
        assertMentions("AC-3", name, "fun entry(context: Context, item: ChannelInfoItem): String", "plan 7.2")
        assertMentions("AC-3", name, "fun choose(context: Context, item: ChannelInfoItem)", "plan 7.2")
        assertMentions("AC-3", name, "R.string.enh_refresh_cooldown_entry", "plan 7.2")
        assertMentions("AC-3", name, "CooldownValues.subscriptionKey(", "plan 7.2: by service and address")
        assertMentions("AC-3", name, "item.serviceId", "plan 7.2")
        assertMentions("AC-3", name, "item.url", "plan 7.2")
        assertMentions("AC-3", name, "CooldownStore.subscriptions(", "plan 7.2")
        assertMentionsOnce("AC-3", name, "CooldownStore.setSubscription(", "plan 7.2: stored at once")
        assertMentions("AC-3", name, "CooldownChooser.show(", "plan 7.2")
    }
}
