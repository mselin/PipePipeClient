package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan section 6, "Wiring on the JVM" (code review finding F13): the hooks of this feature are
 * single lines inside upstream files. A hook lost in a rebase leaves the feature silently off,
 * so this test reads the seven files as text and checks every hook is still there.
 */
class KeepPositionWiringTest {
    private val marker = "ENH:keep-position"
    private val glue = "org.schabi.newpipe.enhancements.keepposition.KeepPosition"
    private val java = "java/org/schabi/newpipe"

    /** file -> the glue functions its marked lines must call, one line each, in this order */
    private val hooks = linkedMapOf(
        "$java/player/Player.java" to listOf("onReload", "shouldSeekOnLoad"),
        "$java/player/PlayerTransportController.kt" to listOf("onSynchronize", "shouldSeekOnSynchronize"),
        "$java/player/PlayerStartController.kt" to listOf("shouldSeekInPlace"),
        "$java/player/PlayerClickController.kt" to listOf("onSwitchToMainPlayer"),
        "$java/util/external_communication/InternalUrlsHandler.java" to listOf("onTimestampLink"),
        "$java/player/playqueue/SinglePlayQueue.java" to listOf("onLinkStartPosition"),
    )
    private val settingsXml = "res/xml/advanced_settings.xml"

    private fun markedLines(path: String): List<String> {
        val inModule = File("src/main/$path")
        val file = if (inModule.exists()) inModule else File("app/src/main/$path")
        assertTrue("$path must exist", file.isFile)
        return file.readLines().filter { it.contains(marker) }
    }

    @Test
    fun `every code hook is present once and calls its function by full name`() {
        for ((path, functions) in hooks) {
            val lines = markedLines(path)
            assertEquals("$path: lines marked $marker", functions.size, lines.size)
            functions.forEachIndexed { i, function ->
                assertTrue(
                    "$path: marked line ${i + 1} must call $glue.$function( but is: ${lines[i].trim()}",
                    lines[i].contains("$glue.$function(")
                )
            }
        }
    }

    @Test
    fun `the settings switch is present once`() {
        val lines = markedLines(settingsXml)
        assertEquals("$settingsXml: lines marked $marker", 1, lines.size)
        assertTrue(lines[0].contains("@string/enh_keep_position_key"))
    }

    @Test
    fun `no function is hooked twice`() {
        val all = hooks.keys.flatMap { markedLines(it) }
        for (function in hooks.values.flatten()) {
            assertEquals("calls of $function in marked lines", 1, all.count { it.contains("$glue.$function(") })
        }
    }
}
