package org.schabi.newpipe.enhancements.releaseviews

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Picasso throws when `onlyScaleDown()` is used on a request that gets its size from `fit()`.
 * That crashed every channel page on a device (questions.md, Q10). This reads the source of the
 * channel videos page and checks the banner request.
 */
class BannerRequestSourceTest {
    private val source: String = run {
        val path = "java/org/schabi/newpipe/fragments/list/channel/ChannelVideosFragment.java"
        val inModule = File("src/main/$path")
        (if (inModule.exists()) inModule else File("app/src/main/$path")).readText()
    }

    @Test
    fun `the banner is requested at the size of its view`() {
        assertTrue(source.contains(".fit().centerCrop()"))
    }

    @Test
    fun `no request combines fit with onlyScaleDown`() {
        assertFalse(source.contains("onlyScaleDown"))
    }
}
