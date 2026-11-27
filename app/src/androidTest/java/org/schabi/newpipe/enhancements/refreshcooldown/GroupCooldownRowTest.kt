package org.schabi.newpipe.enhancements.refreshcooldown

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import org.schabi.newpipe.util.Localization
import kotlin.math.roundToInt

/**
 * AC-2, AC-3, AC-4 (plan 7.1, 7.2, 5.1 and section 13): the row `GroupCooldownUi.attach` adds
 * to the real layout of the group dialog, what `GroupCooldownUi.save` stores, and the entry
 * text of a channel's long-press menu, with the app's real resources and preferences.
 *
 * The dialog's layout (`dialog_feed_group_create`) is inflated with the app's dark theme;
 * `options_root` is made visible, measured and laid out by hand at 360 dp width and the height
 * its layout parameters give, on the main thread. It is not attached to a window.
 *
 * What the JVM tests cannot show, and these do: what the plan only assumes in section 12 (a
 * view inflated with a `ConstraintLayout` parent gets its constraints; `@id/…` of another
 * layout file resolves), where the row ends up, that the fixed height grows once, and that the
 * values go through the default shared preferences in the plan's format.
 *
 * What a pass does not prove: nothing about `FeedGroupDialog` itself (that it calls `attach`
 * and `save`: the wiring test), the chooser (it needs a window: device step 2), a choice made
 * and then cancelled, landscape or a small screen, other themes and font sizes, and whether
 * the row looks right.
 *
 * Instrumented: needs a device or emulator. Not run by CI, only compiled; run it with the
 * plan's device script. It uses only what `androidTest` already depends on. The tests write the
 * two preferences of this feature in the app under test and restore what was there.
 */
@RunWith(AndroidJUnit4::class)
class GroupCooldownRowTest {

    private companion object {
        const val GROUPS_KEY = "enh_refresh_cooldown_groups"
        const val SUBSCRIPTIONS_KEY = "enh_refresh_cooldown_subscriptions"
        const val WIDTH_DP = 360
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var groupsBefore: Any? = null
    private var subscriptionsBefore: Any? = null

    @Before
    fun rememberThePreferences() {
        groupsBefore = preferences.all[GROUPS_KEY]
        subscriptionsBefore = preferences.all[SUBSCRIPTIONS_KEY]
        preferences.edit().remove(GROUPS_KEY).remove(SUBSCRIPTIONS_KEY).commit()
    }

    @After
    fun restoreThePreferences() {
        val editor = preferences.edit().remove(GROUPS_KEY).remove(SUBSCRIPTIONS_KEY)
        (groupsBefore as? String)?.let { editor.putString(GROUPS_KEY, it) }
        (subscriptionsBefore as? String)?.let { editor.putString(SUBSCRIPTIONS_KEY, it) }
        editor.commit()
    }

    /** The dialog's options, as far as the row is concerned. */
    private class Options(val root: ViewGroup) {
        val density: Float = root.resources.displayMetrics.density
        val count: View = root.findViewById(R.id.selected_subscription_count_view)
        val select: View = root.findViewById(R.id.select_channel_button)
        val row: TextView? get() = root.findViewById(R.id.enh_refresh_cooldown_group_button)
        val rows: Int get() = (0 until root.childCount).count { root.getChildAt(it).id == R.id.enh_refresh_cooldown_group_button }
        val height: Int get() = root.layoutParams.height

        fun layout() {
            val width = (WIDTH_DP * density).roundToInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, width, height)
        }
    }

    private fun onMain(body: () -> Unit) = instrumentation.runOnMainSync(body)

    private fun inflateOptions(): Options {
        val themed = ContextThemeWrapper(context, R.style.DarkTheme)
        val dialog = LayoutInflater.from(themed).inflate(R.layout.dialog_feed_group_create, null)
        val root = dialog.findViewById<ViewGroup>(R.id.options_root)
        root.visibility = View.VISIBLE
        (root.findViewById<View>(R.id.selected_subscription_count_view) as TextView).text = "15 selected"
        return Options(root)
    }

    private fun music() = FeedGroupEntity(7, "Music", FeedGroupIcon.ALL)

    private fun dp(options: Options, value: Int): Int = (value * options.density).roundToInt()

    /**
     * AC-2, plan 7.1: for an existing group the row is there, says "Refresh cooldown: Use
     * default", sits between the "n selected" line and "Select subscriptions" without
     * overlapping either, and the options are 48 dp higher than before.
     */
    @Test
    fun ac2_attach_addsTheRowBetweenTheCountLineAndSelectSubscriptions() {
        onMain {
            val options = inflateOptions()
            options.layout()
            val heightBefore = options.height
            assertTrue("before: the layout's own fixed height", heightBefore > 0)
            assertTrue("before: \"Select subscriptions\" is below the count line", options.select.top >= options.count.bottom)
            assertNull(options.row)

            GroupCooldownUi.attach(options.root, music())
            options.layout()

            val row = options.row
            assertNotNull("AC-2: the row exists", row)
            assertEquals("AC-2: it starts as Use default and shows it", "Refresh cooldown: Use default", row!!.text.toString())
            assertEquals(View.VISIBLE, row.visibility)
            assertEquals("the row is a direct child of the options", options.root, row.parent)
            assertEquals("plan 7.1: the options are 48 dp higher", heightBefore + dp(options, 48), options.height)
            assertEquals("the row is 48 dp high", dp(options, 48), row.height)
            assertTrue("the row has a width (${row.width})", row.width > dp(options, 200))
            assertTrue("its top (${row.top}) is at or below the bottom of the count line (${options.count.bottom})", row.top >= options.count.bottom)
            assertTrue("its bottom (${row.bottom}) is at or above the top of \"Select subscriptions\" (${options.select.top})", row.bottom <= options.select.top)
            assertTrue("\"Select subscriptions\" still ends inside the options (${options.select.bottom} of ${options.root.height})", options.select.bottom <= options.root.height)
            assertTrue("the row can be tapped", row.hasOnClickListeners() && row.isEnabled)
        }
    }

    /** AC-2, plan 7.1: the group arrives again (the dialog observes it): still one row, the height raised once, the shown value kept. */
    @Test
    fun ac2_attachTwice_oneRow_heightRaisedOnce() {
        onMain {
            val options = inflateOptions()
            val heightBefore = options.height

            GroupCooldownUi.attach(options.root, music())
            GroupCooldownUi.attach(options.root, music())
            GroupCooldownUi.attach(options.root, FeedGroupEntity(7, "Music", FeedGroupIcon.ALL))
            options.layout()

            assertEquals(1, options.rows)
            assertEquals(heightBefore + dp(options, 48), options.height)
            assertEquals("Refresh cooldown: Use default", options.row!!.text.toString())
        }
    }

    /** AC-2 ("The dialog that creates a group has no such row"), plan 7.1: no group, no row, nothing changed; and a save without a row stores nothing. */
    @Test
    fun ac2_attachWithoutAGroup_noRow_andSaveStoresNothing() {
        onMain {
            val options = inflateOptions()
            options.layout()
            val heightBefore = options.height
            val selectTop = options.select.top

            GroupCooldownUi.attach(options.root, null)
            options.layout()

            assertNull("AC-2: no row for a new group", options.row)
            assertEquals(0, options.rows)
            assertEquals(heightBefore, options.height)
            assertEquals(selectTop, options.select.top)

            GroupCooldownUi.save(options.root, 7, "New group")
            GroupCooldownUi.save(options.root, -1, "New group")
        }
        assertFalse("a save without a row writes no preference", preferences.contains(GROUPS_KEY))
    }

    /** AC-2 ("The dialog shows the current value"), AC-4: with one hour stored for this id and name, the row says so, in the global setting's own wording. */
    @Test
    fun ac2_attach_showsTheStoredValue() {
        preferences.edit().putString(GROUPS_KEY, "7:0473fe05=3600;9:0024fef3=0").commit()
        onMain {
            val options = inflateOptions()
            GroupCooldownUi.attach(options.root, music())

            assertEquals("Refresh cooldown: " + Localization.localizeDuration(context, 3600), options.row!!.text.toString())
        }
        onMain {
            val news = inflateOptions()
            GroupCooldownUi.attach(news.root, FeedGroupEntity(9, "News", FeedGroupIcon.ALL))
            assertEquals(
                "\"Always update\" keeps the setting's own text",
                "Refresh cooldown: " + context.getString(R.string.feed_update_threshold_option_always_update),
                news.row!!.text.toString()
            )
        }
    }

    /** AC-4, plan 3.3: a value stored for the same id under another name is not this group's: the row says Use default. */
    @Test
    fun ac4_attach_aValueStoredForAnotherNameOfTheId_isNotShown() {
        preferences.edit().putString(GROUPS_KEY, "7:0473fe05=3600").commit()
        onMain {
            val options = inflateOptions()
            GroupCooldownUi.attach(options.root, FeedGroupEntity(7, "Rock", FeedGroupIcon.ALL))

            assertEquals("Refresh cooldown: Use default", options.row!!.text.toString())
        }
    }

    /**
     * AC-2, AC-4, plan 7.1 and 4.6: `save` with a new name stores the entry under the new
     * name's key and removes the old one; another group's entry stays; the text is sorted.
     */
    @Test
    fun ac2_save_withANewName_movesTheEntryToTheNewName() {
        preferences.edit().putString(GROUPS_KEY, "9:0024fef3=0;7:0473fe05=3600").commit()
        onMain {
            val options = inflateOptions()
            GroupCooldownUi.attach(options.root, music())

            GroupCooldownUi.save(options.root, 7, "Rock")
        }
        assertEquals("7:0026f385=3600;9:0024fef3=0", preferences.getString(GROUPS_KEY, null))

        // The dialog opened again for the renamed group shows the value.
        onMain {
            val options = inflateOptions()
            GroupCooldownUi.attach(options.root, FeedGroupEntity(7, "Rock", FeedGroupIcon.ALL))
            assertEquals("Refresh cooldown: " + Localization.localizeDuration(context, 3600), options.row!!.text.toString())
        }
    }

    /** AC-2, plan 7.1: `save` with the name unchanged keeps the entry as it is. */
    @Test
    fun ac2_save_withTheSameName_keepsTheEntry() {
        preferences.edit().putString(GROUPS_KEY, "7:0473fe05=3600").commit()
        onMain {
            val options = inflateOptions()
            GroupCooldownUi.attach(options.root, music())
            GroupCooldownUi.save(options.root, 7, "Music")
        }
        assertEquals("7:0473fe05=3600", preferences.getString(GROUPS_KEY, null))
    }

    /** AC-2, plan 3.1: a group on Use default is saved without an entry, and with no entry at all the preference does not exist. */
    @Test
    fun ac2_save_useDefault_leavesNoPreference() {
        onMain {
            val options = inflateOptions()
            GroupCooldownUi.attach(options.root, music())
            GroupCooldownUi.save(options.root, 7, "Music renamed")
        }
        assertFalse("plan 3.1: \"an app without values has no key of this feature\"", preferences.contains(GROUPS_KEY))
    }

    /** AC-2: attaching the row alone stores nothing: only OK does (cancel and back never reach `save`). */
    @Test
    fun ac2_attach_storesNothing() {
        preferences.edit().putString(GROUPS_KEY, "7:0473fe05=3600;garbage;9:0024fef3=301").commit()
        onMain {
            val options = inflateOptions()
            GroupCooldownUi.attach(options.root, music())
        }
        assertEquals("the stored text is untouched, garbage included", "7:0473fe05=3600;garbage;9:0024fef3=301", preferences.getString(GROUPS_KEY, null))
    }

    // ---- the store and the menu entry ----

    /** AC-1, AC-2, AC-3, plan 5.1: the choices come from the global setting's arrays: seven, in its order, the first with the setting's own text. */
    @Test
    fun ac1_store_choicesAreTheGlobalSettings() {
        val choices = CooldownStore.choices(context)

        assertEquals(listOf(0, 300, 900, 3600, 21600, 43200, 86400), choices.seconds)
        assertEquals(context.getString(R.string.feed_update_threshold_option_always_update), choices.labels[0])
        assertEquals((listOf(300, 900, 3600, 21600, 43200, 86400)).map { Localization.localizeDuration(context, it) }, choices.labels.drop(1))
        assertEquals(8, choices.items(context.getString(R.string.enh_refresh_cooldown_use_default)).size)
    }

    /** AC-4, plan 5.1 ("a value of another type gives empty") and 3.2: whatever a restored backup left under the two keys, reading gives values and never throws. */
    @Test
    fun ac4_store_foreignOrGarbagePreferences_giveNoValues() {
        preferences.edit().putInt(GROUPS_KEY, 5).putBoolean(SUBSCRIPTIONS_KEY, true).commit()
        assertEquals(0, CooldownStore.groups(context).size)
        assertEquals(0, CooldownStore.subscriptions(context).size)

        preferences.edit().putString(GROUPS_KEY, ";;=;7:0473fe05=301;x").putString(SUBSCRIPTIONS_KEY, "").commit()
        assertEquals(0, CooldownStore.groups(context).size)
        assertEquals(0, CooldownStore.subscriptions(context).size)

        preferences.edit().putString(GROUPS_KEY, "7:0473fe05=3600;x").commit()
        assertEquals(3600, CooldownStore.groups(context)["7:0473fe05"])
    }

    /**
     * AC-3, AC-4, plan 7.2 and 5.1: a subscription's value is stored under service and address
     * at once, the menu entry shows it, an address with `;`, `=` and non-ASCII letters does
     * not break the stored text, and setting Use default for the last value removes the
     * preference.
     */
    @Test
    fun ac3_setSubscription_isStoredAndShownByTheMenuEntry() {
        val plain = ChannelInfoItem(0, "https://www.youtube.com/channel/UC-lHJZR3Gqxm24_Vd_AJ5Yw", "A channel")
        val odd = ChannelInfoItem(5, "https://example.org/c/a b;c=d:e?x=1&y=é|z", "Another")
        assertEquals("AC-3: it starts as Use default", "Refresh cooldown: Use default", SubscriptionCooldownUi.entry(context, plain))

        CooldownStore.setSubscription(context, CooldownValues.subscriptionKey(plain.serviceId, plain.url), 86400)
        CooldownStore.setSubscription(context, CooldownValues.subscriptionKey(odd.serviceId, odd.url), 0)

        assertEquals(
            "0:https%3A%2F%2Fwww.youtube.com%2Fchannel%2FUC-lHJZR3Gqxm24_Vd_AJ5Yw=86400;" +
                "5:https%3A%2F%2Fexample.org%2Fc%2Fa+b%3Bc%3Dd%3Ae%3Fx%3D1%26y%3D%C3%A9%7Cz=0",
            preferences.getString(SUBSCRIPTIONS_KEY, null)
        )
        assertEquals("Refresh cooldown: " + Localization.localizeDuration(context, 86400), SubscriptionCooldownUi.entry(context, plain))
        assertEquals("Refresh cooldown: " + context.getString(R.string.feed_update_threshold_option_always_update), SubscriptionCooldownUi.entry(context, odd))
        assertEquals("another channel is on Use default", "Refresh cooldown: Use default", SubscriptionCooldownUi.entry(context, ChannelInfoItem(1, plain.url, "Same address, other service")))
        assertFalse("the groups' preference is not touched", preferences.contains(GROUPS_KEY))

        CooldownStore.setSubscription(context, CooldownValues.subscriptionKey(plain.serviceId, plain.url), null)
        assertEquals("Refresh cooldown: Use default", SubscriptionCooldownUi.entry(context, plain))
        CooldownStore.setSubscription(context, CooldownValues.subscriptionKey(odd.serviceId, odd.url), null)
        assertFalse("plan 3.1: with no entry at all the preference is removed", preferences.contains(SUBSCRIPTIONS_KEY))
    }
}
