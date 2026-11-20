package org.schabi.newpipe.enhancements.releaseviews

import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.fragments.list.channel.ChannelVideosFragment
import org.schabi.newpipe.util.PicassoHelper

/**
 * AC-6: a channel banner is loaded no larger than the view that shows it.
 *
 * Not run in this repository's environment (no device); see questions.md A4.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BannerSizeTest {

    /**
     * Sets up: image loading switched on for the test (and put back afterwards); a channel
     * videos page built from a hand-built `ChannelInfo` whose banner url is the drawable bundled
     * with the app, `drawable-nodpi/channel_banner` (2550 x 427 px), addressed as an
     * `android.resource://` url. Picasso loads such a url from the app's resources, so no request
     * is made. The url uses the numeric resource id instead of the plan's `drawable/channel_banner`
     * path: same drawable, but independent of how the build names the resource package (the
     * application id differs from the code namespace). Logged as Q8 in questions.md.
     *
     * Asserts: the bitmap Picasso put into the banner view is at most as wide and as high as the
     * view. The header layout shows the same drawable as placeholder before any load, so the test
     * first waits until the view's drawable is the one Picasso sets for a loaded image
     * (`PicassoDrawable`); a banner that never loads fails the test with a message saying so,
     * it does not pass on the placeholder.
     *
     * Deterministic; the look of the banner (same crop as today) is not checked here, that is
     * step 7 of the device script.
     *
     * Expected to fail on the unfixed app because `PicassoHelper.loadBanner` sets no size limit
     * and the channel header loads the banner at its full size: the bitmap is 2550 x 427, larger
     * than a 70 dp high view on any device (plan 1 M3, 5).
     */
    @Test
    fun ac6_bannerBitmapIsNotLargerThanItsView() {
        val context = targetContext()
        val bannerUrl = "android.resource://${context.packageName}/${R.drawable.channel_banner}"
        val imagesWereEnabled = PicassoHelper.getShouldLoadImages()

        launchMainActivity().use { scenario ->
            try {
                val page = scenario.onMain {
                    PicassoHelper.setShouldLoadImages(true)
                    ChannelVideosFragment.getInstance(
                        handBuiltChannel("ac6videos", 40, bannerUrl = bannerUrl)
                    )
                }
                scenario.openPage(page)

                val loaded = scenario.waitUntil(timeoutMs = 15_000) {
                    val banner = bannerOf(page)
                    banner != null && banner.width > 0 && isLoadedByPicasso(banner)
                }
                assertTrue(
                    "set-up: Picasso did not load the bundled banner into the header " +
                        "(the view still shows its placeholder)",
                    loaded
                )

                val sizes = scenario.onMain {
                    val banner = bannerOf(page)!!
                    val bitmap = (banner.drawable as BitmapDrawable).bitmap
                    intArrayOf(bitmap.width, bitmap.height, banner.width, banner.height)
                }
                assertTrue(
                    "the banner bitmap is ${sizes[0]} x ${sizes[1]} px, " +
                        "its view only ${sizes[2]} x ${sizes[3]} px",
                    sizes[0] <= sizes[2] && sizes[1] <= sizes[3]
                )
            } finally {
                PicassoHelper.setShouldLoadImages(imagesWereEnabled)
            }
        }
    }

    private fun bannerOf(page: ChannelVideosFragment): ImageView? =
        page.view?.findViewById(R.id.channel_banner_image)

    /** Picasso wraps every image it loads in its own (package-private) drawable class. */
    private fun isLoadedByPicasso(view: ImageView): Boolean {
        val drawable = view.drawable
        return drawable is BitmapDrawable && drawable.javaClass.simpleName == "PicassoDrawable"
    }
}
