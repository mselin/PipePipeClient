package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hooks of plan sections 2 and 3, checked on the JVM by reading the two files as text (plan section 6,
 * "Wiring on the JVM"). The glue cannot run without Android, but a hook that is missing can be seen: each
 * file must hold the planned lines marked `ENH:default-open-action`.
 *
 * - `enhancements/queueguard/QueueGuard.kt`, queue-guard's glue: G1 hands queue-guard's policy to
 *   `DefaultOpenAction.policy(...)`, G2 gives the tap dialog `DefaultOpenAction.tapDialogExtension`.
 * - `res/xml/video_audio_settings.xml`, upstream's settings screen "Player": S2 and S3, one line each.
 *   Where they sit as XML elements and what they hold is checked in [DefaultOpenActionResourcesTest].
 *
 * Until the Code layers of queue-guard and of this feature have written the lines, every test here fails:
 * `QueueGuard.kt` does not exist, or the count of marked lines is 0.
 *
 * Amendment 2 (plan "Amendment 2", summary): "no hook is added, changed or removed. Lines marked
 * `ENH:default-open-action` stay at `enhancements/queueguard/QueueGuard.kt` 2 and
 * `res/xml/video_audio_settings.xml` 2", so the six tests of before stand unchanged. New are the text
 * checks of AM-1 (plan B2.6: "`DefaultOpenAction.kt` names `R.string.enh_default_open_action_asking_off`
 * (read as text)"): the glue that shows the notice cannot run without Android, but that it shows the
 * notice only for a saved choice, with the three names of plan B2.4, can be read.
 */
class DefaultOpenActionWiringTest {

    private val marker = "ENH:default-open-action"
    private val queueGuardMarker = "ENH:queue-guard"
    private val defaultOpenAction = "org.schabi.newpipe.enhancements.defaultopenaction.DefaultOpenAction"

    // below app/src/main
    private val queueGuardPath = "java/org/schabi/newpipe/enhancements/queueguard/QueueGuard.kt"
    private val settingsPath = "res/xml/video_audio_settings.xml"
    private val gluePath = "java/org/schabi/newpipe/enhancements/defaultopenaction/DefaultOpenAction.kt"
    private val rememberPath = "java/org/schabi/newpipe/enhancements/defaultopenaction/RememberChoice.kt"

    // the same files as failure messages name them
    private val queueGuardFile = "app/src/main/$queueGuardPath"
    private val settingsFile = "app/src/main/$settingsPath"

    private fun queueGuardLines(): List<String> =
        MainFiles.file(
            queueGuardPath,
            "queue-guard's glue object (QG plan 3.3); queue-guard's code has to be on the branch before G1 " +
                "and G2 can be made (plan section 7, step 0)",
        ).readLines()

    private fun settingsLines(): List<String> =
        MainFiles.file(settingsPath, "upstream's settings screen \"Player\"").readLines()

    private fun List<String>.shown(): String = if (isEmpty()) "none" else joinToString(" | ") { it.trim() }

    // ---- G1, G2: queue-guard's glue ----

    /**
     * G1 (plan section 2): `private val policy: GuardPolicy =
     * DOA.policy(QueueGuardPolicy(PrefsGuardSettings), PrefsGuardSettings) // ENH:default-open-action`, with
     * the class named in full so that no import line is added. Through this line every rule of this feature
     * reaches the taps. The second argument is queue-guard's switch "Ask before replacing the queue", the
     * very object the wrapped policy was built with: with asking on the default action is not used (AC-4),
     * with asking off it is (AC-3), and both policies must read the one switch (plan section 8).
     */
    @Test
    fun ac3_ac4_ac5_ac11_hookG1_queueGuardWrapsItsPolicyInDefaultOpenAction() {
        val call = "$defaultOpenAction.policy(QueueGuardPolicy(PrefsGuardSettings), PrefsGuardSettings)"
        val lines = queueGuardLines().filter { marker in it && "DefaultOpenAction.policy(" in it }
        assertEquals("G1: lines marked $marker in $queueGuardFile that call DefaultOpenAction.policy(", 1, lines.size)
        assertTrue("G1 must call $call, the line is: ${lines.shown()}", call in lines.single())
        assertTrue(
            "G1 replaces the declaration of queue-guard's `policy`, the line is: ${lines.shown()}",
            Regex("""\bval policy\b""").containsMatchIn(lines.single()),
        )
    }

    /**
     * G2 (plan section 2): `private val tapDialogExtension: TapDialogExtension? = DOA.tapDialogExtension //
     * ENH:default-open-action`. Through this line the tap dialog gets the checkbox (AC-7) whose state is
     * saved with the choice (AC-8).
     */
    @Test
    fun ac7_ac8_hookG2_queueGuardTakesItsTapDialogExtensionFromDefaultOpenAction() {
        val reference = "$defaultOpenAction.tapDialogExtension"
        val lines = queueGuardLines().filter { marker in it && "DefaultOpenAction.tapDialogExtension" in it }
        assertEquals(
            "G2: lines marked $marker in $queueGuardFile that read DefaultOpenAction.tapDialogExtension",
            1,
            lines.size,
        )
        assertTrue("G2 must read $reference, the line is: ${lines.shown()}", reference in lines.single())
        assertTrue(
            "G2 replaces the declaration of queue-guard's `tapDialogExtension`, the line is: ${lines.shown()}",
            Regex("""\bval tapDialogExtension\b""").containsMatchIn(lines.single()),
        )
    }

    /**
     * Plan section 6: "`QueueGuard.kt` 2". Plan section 2: "Two single-line replacements ... Nothing else
     * of QG changes"; G1 and G2 carry the full class name "so that no import line is added". With that,
     * queue-guard's file differs from queue-guard alone in these two lines only (AC-14).
     */
    @Test
    fun ac14_hooksG1G2_areTheOnlyTwoMarkedLinesOfQueueGuardAndNeedNoImport() {
        val lines = queueGuardLines()
        val marked = lines.filter { marker in it }
        assertEquals("G1, G2: lines marked $marker in $queueGuardFile (${marked.shown()})", 2, marked.size)

        val imports = lines.filter { it.trim().startsWith("import ") && "enhancements.defaultopenaction" in it }
        assertTrue(
            "$queueGuardFile must not import from this feature's package, G1 and G2 name the class in full: " +
                imports.shown(),
            imports.isEmpty(),
        )
    }

    // ---- S2, S3: the settings screen ----

    /** S2 (plan section 3): the whole `<ListPreference ... />` of the default action on one marked line. */
    @Test
    fun ac1_hookS2_playerSettingsScreenHasTheDefaultActionOnOneMarkedLine() {
        val key = "android:key=\"@string/enh_default_open_action_key\""
        val lines = settingsLines().filter { marker in it && key in it }
        assertEquals("S2: lines marked $marker in $settingsFile that hold $key", 1, lines.size)
        assertTrue(
            "S2 must be one whole element on its line (<ListPreference ... />): ${lines.shown()}",
            "<ListPreference" in lines.single() && "/>" in lines.single(),
        )
    }

    /** S3 (plan section 3): the whole `<ListPreference ... />` of "Start playback in" on one marked line. */
    @Test
    fun ac2_hookS3_playerSettingsScreenHasStartPlaybackInOnOneMarkedLine() {
        val key = "android:key=\"@string/enh_default_open_action_start_in_key\""
        val lines = settingsLines().filter { marker in it && key in it }
        assertEquals("S3: lines marked $marker in $settingsFile that hold $key", 1, lines.size)
        assertTrue(
            "S3 must be one whole element on its line (<ListPreference ... />): ${lines.shown()}",
            "<ListPreference" in lines.single() && "/>" in lines.single(),
        )
    }

    /**
     * Plan section 6: "`res/xml/video_audio_settings.xml` 2". Plan section 3: "2 added lines", placed
     * "directly after QG's S1 line ..., S2 then S3, ... and with no blank line added".
     */
    @Test
    fun ac1_ac2_hooksS2S3_areTheTwoLinesRightBelowQueueGuardsSwitchLine() {
        val lines = settingsLines()
        val marked = lines.withIndex().filter { marker in it.value }
        assertEquals(
            "S2, S3: lines marked $marker in $settingsFile (${marked.map { it.value }.shown()})",
            2,
            marked.size,
        )

        val switchLines = lines.withIndex().filter { queueGuardMarker in it.value }
        assertEquals(
            "queue-guard's S1: lines marked $queueGuardMarker in $settingsFile (queue-guard's code comes first, " +
                "plan section 7, step 0)",
            1,
            switchLines.size,
        )
        val s1 = switchLines.single().index
        assertEquals(
            "S2 and S3 must be the two lines right below queue-guard's switch (line ${s1 + 1} of $settingsFile); " +
                "lines of the marked lines",
            listOf(s1 + 2, s1 + 3),
            marked.map { it.index + 1 },
        )
        assertTrue(
            "S2 comes first, then S3: ${marked.map { it.value }.shown()}",
            "enh_default_open_action_key" in marked[0].value &&
                "enh_default_open_action_start_in_key" in marked[1].value,
        )
    }

    // ---- AM-1: the notice after a remembered choice (plan B2.4) ----

    private val glueFile = "app/src/main/$gluePath"

    /** A Kotlin source without its comments, all whitespace removed: the code as the compiler sees it, on one line. */
    private fun codeOf(path: String, whatItIs: String): String =
        MainFiles.file(path, whatItIs).readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("") { it.substringBefore("//") }
            .replace(Regex("\\s+"), "")

    private fun glueCode(): String = codeOf(gluePath, "this feature's Android glue (plan 4.4, B2.4)")

    /** The code of `class RememberChoiceAddOn`, from its declaration to the end of the file. */
    private fun addOnCode(): String {
        val code = glueCode()
        val start = code.indexOf("classRememberChoiceAddOn(")
        assertTrue("$glueFile must declare class RememberChoiceAddOn (plan 4.4)", start >= 0)
        return code.substring(start)
    }

    private fun String.count(part: String): Int = split(part).size - 1

    /**
     * AM-1, plan B2.6: "`DefaultOpenAction.kt` names `R.string.enh_default_open_action_asking_off`", once,
     * in the checkbox add-on, in a toast (plan B2.4, questions.md A10: "a toast with that text"), and the
     * toast is shown (`.show()`), for a long time (`Toast.LENGTH_LONG`): the text is two short sentences
     * with a path in it.
     */
    @Test
    fun am1_glueShowsTheNoticeAsOneLongToastFromTheCheckboxAddOn() {
        val notice = "R.string.enh_default_open_action_asking_off"
        assertEquals("references to $notice in $glueFile", 1, glueCode().count(notice))

        val addOn = addOnCode()
        assertEquals("references to $notice in RememberChoiceAddOn", 1, addOn.count(notice))
        assertEquals("Toast.makeText( in RememberChoiceAddOn", 1, addOn.count("Toast.makeText("))
        assertEquals("Toast.makeText( in $glueFile", 1, glueCode().count("Toast.makeText("))
        val toast = addOn.substring(addOn.indexOf("Toast.makeText("))
        assertTrue("the toast must be built with $notice: $toast", notice in toast)
        assertTrue("the toast must be a long one (Toast.LENGTH_LONG): $toast", "Toast.LENGTH_LONG" in toast)
        assertTrue(
            "the toast must be shown: .show() after Toast.LENGTH_LONG in $toast",
            ".show()" in toast.substringAfter("Toast.LENGTH_LONG"),
        )
    }

    /**
     * AM-1, plan B2.4: the notice's three places are filled with `R.string.settings`,
     * `R.string.settings_category_player_title` and `R.string.enh_queue_guard_ask_title`, in that order
     * ("%1$s > %2$s > %3$s": Settings > Player > Ask before replacing the queue), each read with
     * `context.getString`, "so the text is in the app's language".
     */
    @Test
    fun am1_glueFillsTheNoticeWithSettingsPlayerAndTheSwitchsTitleInThatOrder() {
        val addOn = addOnCode()
        val expected = "context.getString(R.string.enh_default_open_action_asking_off," +
            "context.getString(R.string.settings)," +
            "context.getString(R.string.settings_category_player_title)," +
            "context.getString(R.string.enh_queue_guard_ask_title)"
        assertTrue(
            "RememberChoiceAddOn must build the notice as plan B2.4 has it, $expected (whitespace and a " +
                "trailing comma aside); its code is: $addOn",
            expected in addOn,
        )
    }

    /**
     * AM-1 with AC-9, plan B2.4: `if (remember.onChoice(choice, checkBox.isChecked)) { Toast... }`. The
     * notice is shown only when `RememberChoice.onChoice` returned `true`, i.e. only when the choice was
     * saved (plan B2.3): "the message comes with every remembered choice and with no other click", not for
     * an unchecked box and not for *Cancel*. Read as text: `onChoice` is called once, as the condition of
     * an `if`, with the checkbox as it is at that moment, and the one toast of the file comes after that
     * condition, inside the add-on's `onChoice`; the `if` has no `else`.
     */
    @Test
    fun am1_ac9_glueShowsTheNoticeOnlyWhenRememberChoiceSaidTheChoiceWasSaved() {
        val addOn = addOnCode()
        val condition = "if(remember.onChoice(choice,checkBox.isChecked)){"
        assertEquals("calls of remember.onChoice( in RememberChoiceAddOn", 1, addOn.count("remember.onChoice("))
        assertEquals(
            "RememberChoiceAddOn must decide about the notice with `if (remember.onChoice(choice, " +
                "checkBox.isChecked)) {` (plan B2.4); occurrences in: $addOn",
            1,
            addOn.count(condition),
        )
        val override = addOn.indexOf("overridefunonChoice(choice:GuardChoice)")
        val conditionAt = addOn.indexOf(condition)
        val toastAt = addOn.indexOf("Toast.makeText(")
        assertTrue("RememberChoiceAddOn must override onChoice(choice: GuardChoice)", override >= 0)
        assertTrue(
            "the condition must stand in the add-on's onChoice and the toast must come after it, inside " +
                "the if: $addOn",
            override in 0 until conditionAt && conditionAt < toastAt,
        )
        assertTrue(
            "the if of the notice has no else branch: no toast when nothing was saved: $addOn",
            "else" !in addOn.substring(conditionAt),
        )
    }

    /**
     * AM-1, plan B2.3 ("plain Kotlin") and PROCESS.md ("Decision logic is plain Kotlin with no Android
     * imports"): when the notice is due is decided in `RememberChoice.kt`, which must stay free of Android
     * so that [RememberChoiceTest] can pin it; showing it is the glue's part.
     */
    @Test
    fun am1_rememberChoiceDecidesAboutTheNoticeWithoutAndroid() {
        val imports = MainFiles.file(rememberPath, "RememberChoice (plan 4.3, B2.3)").readLines()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
        val android = imports.filter { it.startsWith("import android.") || it.startsWith("import androidx.") }
        assertTrue("app/src/main/$rememberPath must not import Android: ${android.shown()}", android.isEmpty())
        assertTrue(
            "RememberChoice.onChoice must return Boolean (plan B2.3)",
            "funonChoice(choice:GuardChoice,checked:Boolean):Boolean" in
                codeOf(rememberPath, "RememberChoice (plan 4.3, B2.3)"),
        )
    }
}
