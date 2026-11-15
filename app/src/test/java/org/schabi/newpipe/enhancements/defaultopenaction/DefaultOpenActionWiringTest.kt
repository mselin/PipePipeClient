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
 */
class DefaultOpenActionWiringTest {

    private val marker = "ENH:default-open-action"
    private val queueGuardMarker = "ENH:queue-guard"
    private val defaultOpenAction = "org.schabi.newpipe.enhancements.defaultopenaction.DefaultOpenAction"

    // below app/src/main
    private val queueGuardPath = "java/org/schabi/newpipe/enhancements/queueguard/QueueGuard.kt"
    private val settingsPath = "res/xml/video_audio_settings.xml"

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
     * DOA.policy(QueueGuardPolicy(PrefsGuardSettings)) // ENH:default-open-action`, with the class named in
     * full so that no import line is added. Through this line every rule of this feature reaches the taps.
     */
    @Test
    fun ac3_ac5_ac11_hookG1_queueGuardWrapsItsPolicyInDefaultOpenAction() {
        val call = "$defaultOpenAction.policy(QueueGuardPolicy(PrefsGuardSettings))"
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
}
