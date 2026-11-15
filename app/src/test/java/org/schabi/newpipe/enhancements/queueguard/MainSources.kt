package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.fail
import java.io.File

/**
 * Read access to `app/src/main` for the tests that look at files instead of calling code: the resource
 * XML (plan section 4, S1) and the hook lines (plan section 2).
 *
 * Gradle runs unit tests with `app/` as working directory, so `src/main` is tried first; `app/src/main`
 * is the fallback for a run from the repository root (an IDE, for instance).
 */
internal object MainSources {

    private val candidates = listOf("src/main", "app/src/main")

    /** The `src/main` directory of the app module. */
    val root: File by lazy {
        candidates.map { File(it) }.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "Cannot find the app's main sources: none of $candidates is a directory below " +
                    File("").absolutePath,
            )
    }

    /** [path] below `app/src/main`, whether it exists or not. */
    fun resolve(path: String): File = File(root, path)

    /**
     * The existing file [path] below `app/src/main`. If it is missing the test fails with [whatItIs],
     * which says what the Code layer has to deliver there.
     */
    fun file(path: String, whatItIs: String): File {
        val file = resolve(path)
        if (!file.isFile) {
            fail("app/src/main/$path does not exist: $whatItIs")
        }
        return file
    }
}
