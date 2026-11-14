package org.schabi.newpipe.enhancements

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves that the JVM unit test source set is compiled and run.
 * Feature tests live in sub-packages: `enhancements/<feature>/`.
 */
class ScaffoldingTest {
    @Test
    fun unitTestsRun() {
        assertEquals(4, 2 + 2)
    }
}
