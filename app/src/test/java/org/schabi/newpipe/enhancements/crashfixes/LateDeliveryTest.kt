package org.schabi.newpipe.enhancements.crashfixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * AC-1 / AC-7 (C1, step 1c): `LateDelivery.deliver(callback, value)` hands the value to the
 * callback and swallows only Android's refusal to start the player service
 * (`ForegroundServiceStartNotAllowedException`, API 31+); it "rethrows everything else".
 *
 * The plan lists no JVM test for it. Tested here is the part that needs no Android object:
 * the value arrives, and an ordinary exception (including a plain `IllegalStateException`, the
 * parent of the swallowed one) still comes out. That the refusal itself is swallowed cannot be
 * tested on the JVM (the exception class is an android.jar stub); device step D1c covers it.
 *
 * Step 1c is committed only if D1c reproduces the crash, so `LateDelivery` may never exist.
 * The class is therefore looked up by name and **the tests are skipped while it is absent**
 * (question Q5); they are the only tests of this feature that do not fail before Code exists.
 */
class LateDeliveryTest {

    private fun deliverMethod(): Method {
        val type = try {
            Class.forName("org.schabi.newpipe.enhancements.crashfixes.LateDelivery")
        } catch (e: ClassNotFoundException) {
            null
        }
        assumeTrue("LateDelivery does not exist: step 1c was not committed", type != null)
        // @JvmStatic fun <T> deliver(callback: Consumer<T>, value: T)
        return type!!.getMethod("deliver", Consumer::class.java, Any::class.java)
    }

    /** Calls `LateDelivery.deliver` and rethrows what it threw. */
    private fun <T> deliver(method: Method, callback: Consumer<T>, value: T) {
        try {
            method.invoke(null, callback, value)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun ac1_value_isDeliveredOnce() {
        val method = deliverMethod()
        val delivered = ArrayList<String>()
        deliver(method, Consumer<String> { delivered.add(it) }, "details")
        assertEquals(listOf("details"), delivered)
    }

    @Test
    fun ac7_ordinaryExceptionsOfTheCallback_areRethrown() {
        val method = deliverMethod()
        val thrown = listOf(
            IllegalStateException("Fragment not attached to an activity."),
            NullPointerException(),
            IllegalArgumentException("x"),
            UnsupportedOperationException()
        )
        for (expected in thrown) {
            try {
                deliver(method, Consumer<String> { throw expected }, "details")
                fail("${expected.javaClass.simpleName} was swallowed")
            } catch (actual: RuntimeException) {
                assertSame(expected, actual)
            }
        }
    }
}
