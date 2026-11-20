package org.schabi.newpipe.enhancements.downloadfixes

import java.io.InputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass

/**
 * Reads the saved state of a pending download and resolves only the classes such a state is
 * made of ([PendingStateAllowList]). A class that is not on the list is refused before any of
 * its code runs; the refusal names the class.
 *
 * @param pluginClasses resolves a class that only a loaded plugin defines; asked only for a
 *   name the app's own class loader does not know, so it cannot widen the list for classes
 *   of the app or the platform.
 */
class AllowListObjectInputStream(
    input: InputStream,
    private val pluginClasses: (String) -> Class<*>? = { null },
) : ObjectInputStream(input) {

    @Throws(InvalidClassException::class)
    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
        val name = desc.name
        var fromPlugin = false
        val cls = PRIMITIVES[name] ?: try {
            // initialize = false: no static initializer runs before the class is accepted
            Class.forName(name, false, AllowListObjectInputStream::class.java.classLoader)
        } catch (e: ClassNotFoundException) {
            fromPlugin = true
            pluginClasses(name) ?: throw refusal(name, "class not found", e)
        } catch (e: LinkageError) {
            throw refusal(name, "class cannot be loaded", e)
        }
        if (!PendingStateAllowList.isAllowed(cls, fromPlugin)) {
            throw refusal(name, "class is not allowed in the saved state of a download", null)
        }
        return cls
    }

    /** A dynamic proxy is never part of a saved download. */
    @Throws(InvalidClassException::class)
    override fun resolveProxyClass(interfaces: Array<String>): Class<*> {
        throw refusal("proxy of " + interfaces.joinToString(", "), "dynamic proxies are not allowed in the saved state of a download", null)
    }

    private fun refusal(name: String, reason: String, cause: Throwable?): InvalidClassException {
        val e = InvalidClassException(name, reason)
        if (cause != null) e.initCause(cause)
        return e
    }

    private companion object {
        val PRIMITIVES: Map<String, Class<*>> = listOf(
            java.lang.Boolean.TYPE, java.lang.Byte.TYPE, java.lang.Character.TYPE, java.lang.Short.TYPE,
            java.lang.Integer.TYPE, java.lang.Long.TYPE, java.lang.Float.TYPE, java.lang.Double.TYPE,
        ).associateBy { it.name }
    }
}

/** The classes the saved state of a pending download may be made of. */
object PendingStateAllowList {
    private val NAMES: Set<String> = (
        listOf(
            "String", "Number", "Enum", "StackTraceElement",
            "Boolean", "Byte", "Character", "Short", "Integer", "Long", "Float", "Double",
        ).map { "java.lang.$it" } +
            listOf(
                "ArrayList", "HashMap", "LinkedHashMap", "HashSet", "LinkedHashSet", "Locale",
                "Arrays\$ArrayList", "CollSer",
            ).map { "java.util.$it" } +
            listOf(
                "EmptyList", "EmptyMap", "EmptySet", "SingletonList", "SingletonMap", "SingletonSet",
                "UnmodifiableCollection", "UnmodifiableList", "UnmodifiableRandomAccessList",
                "UnmodifiableMap", "UnmodifiableSet",
            ).map { "java.util.Collections\$$it" } +
            listOf("kotlin.Pair", "kotlin.Triple") +
            // held by real error objects of a download (questions.md, Q10)
            listOf(
                "okhttp3.internal.http2.ErrorCode",
                "java.security.cert.CertPathValidatorException\$BasicReason",
                "java.security.cert.PKIXReason",
                "java.security.cert.CertPath\$CertPathRep",
            )
        ).toHashSet()

    /** The dot is part of the prefix: `us.shandian.gigantic` is not the app. */
    private val PREFIXES = listOf("us.shandian.giga.", "org.schabi.newpipe.", "kotlin.collections.")

    /**
     * @param cls resolved without initialization
     * @param fromPlugin only a plugin loader knew it (the app's own loader did not)
     */
    @JvmStatic
    fun isAllowed(cls: Class<*>, fromPlugin: Boolean): Boolean {
        if (fromPlugin) return true
        var type: Class<*> = cls
        while (type.isArray) type = type.componentType ?: return false
        if (type.isPrimitive) return true
        val name = type.name
        return name in NAMES || PREFIXES.any { name.startsWith(it) } || Throwable::class.java.isAssignableFrom(type)
    }

    /**
     * Whether the reader of pending state would accept [cls] on meeting its name in a file:
     * the same resolution and the same list as the reader. Used before a state is saved, so
     * that a readable saved state is never replaced by one that would be refused at the
     * next start.
     */
    @JvmStatic
    fun isAcceptedOnRead(cls: Class<*>): Boolean {
        val known = try {
            Class.forName(cls.name, false, PendingStateAllowList::class.java.classLoader)
        } catch (e: ClassNotFoundException) {
            // only its own loader knows it (a plugin's class, or a primitive): the reader asks the plugins
            return true
        } catch (e: LinkageError) {
            return false
        }
        return isAllowed(known, false)
    }
}
