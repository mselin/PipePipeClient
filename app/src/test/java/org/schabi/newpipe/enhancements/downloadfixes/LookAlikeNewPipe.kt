package org.schabi.newpipes

import java.io.Serializable

/**
 * Test fixture of `enhancements.downloadfixes`, deliberately declared in a package whose name
 * only *begins like* `org.schabi.newpipe`: the allow-list prefix is `org.schabi.newpipe.` with
 * the dot.
 */
class LookAlikeNewPipe(val text: String = "x") : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
