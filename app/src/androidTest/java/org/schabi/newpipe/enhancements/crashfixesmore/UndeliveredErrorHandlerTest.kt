package org.schabi.newpipe.enhancements.crashfixesmore

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import java.io.IOException

/**
 * AC-8 (plan C8, instrumented test I4): the app's real RxJava error handler, as the debug app
 * installed it at start, called through `RxJavaPlugins.onError` from the test's own thread.
 * Run by hand on a device or emulator, not in CI:
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.enhancements.crashfixesmore.UndeliveredErrorHandlerTest`
 *
 * **How a crash is seen.** The handler closes the app by handing the error to
 * `Thread.currentThread().getUncaughtExceptionHandler()`. Each test puts a recorder there for
 * its own thread and restores the old one. "Closes the app" is one entry in the recorder,
 * "does not" is none.
 *
 * The debug setting "report out-of-lifecycle errors" (`allow_disposed_exceptions_key`) is set
 * by each test and restored afterwards; it is what `DebugApp.isDisposedRxExceptionsReported()`
 * reads. Off is what a release build always has.
 *
 * **A pass proves**: in the installed debug app, with the real `App`, `DebugApp`, preference
 * and handler, an error RxJava marks undeliverable (not caused by the network) is not handed
 * to the uncaught-exception handler when the setting is off, and is when it is on; a receiver
 * without error handler and a bare `NullPointerException` (accepted limit 1 of AC-8) are
 * handed over as today.
 *
 * **It does not prove**: that the log entry is written (the test does not read logcat; the JVM
 * test `AppErrorHandlerDifferentialTest` checks the `Log.e` call, device step D8 the log);
 * that the process survives a real abandoned load on a slow network (D8); anything about a
 * release build beyond "the setting is off"; nor which page loads produce such errors.
 *
 * Compiled, not run, by the layer that wrote it.
 */
@RunWith(AndroidJUnit4::class)
class UndeliveredErrorHandlerTest {

    private lateinit var preferences: SharedPreferences
    private lateinit var settingKey: String
    private var settingBefore: Boolean? = null

    private val recorded = ArrayList<Throwable>()
    private var handlerBefore: Thread.UncaughtExceptionHandler? = null

    @Before
    fun recordInsteadOfCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "precondition: the app under test is the PipePipe App (its onCreate installs the error handler)",
            context.applicationContext is App
        )
        assertNotNull("precondition: an RxJava error handler is installed", RxJavaPlugins.getErrorHandler())
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        settingKey = context.getString(R.string.allow_disposed_exceptions_key)
        settingBefore = if (preferences.contains(settingKey)) preferences.getBoolean(settingKey, false) else null

        val thread = Thread.currentThread()
        handlerBefore = thread.uncaughtExceptionHandler.takeIf { it !== thread.threadGroup }
        thread.setUncaughtExceptionHandler { _, throwable -> recorded.add(throwable) }
    }

    @After
    fun restore() {
        Thread.currentThread().uncaughtExceptionHandler = handlerBefore
        val editor = preferences.edit()
        val before = settingBefore
        if (before == null) editor.remove(settingKey) else editor.putBoolean(settingKey, before)
        editor.commit()
    }

    private fun debugSetting(on: Boolean) {
        assertTrue(preferences.edit().putBoolean(settingKey, on).commit())
    }

    /** AC-8: an undeliverable error with a bug type as its cause is not rethrown (today: one entry, the app closes). */
    @Test
    fun ac8_undeliverable_isNotRethrown() {
        debugSetting(false)
        RxJavaPlugins.onError(Exception("crash-fixes-more AC-8", NullPointerException()))
        assertEquals(
            "AC-8 (plan C8): an undeliverable error must be logged and not handed to the uncaught-exception handler",
            emptyList<Throwable>(),
            recorded
        )
    }

    /** AC-8 with real RxJava on the device: the error of a load that was disposed (its page was left) is not rethrown. */
    @Test
    fun ac8_abandonedLoad_isNotRethrown() {
        debugSetting(false)
        var saved: SingleEmitter<Int>? = null
        Single.create<Int> { saved = it }.subscribe({ _ -> }, { _ -> }).dispose()
        saved!!.onError(Exception("Could not parse", NumberFormatException()))
        assertEquals(
            "AC-8 (plan C8): the error of an abandoned load must not be handed to the uncaught-exception handler",
            emptyList<Throwable>(),
            recorded
        )
    }

    /** AC-8: an undeliverable error caused by the network stays ignored, as today. Passes before the fix. */
    @Test
    fun ac8_undeliverableNetworkError_staysIgnored() {
        debugSetting(false)
        RxJavaPlugins.onError(IOException("crash-fixes-more AC-8"))
        assertEquals("AC-8: a network error is ignored as today", emptyList<Throwable>(), recorded)
    }

    /** AC-8 / AC-6: a receiver without error handler closes the app as today. Passes before the fix. */
    @Test
    fun ac8_receiverWithoutHandler_closesAsToday() {
        debugSetting(false)
        val error = OnErrorNotImplementedException(RuntimeException("crash-fixes-more AC-8"))
        RxJavaPlugins.onError(error)
        assertEquals("AC-8 / AC-6: handed to the uncaught-exception handler once", 1, recorded.size)
        assertSame(error, recorded[0])
    }

    /**
     * AC-8, accepted limit 1: a bare `NullPointerException` is not wrapped by RxJava and closes
     * the app as today, whether or not anybody waits. Intended. Passes before the fix.
     */
    @Test
    fun ac8_acceptedLimit1_bareNullPointerException_closesAsToday() {
        debugSetting(false)
        val error = NullPointerException("crash-fixes-more AC-8")
        RxJavaPlugins.onError(error)
        assertEquals("AC-8, accepted limit 1: handed to the uncaught-exception handler once", 1, recorded.size)
        assertSame(error, recorded[0])
    }

    /**
     * AC-8 "with the debug setting that reports such errors switched on, today's behaviour is
     * kept": the same undeliverable error closes the app. Passes before the fix.
     */
    @Test
    fun ac8_debugSettingOn_asToday() {
        debugSetting(true)
        RxJavaPlugins.onError(Exception("crash-fixes-more AC-8", NullPointerException()))
        assertEquals("AC-8 / AC-6: with the debug setting on, handed to the uncaught-exception handler once", 1, recorded.size)
        assertTrue(
            "today's handler hands over the member with the bug type in its chain: ${recorded[0]}",
            recorded[0].cause is NullPointerException
        )
    }
}
