package us.shandian.gigantic

import java.io.ObjectInputStream
import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test fixture of `enhancements.downloadfixes`, deliberately declared in a package whose name
 * only *begins like* `us.shandian.giga`: the allow-list prefix is `us.shandian.giga.` with the
 * dot. Its `readObject` has a side effect, so a test can tell whether the stream got as far as
 * running code of a class it should have refused.
 */
class LookAlikeGiga(val text: String = "x") : Serializable {
    private fun readObject(input: ObjectInputStream) {
        readObjectCalls.incrementAndGet()
        input.defaultReadObject()
    }

    companion object {
        private const val serialVersionUID = 1L

        @JvmField
        val readObjectCalls = AtomicInteger()
    }
}
