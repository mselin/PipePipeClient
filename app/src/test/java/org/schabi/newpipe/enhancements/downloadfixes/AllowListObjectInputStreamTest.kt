package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipes.LookAlikeNewPipe
import us.shandian.gigantic.LookAlikeGiga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.Serializable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Arrays
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * AC-4 (S4), plan section 4 "Reading: an allow-list is required": `AllowListObjectInputStream`
 * and `PendingStateAllowList`.
 *
 * The saved state of a download is read with Java deserialization. Before the move it lies
 * where other apps can replace it, and the first start after the update reads whatever is
 * there; so the reader resolves only the classes a mission is made of. Two directions are
 * tested: everything a mission holds is read (a list that is too narrow sets real downloads
 * aside at every start, the plan's first risk), and everything else is refused before any of
 * its code runs.
 */
class AllowListObjectInputStreamTest {

    private fun read(bytes: ByteArray, pluginClasses: ((String) -> Class<*>?)? = null): Any? {
        val stream = if (pluginClasses == null) {
            AllowListObjectInputStream(ByteArrayInputStream(bytes)) // the default: no plugin knows anything
        } else {
            AllowListObjectInputStream(ByteArrayInputStream(bytes), pluginClasses)
        }
        return stream.use { it.readObject() }
    }

    private fun roundTrip(value: Any?): Any? = read(serialized(value))

    /** The exception with which [bytes] is refused; fails when it is read. */
    private fun refusal(bytes: ByteArray, pluginClasses: ((String) -> Class<*>?)? = null): Exception {
        try {
            val value = read(bytes, pluginClasses)
            fail("expected a refusal, but read $value")
        } catch (e: IOException) {
            return e
        } catch (e: ClassNotFoundException) {
            return e
        }
        throw AssertionError()
    }

    private fun assertRefusedNaming(className: String, value: Any) {
        val e = refusal(serialized(value))
        assertTrue("refused with an InvalidClassException, was $e", e is InvalidClassException)
        assertTrue("the refusal names $className, was $e", e.toString().contains(className))
    }

    // ---- allowed: what a mission is made of ----

    /** AC-4/AC-5: strings, boxes and primitive arrays. */
    @Test
    fun ac4_allowed_stringsBoxesAndPrimitiveArrays() {
        assertEquals("text", roundTrip("text"))
        assertEquals(listOf<Any>(true, 1.toByte(), 'c', 2.toShort(), 3, 4L, 5f, 6.0), roundTrip(arrayListOf<Any>(true, 1.toByte(), 'c', 2.toShort(), 3, 4L, 5f, 6.0)))
        assertArrayEquals(longArrayOf(0, 1, 2), roundTrip(longArrayOf(0, 1, 2)) as LongArray)
        assertArrayEquals(intArrayOf(-1, 524288), roundTrip(intArrayOf(-1, 524288)) as IntArray)
        assertArrayEquals(booleanArrayOf(true, false), roundTrip(booleanArrayOf(true, false)) as BooleanArray)
        assertArrayEquals(byteArrayOf(1, 2), roundTrip(byteArrayOf(1, 2)) as ByteArray)
        assertArrayEquals(charArrayOf('a'), roundTrip(charArrayOf('a')) as CharArray)
        assertArrayEquals(doubleArrayOf(1.5), roundTrip(doubleArrayOf(1.5)) as DoubleArray, 0.0)
        assertArrayEquals(floatArrayOf(1.5f), roundTrip(floatArrayOf(1.5f)) as FloatArray, 0f)
        assertArrayEquals(shortArrayOf(7), roundTrip(shortArrayOf(7)) as ShortArray)
        assertNull(roundTrip(null))
    }

    /** AC-4/AC-5, plan: "arrays of allowed types": `urls`, `resourceRequestHeaders` (`HashMap[]`), nested arrays. */
    @Test
    fun ac4_allowed_arraysOfAllowedTypes() {
        assertArrayEquals(arrayOf("a", null, "c"), roundTrip(arrayOf("a", null, "c")) as Array<*>)
        val headers = arrayOf<HashMap<String, String>?>(hashMapOf("Referer" to "https://example.invalid/"), null)
        assertArrayEquals(headers, roundTrip(headers) as Array<*>)
        val nested = arrayOf(longArrayOf(1, 2), longArrayOf(3))
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), (roundTrip(nested) as Array<*>).map { (it as LongArray).toList() })
        assertArrayEquals(arrayOf(arrayOf("x")), roundTrip(arrayOf(arrayOf("x"))) as Array<*>)
        assertArrayEquals(arrayOf<Int?>(1, 2), roundTrip(arrayOf<Int?>(1, 2)) as Array<*>)
        assertArrayEquals(arrayOf(AppOwnEnum.SECOND), roundTrip(arrayOf(AppOwnEnum.SECOND)) as Array<*>)
    }

    /** AC-4/AC-5, plan: `java.util` `ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`, `LinkedHashSet`, `Locale`. */
    @Test
    fun ac4_allowed_javaCollectionsAndLocale() {
        val graph = arrayListOf<Any?>(
            hashMapOf("k" to arrayListOf(1, 2)),
            linkedMapOf("a" to 1, "b" to 2),
            hashSetOf("x"),
            linkedSetOf(3, 1, 2),
            Locale.forLanguageTag("pt-BR"),
            null,
        )

        val result = roundTrip(graph)

        assertEquals(graph, result)
        assertEquals("LinkedHashMap keeps its order", listOf("a", "b"), ((result as List<*>)[1] as Map<*, *>).keys.toList())
        assertEquals("LinkedHashSet keeps its order", listOf(3, 1, 2), (result[3] as Set<*>).toList())
    }

    /**
     * AC-4/AC-5, plan: `Arrays$ArrayList`, `CollSer` and the `Collections$` classes of the list:
     * what `Arrays.asList`, `List.of`, `Collections.emptyList/singletonList/unmodifiable...` return.
     */
    @Test
    fun ac4_allowed_wrappersOfTheJavaLibrary() {
        val values = listOf<Any>(
            Arrays.asList("a", "b"),
            Collections.emptyList<String>(),
            Collections.emptyMap<String, String>(),
            Collections.emptySet<String>(),
            Collections.singletonList("one"),
            Collections.singletonMap("k", "v"),
            Collections.singleton("one"),
            Collections.unmodifiableCollection(arrayListOf(1, 2)),
            Collections.unmodifiableList(arrayListOf(1, 2)), // UnmodifiableRandomAccessList
            Collections.unmodifiableList(Arrays.asList(1, 2)),
            Collections.unmodifiableMap(hashMapOf("k" to 1)),
            Collections.unmodifiableSet(hashSetOf("s")),
            java.util.List.of("a", "b", "c"), // java.util.CollSer in the stream
            java.util.Map.of("k", "v"),
            java.util.Set.of("s"),
        )
        for (value in values) {
            val result = roundTrip(value)
            if (value is Collection<*> && value !is List<*> && value !is Set<*>) {
                assertEquals(value.javaClass.name, value.toList(), (result as Collection<*>).toList())
            } else {
                assertEquals(value.javaClass.name, value, result)
            }
        }
    }

    /** AC-4/AC-5, plan: names starting with `kotlin.collections.`, and `kotlin.Pair`, `kotlin.Triple`. */
    @Test
    fun ac4_allowed_kotlinCollectionsPairAndTriple() {
        val values = listOf<Any>(
            emptyList<String>(), // kotlin.collections.EmptyList
            emptyMap<String, String>(),
            emptySet<String>(),
            listOf("one"),
            listOf("a", "b"),
            mapOf("k" to "v"),
            mapOf("a" to 1, "b" to 2),
            setOf(1, 2),
            buildList { add("built") }, // kotlin.collections.builders
            buildMap { put("k", 1) },
            buildSet { add("s") },
            Pair("first", 2),
            Triple("first", 2, listOf(3L)),
        )
        for (value in values) {
            assertEquals(value.javaClass.name, value, roundTrip(value))
        }
    }

    /** AC-4/AC-5, plan: names starting with `org.schabi.newpipe.`: the app's and the extractor's own classes, enums included (`java.lang.Enum`). */
    @Test
    fun ac4_allowed_classesOfTheAppsOwnPackages() {
        val value = AppOwnPayload("outer", listOf(1, 2, 3), nested = AppOwnPayload("inner", emptyList()))

        assertEquals(value, roundTrip(value))
        assertEquals(AppOwnEnum.SECOND, roundTrip(AppOwnEnum.SECOND))
        assertEquals(SavedState(5), roundTrip(SavedState(5)))
        assertArrayEquals(arrayOf(value), roundTrip(arrayOf(value)) as Array<*>)
    }

    /**
     * AC-4/AC-5, plan: "any subclass of `Throwable` and `java.lang.StackTraceElement`
     * (`errObject`)": an exception with message, cause, suppressed exception and stack trace, of
     * the app's and of the Java library's types.
     */
    @Test
    fun ac4_allowed_exceptionWithCauseSuppressedAndStack() {
        val cause = SocketTimeoutException("timeout")
        val error = AppOwnException("download failed", IOException("unexpected end of stream", cause))
        error.addSuppressed(IllegalStateException("while closing"))

        val result = roundTrip(error) as Throwable

        assertTrue(result is AppOwnException)
        assertEquals("download failed", result.message)
        assertTrue(result.cause is IOException)
        assertEquals("unexpected end of stream", result.cause!!.message)
        assertTrue(result.cause!!.cause is SocketTimeoutException)
        assertEquals("while closing", result.suppressed.single().message)
        assertTrue(result.suppressed.single() is IllegalStateException)
        assertEquals("the stack trace comes back", error.stackTrace.toList(), result.stackTrace.toList())
        assertTrue(result.stackTrace.isNotEmpty())
    }

    /** AC-4/AC-5: the exceptions downloads really end with, as `errObject` of a state. */
    @Test
    fun ac4_allowed_theUsualErrorObjects() {
        val errors = listOf<Exception>(
            IOException("DownloadMission.storage == NULL"),
            java.io.FileNotFoundException("gone"),
            java.net.UnknownHostException("example.invalid"),
            java.net.ConnectException("refused"),
            javax.net.ssl.SSLException("handshake"),
            java.net.SocketException("reset"),
            java.net.ProtocolException("unexpected end of stream"),
            IllegalStateException("state"),
            NullPointerException(),
            RuntimeException(IOException("wrapped")),
            java.util.concurrent.TimeoutException("slow"),
            java.io.InterruptedIOException("interrupted"),
            java.nio.channels.ClosedByInterruptException(),
            ArrayIndexOutOfBoundsException(3),
        )
        for (error in errors) {
            val state = SavedState(1, error = error)
            val result = roundTrip(state) as SavedState
            assertEquals(error.javaClass, result.error!!.javaClass)
            assertEquals(error.message, result.error.message)
        }
    }

    /** AC-4/AC-5: a graph that refers to the same object twice and to itself (back references in the stream). */
    @Test
    fun ac5_allowed_sharedAndCyclicReferences() {
        val shared = hashMapOf("k" to "v")
        val list = arrayListOf<Any>(shared, shared)
        list.add(list)

        val result = roundTrip(list) as List<*>

        assertTrue("the shared object is still one object", result[0] === result[1])
        assertTrue("the cycle is still a cycle", result[2] === result)
    }

    // ---- refused ----

    /** AC-4, plan section 5: `java.util.PriorityQueue` (the usual first link of a gadget chain) is refused, and named. */
    @Test
    fun ac4_refused_priorityQueue() {
        assertRefusedNaming("java.util.PriorityQueue", PriorityQueue(listOf(3, 1, 2)))
    }

    /** AC-4, plan section 5: `java.net.URL` (resolves host names when hashed) is refused, and named. */
    @Test
    fun ac4_refused_url() {
        assertRefusedNaming("java.net.URL", URL("http://example.invalid/"))
    }

    /** AC-4, "Everything else is refused": serializable classes of the Java library that are not on the list. */
    @Test
    fun ac4_refused_otherLibraryClasses() {
        assertRefusedNaming("java.io.File", File("/data/data/x"))
        assertRefusedNaming("java.util.Date", Date(0))
        assertRefusedNaming("java.util.TreeMap", TreeMap(mapOf("a" to 1)))
        assertRefusedNaming("java.util.concurrent.ConcurrentHashMap", ConcurrentHashMap(mapOf("a" to 1)))
        assertRefusedNaming("java.util.concurrent.atomic.AtomicLong", AtomicLong(1))
        assertRefusedNaming("java.math.BigInteger", BigInteger.TEN) // a Number, but only java.lang's boxes are listed
        assertRefusedNaming("java.util.Hashtable", java.util.Hashtable(mapOf("a" to 1)))
        assertRefusedNaming("java.util.LinkedList", java.util.LinkedList(listOf(1)))
        assertRefusedNaming("java.lang.StringBuilder", StringBuilder("x"))
        assertRefusedNaming("java.util.concurrent.TimeUnit", java.util.concurrent.TimeUnit.SECONDS) // an enum outside the app
    }

    /** AC-4: a refused class is refused wherever it stands in the graph: in a list, as a map value, in an exception, in an array, in an app class. */
    @Test
    fun ac4_refused_insideAllowedContainers() {
        val url = URL("http://example.invalid/")
        assertRefusedNaming("java.net.URL", arrayListOf<Any>("a", url))
        assertRefusedNaming("java.net.URL", hashMapOf<String, Any>("k" to url))
        assertRefusedNaming("java.net.URL", Collections.singletonList(url))
        assertRefusedNaming("java.net.URL", Pair("first", url))
        assertRefusedNaming("java.net.URL", arrayListOf<Any>(arrayListOf<Any>(hashMapOf("deep" to arrayListOf<Any>(url)))))
        assertRefusedNaming("java.util.PriorityQueue", linkedSetOf<Any>(PriorityQueue(listOf(1))))
    }

    /** AC-4, "arrays of allowed types" only: an array whose element type is not allowed is refused, even when empty. */
    @Test
    fun ac4_refused_arraysOfRefusedTypes() {
        assertTrue(refusal(serialized(arrayOf<URL>())) is InvalidClassException)
        assertTrue(refusal(serialized(arrayOf(Date(0)))) is InvalidClassException)
        assertTrue(refusal(serialized(arrayOf(arrayOf<PriorityQueue<Int>>()))) is InvalidClassException)
        assertTrue(refusal(serialized(arrayListOf<Any>(arrayOf<File>()))) is InvalidClassException)
    }

    /** AC-4: a `Class` object in the stream is resolved through the same list: `URL.class` is refused. */
    @Test
    fun ac4_refused_classObjectOfARefusedClass() {
        assertRefusedNaming("java.net.URL", arrayListOf<Any>(URL::class.java))
    }

    /**
     * AC-4: the prefixes are `us.shandian.giga.` and `org.schabi.newpipe.` *with the dot*. A
     * class of a package that only begins with the same letters is refused, and its
     * `readObject` never runs.
     */
    @Test
    fun ac4_refused_packagesThatOnlyLookLikeTheApps() {
        val before = LookAlikeGiga.readObjectCalls.get()

        assertRefusedNaming("us.shandian.gigantic.LookAlikeGiga", LookAlikeGiga())
        assertRefusedNaming("us.shandian.gigantic.LookAlikeGiga", arrayListOf<Any>(SavedState(1), LookAlikeGiga()))
        assertRefusedNaming("org.schabi.newpipes.LookAlikeNewPipe", LookAlikeNewPipe())

        assertEquals("no code of a refused class ran", before, LookAlikeGiga.readObjectCalls.get())
        // the fixture itself is sound: a plain stream reads it and runs its readObject
        ObjectInputStream(ByteArrayInputStream(serialized(LookAlikeGiga()))).use { it.readObject() }
        assertEquals(before + 1, LookAlikeGiga.readObjectCalls.get())
    }

    /** A handler of the app's own package (allowed by prefix), so that only the proxy rule can refuse. */
    private class AppOwnHandler : InvocationHandler, Serializable {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? = null

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * AC-4, plan: `resolveProxyClass` "always refused": a dynamic proxy, whatever its
     * interfaces and although its handler is a class of the app.
     */
    @Test
    fun ac4_refused_dynamicProxy() {
        val loader = AllowListObjectInputStreamTest::class.java.classLoader
        for (interfaces in listOf(arrayOf<Class<*>>(Runnable::class.java), arrayOf(Comparable::class.java, Serializable::class.java), arrayOf(Map::class.java))) {
            val proxy = Proxy.newProxyInstance(loader, interfaces, AppOwnHandler())
            assertNotNull("a plain stream reads the proxy", ObjectInputStream(ByteArrayInputStream(serialized(proxy))).use { it.readObject() })

            val e = refusal(serialized(proxy))
            assertTrue("proxy of ${interfaces.map { it.name }} is refused with an InvalidClassException, was $e", e is InvalidClassException)
            assertTrue(refusal(serialized(arrayListOf(proxy))) is InvalidClassException)
        }
    }

    /** AC-4: a class that no class loader knows is refused; nothing is guessed. */
    @Test
    fun ac4_refused_unknownClass() {
        val e = refusal(PluginPayload.streamWithPluginOnlyName("x"))
        assertTrue("the refusal names the class, was $e", e.toString().contains(PluginPayload.PLUGIN_ONLY_NAME))
    }

    // ---- plugins ----

    /**
     * AC-4/AC-5, plan section 5 "plugin resolver honoured": a class that only a loaded plugin
     * defines (`MissionRecoveryInfo.deliveryMethodInfo` may be one) is resolved through the
     * resolver and read.
     */
    @Test
    fun ac4_plugin_aClassOnlyThePluginKnows_isRead() {
        val asked = ArrayList<String>()
        val resolver: (String) -> Class<*>? = { name ->
            asked.add(name)
            if (name == PluginPayload.PLUGIN_ONLY_NAME) PluginPayload::class.java else null
        }

        val result = read(PluginPayload.streamWithPluginOnlyName("delivery info"), resolver)

        assertEquals(PluginPayload("delivery info"), result)
        assertEquals("the resolver was asked for the name in the stream", listOf(PluginPayload.PLUGIN_ONLY_NAME), asked.distinct())
    }

    /** AC-4: the same class inside a mission-like graph, next to app classes. */
    @Test
    fun ac4_plugin_insideAGraph() {
        val graph = withNameReplaced(
            serialized(arrayListOf<Any>(SavedState(2), PluginPayload("in a list"), "end")),
            PluginPayload::class.java.name,
            PluginPayload.PLUGIN_ONLY_NAME
        )

        val result = read(graph) { name -> if (name == PluginPayload.PLUGIN_ONLY_NAME) PluginPayload::class.java else null }

        assertEquals(arrayListOf<Any>(SavedState(2), PluginPayload("in a list"), "end"), result)
    }

    /** AC-4: a resolver that knows nothing, and the default resolver, refuse the unknown class. */
    @Test
    fun ac4_plugin_resolverThatDoesNotKnowTheClass_refuses() {
        val stream = PluginPayload.streamWithPluginOnlyName("x")

        assertNotNull(refusal(stream) { null })
        assertNotNull(refusal(stream))
        assertNotNull(refusal(stream) { name -> if (name == "com.example.other.Thing") PluginPayload::class.java else null })
    }

    /**
     * AC-4, plan: "a class that *only* a loaded plugin defines". The resolver cannot widen the
     * list for classes the app itself can load: `PriorityQueue` stays refused although a
     * resolver would hand it out.
     */
    @Test
    fun ac4_plugin_resolverCannotAllowAClassTheAppKnows() {
        val handsOutAnything: (String) -> Class<*>? = { name -> runCatching { Class.forName(name) }.getOrNull() }

        val e = refusal(serialized(PriorityQueue(listOf(1))), handsOutAnything)
        assertTrue("was $e", e is InvalidClassException)
        assertTrue(refusal(serialized(URL("http://example.invalid/")), handsOutAnything) is InvalidClassException)
        assertTrue(refusal(serialized(LookAlikeGiga()), handsOutAnything) is InvalidClassException)
        assertTrue(refusal(serialized(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Runnable::class.java), AppOwnHandler())), handsOutAnything) is InvalidClassException)
    }

    // ---- PendingStateAllowList.isAllowed, by the plan's list ----

    private fun named(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    private fun assertAllowed(cls: Class<*>) = assertTrue("${cls.name} must be allowed", PendingStateAllowList.isAllowed(cls, false))

    private fun assertNotAllowed(cls: Class<*>) = assertFalse("${cls.name} must not be allowed", PendingStateAllowList.isAllowed(cls, false))

    /** AC-4, plan: "primitives and arrays of allowed types". */
    @Test
    fun ac4_list_primitivesAndArrays() {
        for (primitive in listOf(
            java.lang.Boolean.TYPE, java.lang.Byte.TYPE, java.lang.Character.TYPE, java.lang.Short.TYPE,
            java.lang.Integer.TYPE, java.lang.Long.TYPE, java.lang.Float.TYPE, java.lang.Double.TYPE
        )) {
            assertAllowed(primitive)
        }
        assertAllowed(LongArray::class.java)
        assertAllowed(Array<LongArray>::class.java)
        assertAllowed(Array<String>::class.java)
        assertAllowed(Array<Array<String>>::class.java)
        assertAllowed(arrayOf<HashMap<String, String>>().javaClass)
        assertAllowed(Array<StackTraceElement>::class.java)
        assertAllowed(Array<AppOwnPayload>::class.java)
        assertNotAllowed(Array<URL>::class.java)
        assertNotAllowed(Array<Array<URL>>::class.java)
        assertNotAllowed(arrayOf<PriorityQueue<Int>>().javaClass)
        assertNotAllowed(Array<LookAlikeGiga>::class.java)
    }

    /** AC-4, plan: names starting with `us.shandian.giga.` or `org.schabi.newpipe.`; look-alikes are not. */
    @Test
    fun ac4_list_theAppsPackagesByPrefix() {
        assertAllowed(named("us.shandian.giga.get.DownloadMission"))
        assertAllowed(named("us.shandian.giga.get.MissionRecoveryInfo"))
        assertAllowed(named("us.shandian.giga.hls.state.HlsDownloadCheckpoint"))
        assertAllowed(named("us.shandian.giga.get.SabrResourceCheckpoint"))
        assertAllowed(named("us.shandian.giga.postprocessing.Postprocessing"))
        assertAllowed(named("org.schabi.newpipe.streams.io.StoredFileHelper"))
        assertAllowed(named("org.schabi.newpipe.extractor.MediaFormat"))
        assertAllowed(named("org.schabi.newpipe.extractor.services.youtube.ItagItem"))
        assertAllowed(named("org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo"))
        assertAllowed(AppOwnPayload::class.java)
        assertAllowed(AppOwnEnum::class.java)
        assertNotAllowed(LookAlikeGiga::class.java)
        assertNotAllowed(LookAlikeNewPipe::class.java)
    }

    /** AC-4, plan: "any subclass of `Throwable` and `java.lang.StackTraceElement`". */
    @Test
    fun ac4_list_throwablesAndStackTraceElement() {
        assertAllowed(Throwable::class.java)
        assertAllowed(Exception::class.java)
        assertAllowed(IOException::class.java)
        assertAllowed(SocketTimeoutException::class.java)
        assertAllowed(javax.net.ssl.SSLHandshakeException::class.java)
        assertAllowed(OutOfMemoryError::class.java)
        assertAllowed(AppOwnException::class.java)
        assertAllowed(StackTraceElement::class.java)
    }

    /** AC-4, plan: `java.lang` `String`, `Number`, the eight boxes, `Enum`. */
    @Test
    fun ac4_list_javaLang() {
        for (name in listOf("String", "Number", "Enum", "Boolean", "Byte", "Character", "Short", "Integer", "Long", "Float", "Double")) {
            assertAllowed(named("java.lang.$name"))
        }
        assertNotAllowed(named("java.lang.StringBuilder"))
        assertNotAllowed(named("java.lang.Thread"))
        assertNotAllowed(named("java.lang.Class"))
        assertNotAllowed(named("java.lang.Runtime"))
        assertNotAllowed(named("java.lang.ProcessBuilder"))
        assertNotAllowed(named("java.math.BigInteger"))
        assertNotAllowed(named("java.math.BigDecimal"))
        assertNotAllowed(named("java.util.concurrent.atomic.AtomicInteger"))
    }

    /** AC-4, plan: the `java.util` classes of the list, one by one. */
    @Test
    fun ac4_list_javaUtil() {
        val listed = listOf("ArrayList", "HashMap", "LinkedHashMap", "HashSet", "LinkedHashSet", "Locale", "Arrays\$ArrayList", "CollSer") +
            listOf(
                "EmptyList", "EmptyMap", "EmptySet", "SingletonList", "SingletonMap", "SingletonSet", "UnmodifiableCollection",
                "UnmodifiableList", "UnmodifiableRandomAccessList", "UnmodifiableMap", "UnmodifiableSet"
            ).map { "Collections\$$it" }
        for (name in listed) assertAllowed(named("java.util.$name"))

        val notListed = listOf(
            "PriorityQueue", "TreeMap", "TreeSet", "Hashtable", "LinkedList", "Vector", "Date", "IdentityHashMap", "WeakHashMap",
            "EnumMap", "BitSet", "Properties", "Collections\$SynchronizedList", "Collections\$CheckedList", "Collections\$SetFromMap",
            "concurrent.ConcurrentHashMap", "concurrent.CopyOnWriteArrayList"
        )
        for (name in notListed) assertNotAllowed(named("java.util.$name"))
    }

    /** AC-4, plan: names starting with `kotlin.collections.`, and `kotlin.Pair`, `kotlin.Triple`. */
    @Test
    fun ac4_list_kotlin() {
        assertAllowed(Pair::class.java)
        assertAllowed(Triple::class.java)
        assertAllowed(named("kotlin.collections.EmptyList"))
        assertAllowed(named("kotlin.collections.EmptyMap"))
        assertAllowed(named("kotlin.collections.ArrayDeque"))
        assertAllowed(named("kotlin.collections.builders.ListBuilder"))
        assertAllowed(named("kotlin.collections.builders.SerializedCollection"))
        assertNotAllowed(named("kotlin.Unit"))
        assertNotAllowed(named("kotlin.text.Regex"))
        assertNotAllowed(named("kotlin.jvm.internal.Ref\$ObjectRef"))
    }

    /** AC-4: other things reachable from a download are not state: URLs, files, sockets' addresses, reflection. */
    @Test
    fun ac4_list_everythingElseIsRefused() {
        for (name in listOf(
            "java.net.URL", "java.net.URI", "java.net.InetAddress", "java.net.InetSocketAddress", "java.io.File",
            "java.lang.reflect.Proxy", "java.security.SignedObject", "java.security.KeyPair", "javax.crypto.SealedObject",
            "java.text.SimpleDateFormat", "java.time.Instant", "java.util.regex.Pattern"
        )) {
            assertNotAllowed(named(name))
        }
    }

    /**
     * AC-4, plan: "fromPlugin: only a plugin loader knew it" and "a class that only a loaded
     * plugin defines" is allowed. The same class is not allowed when the app's own loader knew it.
     */
    @Test
    fun ac4_list_aPluginOnlyClassIsAllowed() {
        assertTrue(PendingStateAllowList.isAllowed(PluginPayload::class.java, true))
        assertTrue(PendingStateAllowList.isAllowed(LookAlikeGiga::class.java, true))
        assertFalse(PendingStateAllowList.isAllowed(LookAlikeGiga::class.java, false))
    }
}
