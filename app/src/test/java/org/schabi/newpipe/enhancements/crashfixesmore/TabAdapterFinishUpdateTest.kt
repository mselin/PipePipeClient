@file:Suppress("DEPRECATION")

package org.schabi.newpipe.enhancements.crashfixesmore

import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.fragment.app.FragmentTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

/**
 * AC-5, plan C5b: what `TabAdapter.finishUpdate` does when the pager's commit throws. This is
 * the one piece of the plan that is logic of its own (swallow, log once, keep swallowing) and
 * can run on the JVM. It runs the real, compiled `TabAdapter` on top of the real
 * `FragmentPagerAdapter` of the unit-test classpath.
 *
 * **What is real and what is not.**
 * - Real: `TabAdapter` and `androidx.fragment.app.FragmentPagerAdapter.finishUpdate`, which
 *   commits its pending transaction with `commitNowAllowingStateLoss()` and forgets the
 *   transaction only when the commit returned.
 * - Not real: the pending transaction. It is put into the adapter's private field
 *   `mCurTransaction` by reflection and throws from `commitNowAllowingStateLoss()` what the
 *   plan says the library throws. A real `FragmentManager` and a real `ViewPager` cannot be
 *   built here: the `android.jar` of unit tests has no implementation (no `Looper`, no
 *   `View`). So **this test does not show that the library throws**, on the first failure or
 *   as `Fragment already added` on every later one, **nor that the page survives**. That is
 *   the instrumented test `ChannelPageFailingTabTest` (I2), on a device.
 * - `android.util.Log`: in unit tests every method of it throws "not mocked", which would hide
 *   what the new lines do. `TabAdapter` is therefore loaded a second time by a class loader
 *   of this test, together with a recording `android.util.Log` that this test compiles at run
 *   time with the JDK's compiler (found by name). No build file, no library and no file outside this package
 *   is involved.
 *
 * Before the Code layer adds the override, `finishUpdate` is the library's and the first test
 * fails with the `IllegalStateException` leaving it.
 */
class TabAdapterFinishUpdateTest {

    private companion object {
        const val TAB_ADAPTER = "org.schabi.newpipe.fragments.detail.TabAdapter"
        const val LOG = "android.util.Log"

        /** Records `Log.e` calls; every other level is accepted and dropped. */
        val LOG_SOURCE = """
            package android.util;
            public final class Log {
                public static final java.util.List<Object[]> ERRORS = new java.util.ArrayList<>();
                public static int e(String tag, String msg, Throwable tr) {
                    ERRORS.add(new Object[] {tag, msg, tr});
                    return 0;
                }
                public static int e(String tag, String msg) { return e(tag, msg, null); }
                public static int v(String tag, String msg) { return 0; }
                public static int v(String tag, String msg, Throwable tr) { return 0; }
                public static int d(String tag, String msg) { return 0; }
                public static int d(String tag, String msg, Throwable tr) { return 0; }
                public static int i(String tag, String msg) { return 0; }
                public static int i(String tag, String msg, Throwable tr) { return 0; }
                public static int w(String tag, String msg) { return 0; }
                public static int w(String tag, String msg, Throwable tr) { return 0; }
                public static int w(String tag, Throwable tr) { return 0; }
                public static String getStackTraceString(Throwable tr) { return String.valueOf(tr); }
            }
        """.trimIndent()

        /** `android/util/Log.class` of [LOG_SOURCE], compiled once per test run. */
        val logClassBytes: ByteArray by lazy {
            // javax.tools is not part of the android.jar this test is compiled against, but it is
            // part of the JDK the test runs on; hence by name
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
                ?: throw AssertionError("this test needs a JDK (the javax.tools compiler); it runs on a JRE")
            val run = Class.forName("javax.tools.Tool").getMethod(
                "run",
                InputStream::class.java,
                OutputStream::class.java,
                OutputStream::class.java,
                Array<String>::class.java
            )
            val dir = Files.createTempDirectory("crashfixesmore-log").toFile()
            try {
                val source = File(dir, "src/android/util/Log.java")
                source.parentFile!!.mkdirs()
                source.writeText(LOG_SOURCE)
                val out = File(dir, "out").apply { mkdirs() }
                val messages = ByteArrayOutputStream()
                val exitCode = run.invoke(
                    compiler, null, messages, messages,
                    arrayOf("-d", out.path, "-proc:none", "-nowarn", source.path)
                )
                if (exitCode != 0) {
                    throw AssertionError("could not compile the recording android.util.Log: $messages")
                }
                File(out, "android/util/Log.class").readBytes()
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /** Defines `TabAdapter` and the recording `Log` itself; everything else comes from the parent. */
    private class RecordingLogLoader(parent: ClassLoader) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != LOG && name != TAB_ADAPTER && !name.startsWith("$TAB_ADAPTER$")) {
                return super.loadClass(name, resolve)
            }
            synchronized(this) {
                findLoadedClass(name)?.let { return it }
                val bytes = if (name == LOG) {
                    logClassBytes
                } else {
                    val resource = name.replace('.', '/') + ".class"
                    (parent.getResourceAsStream(resource) ?: throw ClassNotFoundException(name))
                        .use { it.readBytes() }
                }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
    }

    /** A transaction of which only `commitNowAllowingStateLoss` matters: it throws [failure]. */
    @Suppress("DEPRECATION")
    private class PendingTransaction(private val failure: () -> RuntimeException?) : FragmentTransaction() {
        var commits = 0
        override fun commit(): Int = throw AssertionError("FragmentPagerAdapter commits with commitNowAllowingStateLoss")
        override fun commitAllowingStateLoss(): Int = throw AssertionError("FragmentPagerAdapter commits with commitNowAllowingStateLoss")
        override fun commitNow() {
            throw AssertionError("FragmentPagerAdapter commits with commitNowAllowingStateLoss")
        }
        override fun commitNowAllowingStateLoss() {
            commits++
            failure()?.let { throw it }
        }
    }

    private lateinit var adapter: Any
    private lateinit var errors: List<Array<Any?>>
    private val pending = FragmentPagerAdapter::class.java.getDeclaredField("mCurTransaction")
        .apply { isAccessible = true }

    @Before
    fun newAdapterWithRecordingLog() {
        val loader = RecordingLogLoader(javaClass.classLoader!!)
        val adapterClass = loader.loadClass(TAB_ADAPTER)
        assertSame("TabAdapter must be the copy that sees the recording Log", loader, adapterClass.classLoader)
        // the fragment manager is never used by finishUpdate, and none can be built on the JVM
        adapter = adapterClass.getConstructor(FragmentManager::class.java).newInstance(*arrayOfNulls<Any>(1))
        @Suppress("UNCHECKED_CAST")
        errors = loader.loadClass(LOG).getField("ERRORS").get(null) as List<Array<Any?>>
        assertTrue(errors.isEmpty())
    }

    /** Calls the adapter's `finishUpdate`; what it throws is returned, not thrown. */
    private fun finishUpdate(): Throwable? {
        // the container is not used by FragmentPagerAdapter.finishUpdate; no ViewGroup can be built here
        val method = adapter.javaClass.getMethod("finishUpdate", ViewGroup::class.java)
        return try {
            method.invoke(adapter, *arrayOfNulls<Any>(1))
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }
    }

    private fun setPending(transaction: FragmentTransaction?) = pending.set(adapter, transaction)

    /**
     * AC-5 "the page does not crash", plan C5b: the `IllegalStateException` of the pager's
     * commit does not leave `finishUpdate`, and it is written to the log with tag `TabAdapter`
     * and the exception itself (so with its stack trace).
     */
    @Test
    fun ac5_finishUpdate_swallowsThePagersIllegalStateExceptionAndLogsIt() {
        val failure = IllegalStateException("thrown while the tab fragment was created")
        val transaction = PendingTransaction { failure }
        setPending(transaction)

        val thrown = finishUpdate()

        if (thrown != null) {
            throw AssertionError(
                "AC-5, plan C5b: TabAdapter.finishUpdate must catch the IllegalStateException of the " +
                    "pager's commit; it left the method (is the override missing?)",
                thrown
            )
        }
        assertEquals("the pending transaction must have been committed once", 1, transaction.commits)
        assertEquals("AC-5, plan C5b: the failure must be written to the log with Log.e", 1, errors.size)
        assertEquals("AC-5, plan C5b: log tag", "TabAdapter", errors[0][0])
        assertSame("AC-5, plan C5b: the exception must be passed to Log.e", failure, errors[0][2])
    }

    /**
     * AC-5 "now or at the next layout" and "written to the log once", plan C5b: the library
     * keeps the failed transaction (premise of the plan, asserted here on the real
     * `FragmentPagerAdapter`), so every later `finishUpdate` (one per layout pass) commits it
     * again and fails again. Each of these is swallowed too, and none of them is logged.
     */
    @Test
    fun ac5_finishUpdate_keepsSwallowingAndLogsOnlyTheFirstFailure() {
        var call = 0
        val transaction = PendingTransaction {
            call++
            IllegalStateException(if (call == 1) "first failure" else "Fragment already added: tab $call")
        }
        setPending(transaction)

        for (pass in 1..5) {
            val thrown = finishUpdate()
            if (thrown != null) {
                throw AssertionError(
                    "AC-5, plan C5b: finishUpdate number $pass after a pager failure must not throw " +
                        "(pass 1 is the failure itself, each later one is a layout pass)",
                    thrown
                )
            }
            assertSame(
                "premise of plan C5b: FragmentPagerAdapter keeps the transaction whose commit threw " +
                    "(if this fails the library changed and the plan's failure path has to be read again)",
                transaction,
                pending.get(adapter)
            )
        }
        assertEquals("the stale transaction is committed again on every pass", 5, transaction.commits)
        assertEquals(
            "AC-5, plan C5b: the failure is written to the log once, not once per layout pass; " +
                "logged: ${errors.map { it[1].toString() + " / " + it[2] }}",
            1,
            errors.size
        )
        assertEquals("AC-5, plan C5b: the logged exception is the first failure", "first failure", (errors[0][2] as Throwable?)?.message)
    }

    /**
     * AC-6, plan C5b "regression risk": only `IllegalStateException` is swallowed. Any other
     * exception of the commit leaves `finishUpdate` as before and is not logged by it.
     */
    @Test
    fun ac6_finishUpdate_letsOtherExceptionsThrough() {
        val failure = NullPointerException("not the pager's IllegalStateException")
        setPending(PendingTransaction { failure })

        val thrown = finishUpdate()

        assertSame("AC-6, plan C5b: an exception that is no IllegalStateException must not be swallowed", failure, thrown)
        assertTrue("AC-6, plan C5b: nothing is logged for it by finishUpdate: $errors", errors.isEmpty())
    }

    /**
     * AC-6: a pager that works is untouched. The pending transaction is committed once and
     * forgotten, nothing is logged, and a `finishUpdate` without pending transaction does
     * nothing. Passes before Code exists (it is the library's behaviour).
     */
    @Test
    fun ac6_finishUpdate_workingPagerIsUnchanged() {
        val transaction = PendingTransaction { null }
        setPending(transaction)

        assertNull("a commit that works must not make finishUpdate throw", finishUpdate())
        assertEquals(1, transaction.commits)
        assertNull("the committed transaction must be forgotten", pending.get(adapter))

        assertNull(finishUpdate())
        assertEquals("nothing is pending, nothing is committed", 1, transaction.commits)
        assertTrue("AC-6: a working pager logs no error: $errors", errors.isEmpty())
    }

    /**
     * AC-5, plan C5b ("self-healing" in its regression risk, and the reason the flag only
     * guards the log line): after a failure was swallowed, a later transaction that works is
     * still committed and forgotten. The adapter does not stop calling the pager.
     */
    @Test
    fun ac5_finishUpdate_afterAFailureAWorkingTransactionIsStillCommitted() {
        setPending(PendingTransaction { IllegalStateException("FragmentManager is already executing transactions") })
        finishUpdate()?.let {
            throw AssertionError("AC-5, plan C5b: finishUpdate must catch the IllegalStateException", it)
        }
        assertNotNull(pending.get(adapter))

        val working = PendingTransaction { null }
        setPending(working)
        finishUpdate()?.let { fail("a working transaction after a swallowed failure must not throw: $it") }

        assertEquals("AC-5, plan C5b: super.finishUpdate must still be called after a failure", 1, working.commits)
        assertNull(pending.get(adapter))
        assertEquals("only the failure is logged", 1, errors.size)
    }
}
