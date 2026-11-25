package org.schabi.newpipe.enhancements.crashfixesmore

import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.functions.Consumer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * AC-8 and AC-6, plan C8 ("Tests to write", the optional differential test): the **real,
 * compiled error handler** of `App` (the anonymous `Consumer` of
 * `App.configureRxJavaErrorHandler`), run on the JVM over the matrix of plan T2.
 *
 * It closes the two gaps the other AC-8 tests leave:
 * - `UndeliveredErrorsTest` compares `decide` with a *copy* of today's handler. Here the copy
 *   (`TodaysHandler`) is compared with the handler itself: what it hands to the thread's
 *   uncaught-exception handler, and which instance.
 * - The wiring test reads the four new lines of `App.java` as text. Here they run: for an
 *   error that has to be logged, the handler must hand nothing to the uncaught-exception
 *   handler and write exactly one `Log.e(LOG_TAG, LOG_MESSAGE, <the error as handed over>)`
 *   after its own first log line.
 *
 * **What is real and what is not.**
 * - Real: the bytes of `App$N.class` and `App.class` as compiled for the app, `ExceptionUtils`,
 *   RxJava's exception classes and, once it exists, `UndeliveredErrors`.
 * - Not real: `android.util.Log` (in unit tests every method of it throws "not mocked"), which
 *   is a recording class compiled at run time with the JDK's compiler, as in
 *   [TabAdapterFinishUpdateTest]; `App` and its handler are loaded a second time by a class
 *   loader of this test that sees that `Log`.
 * - The `App` object: no constructor runs and nothing of Android is touched. The handler only
 *   asks it for `isDisposedRxExceptionsReported()`, the debug setting. `App` answers false;
 *   for "on" a subclass that answers true is compiled at run time (in the app it is `DebugApp`
 *   reading the preference; that it does is the instrumented test I4).
 * - The uncaught-exception handler is a recorder set on the test's own thread, so "closes the
 *   app" is an entry in a list.
 *
 * So this shows what the handler **decides**, not that RxJava calls it (that is
 * [UndeliveredErrorsRxTest]) and not that the process survives (I4, device step D8).
 *
 * Before the Code layer adds the four lines: the `asToday` tests pass (they describe upstream
 * and keep passing afterwards: that is AC-6), the `isLogged` test fails.
 */
class AppErrorHandlerDifferentialTest {

    private companion object {
        const val APP = "org.schabi.newpipe.App"
        const val APP_WITH_SETTING_ON = "org.schabi.newpipe.CrashFixesMoreAppWithDebugSettingOn"
        const val LOG_CLASS = "android.util.Log"

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

        /** The debug setting switched on, as `DebugApp` does it, without Android. */
        val APP_WITH_SETTING_ON_SOURCE = """
            package org.schabi.newpipe;
            public class CrashFixesMoreAppWithDebugSettingOn extends App {
                @Override
                protected boolean isDisposedRxExceptionsReported() { return true; }
            }
        """.trimIndent()

        /** Where the class files of [type] and of its superclasses come from, for the compiler's classpath. */
        fun classpathOf(type: Class<*>): String {
            val entries = LinkedHashSet<String>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.protectionDomain?.codeSource?.location?.let { entries.add(File(it.toURI()).path) }
                current = current.superclass
            }
            return entries.joinToString(File.pathSeparator)
        }

        /** Compiles [sources] (path to text) with the JDK's compiler; returns class name to bytes. */
        fun compile(sources: Map<String, String>, classpath: String?): Map<String, ByteArray> {
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
            val dir = Files.createTempDirectory("crashfixesmore-handler").toFile()
            try {
                val out = File(dir, "out").apply { mkdirs() }
                val files = sources.map { (path, text) ->
                    File(dir, "src/$path").apply {
                        parentFile!!.mkdirs()
                        writeText(text)
                    }.path
                }
                val arguments = ArrayList<String>(listOf("-d", out.path, "-proc:none", "-nowarn"))
                if (classpath != null) {
                    arguments.addAll(listOf("-cp", classpath))
                }
                arguments.addAll(files)
                val messages = ByteArrayOutputStream()
                val exitCode = run.invoke(compiler, null, messages, messages, arguments.toTypedArray())
                if (exitCode != 0) {
                    throw AssertionError("could not compile ${sources.keys} for this test: $messages")
                }
                return out.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.associate {
                    it.relativeTo(out).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') to it.readBytes()
                }
            } finally {
                dir.deleteRecursively()
            }
        }

        val compiled: Map<String, ByteArray> by lazy {
            val app = Class.forName(APP, false, AppErrorHandlerDifferentialTest::class.java.classLoader)
            compile(mapOf("android/util/Log.java" to LOG_SOURCE), null) +
                compile(
                    mapOf("org/schabi/newpipe/CrashFixesMoreAppWithDebugSettingOn.java" to APP_WITH_SETTING_ON_SOURCE),
                    classpathOf(app)
                )
        }
    }

    /**
     * Defines `App`, its nested classes, the subclass with the setting on and the recording
     * `Log` itself; everything else (RxJava, `ExceptionUtils`, `UndeliveredErrors`, Android's
     * stubs) comes from the parent.
     */
    private class RecordingLogLoader(parent: ClassLoader) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != LOG_CLASS && name != APP && !name.startsWith("$APP$") && name != APP_WITH_SETTING_ON) {
                return super.loadClass(name, resolve)
            }
            synchronized(this) {
                findLoadedClass(name)?.let { return it }
                val bytes = compiled[name]
                    ?: (parent.getResourceAsStream(name.replace('.', '/') + ".class") ?: throw ClassNotFoundException(name))
                        .use { it.readBytes() }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
    }

    /** What one call of the handler did. */
    private class Observed(val handedToUncaughtHandler: List<Throwable?>, val logged: List<Array<Any?>>)

    private lateinit var loader: ClassLoader
    private lateinit var handlerClass: Class<*>
    private lateinit var logErrors: MutableList<Array<Any?>>
    private val uncaught = ArrayList<Throwable?>()
    private var handlerBefore: Thread.UncaughtExceptionHandler? = null
    private val handlers = HashMap<Boolean, Consumer<Throwable>>()

    @Before
    fun loadTheCompiledHandler() {
        loader = RecordingLogLoader(javaClass.classLoader!!)
        val app = loader.loadClass(APP)
        assertSame("App must be the copy that sees the recording Log", loader, app.classLoader)
        // the anonymous classes of App are numbered; the handler is the one that is an RxJava Consumer
        val candidates = (1..20).mapNotNull {
            try {
                loader.loadClass("$APP$$it")
            } catch (e: ClassNotFoundException) {
                null
            }
        }.filter { Consumer::class.java.isAssignableFrom(it) && it.enclosingMethod?.name == "configureRxJavaErrorHandler" }
        assertEquals(
            "App.configureRxJavaErrorHandler must hold exactly one anonymous Consumer (the error handler); found $candidates",
            1,
            candidates.size
        )
        handlerClass = candidates[0]
        @Suppress("UNCHECKED_CAST")
        logErrors = loader.loadClass(LOG_CLASS).getField("ERRORS").get(null) as MutableList<Array<Any?>>

        val thread = Thread.currentThread()
        handlerBefore = thread.uncaughtExceptionHandler.takeIf { it !== thread.threadGroup }
        thread.setUncaughtExceptionHandler { _, throwable -> uncaught.add(throwable) }
    }

    @After
    fun restoreUncaughtExceptionHandler() {
        Thread.currentThread().uncaughtExceptionHandler = handlerBefore
    }

    /** An object of [type] for which no constructor ran (the constructors of Android's classes cannot run here). */
    private fun instanceWithoutConstructor(type: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)!!
    }

    /** The real handler, on an `App` whose debug setting is [reportDisposed]. */
    private fun handler(reportDisposed: Boolean): Consumer<Throwable> = handlers.getOrPut(reportDisposed) {
        val appClass = loader.loadClass(if (reportDisposed) APP_WITH_SETTING_ON else APP)
        val app = instanceWithoutConstructor(appClass)
        val setting = loader.loadClass(APP).getDeclaredMethod("isDisposedRxExceptionsReported").apply { isAccessible = true }
        assertEquals("the debug setting of the App used by this test", reportDisposed, setting.invoke(app))
        val constructor = handlerClass.declaredConstructors.single().apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        constructor.newInstance(app) as Consumer<Throwable>
    }

    private fun run(throwable: Throwable, reportDisposed: Boolean): Observed {
        uncaught.clear()
        logErrors.clear()
        handler(reportDisposed).accept(throwable)
        return Observed(ArrayList(uncaught), ArrayList(logErrors))
    }

    private class Case(val shape: ErrorShape, val reportDisposed: Boolean) {
        val throwable: Throwable = shape.make()
        val today: TodaysHandler.Outcome = TodaysHandler.handle(throwable, reportDisposed)
        val expected: String = TodaysHandler.expectedAfterTheChange(throwable, reportDisposed)
        val label: String get() = "${shape.name}, setting ${onOff(reportDisposed)}"
    }

    private fun cases(): List<Case> = ErrorShapes.all.flatMap { listOf(Case(it, false), Case(it, true)) }

    private fun failures(what: String, lines: List<String>, of: Int) {
        if (lines.isNotEmpty()) {
            throw AssertionError(
                "$what: ${lines.size} of $of cases differ. First ${minOf(lines.size, 25)}:\n" +
                    lines.take(25).joinToString("\n")
            )
        }
    }

    /** The handler's own first line (`App.java`, "RxJavaPlugins.ErrorHandler called with .."), which stays. */
    private fun isUpstreamLine(entry: Array<Any?>) =
        entry[1].toString().startsWith("RxJavaPlugins.ErrorHandler called with") && entry[2] == null

    /** Null if the real handler did for [case] what today's handler does; else what differs. */
    private fun differenceFromToday(case: Case): String? {
        val observed = run(case.throwable, case.reportDisposed)
        val expectedHandOver: List<Throwable?> = when (case.today.kind) {
            TodaysHandler.Kind.CRASH, TodaysHandler.Kind.REPORT -> listOf(case.today.handedOver)
            else -> emptyList()
        }
        val sameHandOver = observed.handedToUncaughtHandler.size == expectedHandOver.size &&
            observed.handedToUncaughtHandler.zip(expectedHandOver).all { (have, want) -> have === want }
        return when {
            !sameHandOver ->
                "${case.label}: today ${case.today.kind} hands ${expectedHandOver.map { describe(it) }} to the " +
                    "uncaught-exception handler; the real handler handed over ${observed.handedToUncaughtHandler.map { describe(it) }}"
            observed.logged.size != 1 || !isUpstreamLine(observed.logged[0]) ->
                "${case.label}: today only the handler's own first line is logged; logged: " +
                    observed.logged.map { "${it[0]} / ${it[1]} / ${describe(it[2] as Throwable?)}" }
            else -> null
        }
    }

    /**
     * AC-6 for AC-8, and the proof that `TodaysHandler` (the copy the other tests compare
     * with) is the real handler: for every error that is **not** an `UndeliverableException`,
     * debug setting off and on, the real handler hands exactly the same instance to the
     * uncaught-exception handler as the copy says (the deciding member for CRASH, the error
     * itself for REPORT, nothing otherwise) and logs nothing but its own first line. Passes
     * before and after the Code layer.
     */
    @Test
    fun ac6_ac8_realHandler_notUndeliverable_asToday() {
        val plain = cases().filter { it.throwable !is UndeliverableException }
        assertTrue(plain.size > 1000)
        failures(
            "AC-6 / AC-8: the real handler of App for errors that are not undeliverable",
            plain.mapNotNull { differenceFromToday(it) },
            plain.size
        )
    }

    /**
     * AC-8 "with the debug setting switched on, today's behaviour is kept": for every
     * `UndeliverableException` with the setting on, the real handler does what today's does
     * (CRASH with the member, REPORT with the unwrapped error, nothing for the network).
     * Passes before and after the Code layer.
     */
    @Test
    fun ac8_realHandler_undeliverable_debugSettingOn_asToday() {
        val wrappedOn = cases().filter { it.throwable is UndeliverableException && it.reportDisposed }
        assertTrue(wrappedOn.size > 500)
        failures(
            "AC-8 / AC-6: the real handler of App for undeliverable errors with the debug setting on",
            wrappedOn.mapNotNull { differenceFromToday(it) },
            wrappedOn.size
        )
    }

    /**
     * AC-8 "caused by the network or an interruption, which stay ignored as today": for every
     * `UndeliverableException` with the setting off that today's handler ignores for such a
     * cause, the real handler still does nothing: no hand-over, no log entry of this package.
     * Passes before and after the Code layer.
     */
    @Test
    fun ac8_realHandler_undeliverableNetworkOrInterruption_settingOff_staysIgnoredWithoutLog() {
        val ignored = cases().filter {
            it.throwable is UndeliverableException && !it.reportDisposed && it.today.kind == TodaysHandler.Kind.IGNORED_FOR_IO
        }
        assertTrue(ignored.size > 100)
        failures(
            "AC-8: the real handler of App for undeliverable errors caused by the network or an interruption",
            ignored.mapNotNull { differenceFromToday(it) },
            ignored.size
        )
    }

    /**
     * AC-8, the change, on the real handler: for every `UndeliverableException` with the debug
     * setting off that is not caused by the network or an interruption (today: closes the app,
     * or is dropped without a trace), the handler
     * - hands **nothing** to the uncaught-exception handler (the app stays open), and
     * - after its own first line writes exactly one
     *   `Log.e(UndeliveredErrors.LOG_TAG, UndeliveredErrors.LOG_MESSAGE, throwable)` with the
     *   error as RxJava handed it over (the wrapper, so the entry carries the cause's stack
     *   trace).
     * Fails until the Code layer adds `UndeliveredErrors` and the four lines of `App.java`.
     */
    @Test
    fun ac8_realHandler_undeliverable_settingOff_isLoggedAndNotHandedOn() {
        val toLog = cases().filter { it.expected == LOG }
        assertTrue("the matrix must hold some hundred cases to log, it has ${toLog.size}", toLog.size > 200)
        assertTrue("only undeliverable errors with the setting off are to be logged", toLog.all { it.throwable is UndeliverableException && !it.reportDisposed })
        assertTrue("the cases must include errors that close the app today", toLog.any { it.today.kind == TodaysHandler.Kind.CRASH })
        assertTrue("the cases must include errors that are dropped silently today", toLog.any { it.today.kind == TodaysHandler.Kind.DROPPED_SILENTLY })
        val tag = Decision.logTag
        val message = Decision.logMessage
        val wrong = toLog.mapNotNull { case ->
            val observed = run(case.throwable, false)
            val own = observed.logged.filterNot { isUpstreamLine(it) }
            when {
                observed.handedToUncaughtHandler.isNotEmpty() ->
                    "${case.label}: must be logged and not rethrown (today ${case.today.kind}); the real handler handed " +
                        "${observed.handedToUncaughtHandler.map { describe(it) }} to the uncaught-exception handler"
                own.size != 1 || own[0][0] != tag || own[0][1] != message || own[0][2] !== case.throwable ->
                    "${case.label}: must write exactly one Log.e(\"$tag\", \"$message\", <the error as handed over>); " +
                        "logged besides the handler's first line: " +
                        own.map { "${it[0]} / ${it[1]} / ${describe(it[2] as Throwable?)}" }
                observed.logged.size != 2 || !isUpstreamLine(observed.logged[0]) ->
                    "${case.label}: the handler's own first log line must still precede the new entry; logged: " +
                        observed.logged.map { "${it[0]} / ${it[1]}" }
                else -> null
            }
        }
        failures("AC-8, plan C8: the real handler of App for undeliverable errors with the debug setting off", wrong, toLog.size)
    }

    /**
     * AC-8, both accepted limits and the criterion, each once by name on the real handler
     * (debug setting off), so that a reader finds them without the matrix:
     * the extractor's parsing error from an abandoned load is logged; a bare
     * `NullPointerException` still closes the app (accepted limit 1); a receiver without error
     * handler still closes the app.
     */
    @Test
    fun ac8_realHandler_namedCases_settingOff() {
        val parse = UndeliverableException(Exception("Could not parse", NumberFormatException()))
        val parsed = run(parse, false)
        assertEquals(
            "AC-8: an undeliverable parsing error must not be handed to the uncaught-exception handler",
            emptyList<Throwable?>(),
            parsed.handedToUncaughtHandler
        )
        assertTrue(
            "AC-8: an undeliverable parsing error must be written to the log with tag UndeliveredErrors and the error itself; " +
                "logged: ${parsed.logged.map { "${it[0]} / ${it[1]}" }}",
            parsed.logged.any { it[0] == "UndeliveredErrors" && it[2] === parse }
        )

        val bare = NullPointerException("accepted limit 1")
        assertEquals(
            "AC-8, accepted limit 1: a bare NullPointerException closes the app as today",
            listOf<Throwable?>(bare),
            run(bare, false).handedToUncaughtHandler
        )
        val noHandler = io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException(RuntimeException("x"))
        assertEquals(
            "AC-8 / AC-6: a receiver without error handler closes the app as today",
            listOf<Throwable?>(noHandler),
            run(noHandler, false).handedToUncaughtHandler
        )
    }
}
