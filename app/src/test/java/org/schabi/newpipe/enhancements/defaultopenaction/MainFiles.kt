package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.fail
import java.io.File

/**
 * Read access to `app/src/main` for the tests that look at files instead of calling code: the resource
 * XML (plan sections 3 and 5) and the hook lines (plan sections 2 and 3).
 *
 * Gradle runs unit tests with `app/` as working directory, so `src/main` is tried first; `app/src/main`
 * is the fallback for a run from the repository root (an IDE, for instance).
 */
internal object MainFiles {

    private val candidates = listOf("src/main", "app/src/main")

    /** The `src/main` directory of the app module. */
    val root: File by lazy {
        candidates.map { File(it) }.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "Cannot find the app's main sources: none of $candidates is a directory below " +
                    File("").absolutePath,
            )
    }

    /**
     * The existing file [path] below `app/src/main`. If it is missing the test fails with [whatItIs],
     * which says who has to deliver the file.
     */
    fun file(path: String, whatItIs: String): File {
        val file = File(root, path)
        if (!file.isFile) {
            fail("app/src/main/$path does not exist: $whatItIs")
        }
        return file
    }
}
